"""Owned loopback positive control for decoded SFU answers; never production debug."""
import argparse
import collections
import hashlib
import importlib.util
import json
import pathlib
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("signal_boundary", pathlib.Path(__file__).with_name("signal-boundary.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def docker(*parts):
    return subprocess.check_output(["docker", *parts], text=True, stderr=subprocess.DEVNULL).strip()


def validate_workspace(work):
    require({p.name for p in work.iterdir()} == {"probe", "probe.yaml", "signal-boundary.py", "run-signal-boundary-probe.py"}, "Fresh workspace required")
    require(all(not p.is_symlink() and p.is_file() and p.stat().st_nlink == 1 and p.resolve().parent == work for p in work.iterdir()), "Only regular owned files allowed")


def read_trace(stream, trace, stopping, health):
    try:
        while True:
            line = stream.readline(module.MAX_LINE + 1)
            if not line:
                if not stopping.is_set():
                    health["premature_eof"] += 1
                return
            if len(line) > module.MAX_LINE:
                trace.health["oversized"] += 1
                while line and not line.endswith(b"\n"):
                    line = stream.readline(module.MAX_LINE + 1)
                if not line:
                    if not stopping.is_set():
                        health["premature_eof"] += 1
                    return
                continue
            try:
                decoded = line.decode("utf-8")
            except UnicodeDecodeError:
                health["read_error"] += 1
                continue
            trace.consume(decoded)
    except Exception:
        # Exceptions can carry raw input. Export a fixed counter only.
        health["read_error"] += 1


def boundary_result(trace, reader_health, probe_exit, attach_exit):
    counts = collections.Counter(e["stage"] for e in trace.events)
    peers = collections.defaultdict(set)
    for event in trace.events:
        peers[event["peer"]].add(event["stage"])
    required = {"service_read", "participant_dispatch", "transport_process"}
    accepted = (probe_exit == 0 and attach_exit == 0 and not any(trace.health.values())
        and not any(reader_health.values()) and sum(required <= stages for stages in peers.values()) >= 3)
    return {"accepted": accepted, "probe_exit": probe_exit, "attach_exit": attach_exit,
        "stages": dict(counts), "capture_health": trace.health, "reader_health": reader_health,
        "events": trace.events, "processing_is_not_application": True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--probe-sha256", required=True)
    args = parser.parse_args()
    work = pathlib.Path(args.workspace).resolve()
    require(re.fullmatch(r"/tmp/volty-nearby-boundary-[a-z0-9]+", str(work)), "Owned workspace required")
    require(re.fullmatch(r"sha256:[a-f0-9]{64}", args.image), "Exact image ID required")
    require(re.fullmatch(r"[a-f0-9]{64}", args.probe_sha256), "Expected probe hash required")
    validate_workspace(work)
    require(hashlib.sha256((work / "probe").read_bytes()).hexdigest() == args.probe_sha256, "Probe identity mismatch")
    require(docker("image", "inspect", args.image, "--format", "{{.Id}}") == args.image, "Image identity mismatch")
    name = work.name.replace("volty-", "", 1)
    require(not docker("ps", "-aq", "--filter", "name=^/" + name + "$"), "Owned container already exists")
    for port, kind in ((17881, socket.SOCK_STREAM), (17882, socket.SOCK_STREAM), (17883, socket.SOCK_DGRAM)):
        with socket.socket(socket.AF_INET, kind) as check:
            check.bind(("127.0.0.1", port))
    config = (work / "probe.yaml").read_text()
    expected = """port: 17881
bind_addresses:
  - 127.0.0.1
rtc:
  tcp_port: 17882
  udp_port: 17883
  node_ip: 127.0.0.1
  use_external_ip: false
  enable_loopback_candidate: true
keys:
  nearby-probe: local-nearby-probe-secret-not-production-20261003
logging:
  level: warn
"""
    require(config.replace("\r\n", "\n") == expected, "Exact loopback fixture configuration required")
    (work / "probe.yaml").write_text(expected.replace("level: warn", "level: debug"))
    trace = module.BoundaryTrace()
    reader_health = {"read_error": 0, "premature_eof": 0}
    stopping = threading.Event()
    attach = None
    reader = None
    container = docker("run", "-d", "--name", name, "--label", "volty.nearby.task=" + name,
        "--network", "host", "--log-driver", "none", "--memory=512m", "--cpus=1",
        "--read-only", "--tmpfs", "/tmp:rw,size=16m", "--mount",
        "type=bind,src=" + str(work / "probe.yaml") + ",dst=/etc/volty-probe.yaml,readonly",
        args.image, "--config", "/etc/volty-probe.yaml")
    require(re.fullmatch(r"[a-f0-9]{64}", container), "Unexpected container identity")
    probe_exit = None
    try:
        require(docker("inspect", container, "--format", "{{.HostConfig.LogConfig.Type}}") == "none", "Raw log persistence enabled")
        attach = subprocess.Popen(["docker", "attach", "--no-stdin", "--sig-proxy=false", container], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

        reader = threading.Thread(target=read_trace, args=(attach.stdout, trace, stopping, reader_health), daemon=True)
        reader.start()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen("http://127.0.0.1:17881", timeout=1) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            time.sleep(.1)
        else:
            raise RuntimeError("Owned SFU startup failed")
        time.sleep(.3)
        require(attach.poll() is None and reader.is_alive(), "Volatile observer not running")
        print("owned_boundary_observer_started=true raw_log_persistence=false", flush=True)
        probe_exit = subprocess.run([str(work / "probe"), "production"], cwd=work, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=170).returncode
        require(attach.poll() is None and reader.is_alive(), "Volatile observer ended during probe")
    finally:
        identity = docker("inspect", container, "--format", '{{.Id}}|{{.Image}}|{{index .Config.Labels "volty.nearby.task"}}')
        require(identity == container + "|" + args.image + "|" + name, "Refusing changed-container cleanup")
        stopping.set()
        try:
            docker("stop", "--time", "5", container)
            if attach:
                try:
                    attach.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    reader_health["read_error"] += 1
                    attach.kill()
                    attach.wait(timeout=5)
            if reader:
                reader.join(timeout=5)
                require(not reader.is_alive(), "Volatile reader did not retire")
        finally:
            # Stop failure must still remove this exact, already-verified owned container.
            docker("rm", "-f", container)
            (work / "cleanup.json").write_text(json.dumps({"owned_container_removed": True, "raw_log_persistence": False}) + "\n")
            print("owned_container_removed=true", flush=True)
    result = boundary_result(trace, reader_health, probe_exit, attach.returncode if attach else None)
    (work / "boundary.json").write_text(json.dumps(result) + "\n")
    print("boundary_summary=" + json.dumps({k: v for k, v in result.items() if k != "events"}), flush=True)
    require(result["accepted"], "Boundary evidence not accepted; inspect fixed summary")
    print("decoded_answer_boundaries_positive_control=passed", flush=True)


if __name__ == "__main__":
    main()
