"""Virtual Begode T4 that rides: replays real FFE1 cycles with a coherent synthetic ride.

Used by the compact-history field gate (Task 8 of docs/superpowers/plans/2026-10-29-compact-history-storage.md)
instead of a real ride. Like tools/leaperkim-emulator it is a Bumble GATT peripheral on Android Emulator Netsim.

What is real and what is rewritten
  * Frame SHAPES and CADENCE come from real captures: every capture is reassembled into 24-byte frames and cut into
    cycles of [0x00 live, 0x01 smart BMS, 0x04 odometer, 0x07 motion] with the cycle's own notification offsets
    (5 notifications of 20,20,20,20,16 bytes, ~3.2 Hz per frame type). Cycles are replayed in order and looped.
  * VALUES are rewritten to one coherent ride: speed (city stop-and-go profile), the since-power-on counter (live frame
    bytes 8..9) and the lifetime odometer (0x04 bytes 2..5) are integrals of that same speed, so the odometer gate's
    witness agrees; currents and duty follow speed and acceleration; the pack voltage sags and drifts down, and the
    0x01 branch voltage is scaled with the live voltage (the R13 witness rejects a 0x01 that disagrees). The 0x04
    alert byte is zeroed. The captures themselves are free-spin calibration runs (80 km/h, no distance), which is why
    the speed is not taken from them.
  * The same simulated ride drives the emulator's GPS (`geo fix` over the emulator console at 1 Hz) along a synthetic
    city route, so wheel distance and GPS distance agree. The route is made up; it is not a rider's track.

Never writes: the FFE1 write handler is a no-op that logs, as in the Leaperkim tool.

Input: Volty "BLE diagnostics" text exports (untrusted data: parsed with a strict regex, never executed) or the
chronological TSV in this directory. Raw exports are not committed; the TSV default has no address.

    py -3.12 tools\\begode-emulator\\emulate_begode_ride.py --capture dump1.txt --capture dump2.txt ^
        --run-seconds 720 --odometer-start 2809000 --power-on-start 100

    --drop-at 480 --drop-for 40       the wheel drops the link at ride second 480 and is unreachable for 40 s
    --run-seconds N                   the wheel is "switched off" N s after the app subscribed: the script
                                      disconnects, stops advertising, stops the GPS and exits
    --no-gps                          do not drive the emulator GPS
"""
from __future__ import annotations

import argparse
import asyncio
import math
import os
import random
import re
import socket
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "leaperkim-emulator"))

import bumble.logging  # noqa: E402
from bumble.device import Device, DeviceConfiguration  # noqa: E402
from bumble.gatt import Characteristic, CharacteristicValue, Service  # noqa: E402
from bumble.hci import Address  # noqa: E402
from bumble.transport import open_transport  # noqa: E402
from emulate_leaperkim import make_advertising_data  # noqa: E402

SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
SPEED_KMH_PER_UNIT = 0.036
FRAME_TYPES = [0, 1, 4, 7]
CYCLE_BYTES = 96

DIAG_ROW = re.compile(
    r"^(?P<ts>\d{13})\s+\S+\s+(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\s+\S+\s+\S+\s+(?P<len>\d+)\s+(?P<hex>(?:[0-9A-F]{2}\s*)+)$",
    re.IGNORECASE)
TSV_ROW = re.compile(r"^\s*(?P<ms>\d+)\s+(?P<hex>(?:[0-9A-F]{2}\s*)+)$", re.IGNORECASE)


# ---------------------------------------------------------------- capture -> cycles

@dataclass
class Cycle:
    start_ms: int
    offsets: list[int]          # notification offsets within the cycle, ms
    frames: dict[int, bytes]    # type -> 24 bytes
    order: list[int]            # frame types in stream order


def load_records(path: Path) -> list[tuple[int, bytes]]:
    recs: list[tuple[int, bytes]] = []
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    rows = text.splitlines()
    for i, line in enumerate(rows):
        m = DIAG_ROW.match(line.strip())
        if m:
            payload = bytes.fromhex(m["hex"].replace(" ", ""))
            if len(payload) == int(m["len"]):
                recs.append((int(m["ts"]), payload))
    if recs:
        # exports list oldest first in the "records oldest first" form; keep a stable sort by time.
        recs.sort(key=lambda r: r[0])
        base = recs[0][0]
        return [(t - base, p) for t, p in recs]
    for line in rows:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = TSV_ROW.match(line)
        if m:
            recs.append((int(m["ms"]), bytes.fromhex(m["hex"].replace(" ", ""))))
    return recs


def cycles_from_records(recs: list[tuple[int, bytes]]) -> list[Cycle]:
    """Group notifications into cycles that each end on the short (<20 byte) notification."""
    cycles: list[Cycle] = []
    group: list[tuple[int, bytes]] = []
    for t, p in recs:
        group.append((t, p))
        if len(p) < 20:
            blob = b"".join(x[1] for x in group)
            if len(blob) == CYCLE_BYTES:
                frames = [blob[i:i + 24] for i in range(0, CYCLE_BYTES, 24)]
                ok = all(f[:2] == b"\x55\xaa" and f[20:24] == b"\x5a\x5a\x5a\x5a" for f in frames)
                order = [f[18] for f in frames]
                if ok and sorted(order) == sorted(FRAME_TYPES):
                    t0 = group[0][0]
                    cycles.append(Cycle(t0, [g[0] - t0 for g in group], {f[18]: f for f in frames}, order))
            group = []
    return cycles


# ---------------------------------------------------------------- the simulated ride

def build_speed_profile(seed: int, total_s: float, end_stop_at: float | None, step: float = 0.1):
    """Stop-and-go city riding: returns speeds (km/h) and cumulative distance (m) at `step` resolution."""
    rnd = random.Random(seed)
    n = int(total_s / step) + 1
    speeds = [0.0] * n
    t = 0.0
    v = 0.0
    state = "wait"
    until = 12.0
    target = 0.0
    for i in range(n):
        t = i * step
        forced_stop = end_stop_at is not None and t >= end_stop_at
        if forced_stop:
            target = 0.0
            state = "brake"
        elif state == "wait" and t >= until:
            state = "go"
            target = rnd.uniform(17.0, 29.0)
        elif state == "go" and v >= target - 0.3:
            state = "cruise"
            until = t + rnd.uniform(35.0, 90.0)
        elif state == "cruise" and t >= until:
            state = "brake"
            target = 0.0
        elif state == "brake" and v <= 0.05 and not forced_stop:
            state = "wait"
            until = t + rnd.uniform(5.0, 12.0)
            v = 0.0
        a_kmh_s = 0.0
        if state == "go":
            a_kmh_s = 4.3                       # ~1.2 m/s2
        elif state == "brake":
            a_kmh_s = -5.4                      # ~1.5 m/s2
        elif state == "cruise":
            wobble = target + 2.0 * math.sin(t / 7.0) + 1.0 * math.sin(t / 2.3)
            a_kmh_s = max(-2.0, min(2.0, (wobble - v) * 1.5))
        v = max(0.0, v + a_kmh_s * step)
        speeds[i] = v
    dist = [0.0] * n
    for i in range(1, n):
        dist[i] = dist[i - 1] + (speeds[i - 1] + speeds[i]) / 2 / 3.6 * step
    return speeds, dist, step


class Ride:
    def __init__(self, speeds, dist, step):
        self.speeds, self.dist, self.step = speeds, dist, step

    def _idx(self, t: float) -> int:
        return max(0, min(len(self.speeds) - 1, int(t / self.step)))

    def speed_kmh(self, t: float) -> float:
        return self.speeds[self._idx(t)]

    def distance_m(self, t: float) -> float:
        i = self._idx(t)
        return self.dist[i]

    def accel_ms2(self, t: float) -> float:
        i = self._idx(t)
        j = min(len(self.speeds) - 1, i + 1)
        return (self.speeds[j] - self.speeds[i]) / 3.6 / self.step


# Synthetic city route: Manhattan blocks, generic coordinates. A closed loop; the rider goes round and round.
ROUTE_ORIGIN = (55.7500, 37.6100)  # lat, lon


def build_route() -> list[tuple[float, float]]:
    pts_m = [(0.0, 0.0)]
    for dx, dy in [(0, 420), (380, 0), (0, 300), (-250, 0), (0, 380), (520, 0), (0, -1100), (-650, 0)]:
        x, y = pts_m[-1]
        pts_m.append((x + dx, y + dy))
    x, y = pts_m[-1]
    pts_m.append((0.0, 0.0))  # close the loop
    lat0, lon0 = ROUTE_ORIGIN
    m_per_deg_lat = 111_320.0
    m_per_deg_lon = 111_320.0 * math.cos(math.radians(lat0))
    return [(lat0 + y / m_per_deg_lat, lon0 + x / m_per_deg_lon) for x, y in pts_m]


def position_on_route(route, d: float):
    seg = []
    total = 0.0
    for (la1, lo1), (la2, lo2) in zip(route, route[1:]):
        dy = (la2 - la1) * 111_320.0
        dx = (lo2 - lo1) * 111_320.0 * math.cos(math.radians(la1))
        length = math.hypot(dx, dy)
        seg.append((la1, lo1, la2, lo2, length))
        total += length
    d = d % total
    for la1, lo1, la2, lo2, length in seg:
        if d <= length:
            f = d / length if length else 0.0
            return la1 + (la2 - la1) * f, lo1 + (lo2 - lo1) * f
        d -= length
    return route[-1]


class EmulatorConsole:
    """Minimal emulator-console client for `geo fix` (auth token from the user's profile)."""

    def __init__(self, port: int):
        self.port = port
        self.sock: socket.socket | None = None

    def open(self) -> None:
        token = (Path.home() / ".emulator_console_auth_token").read_text().strip()
        self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        self._read()
        self._send(f"auth {token}")

    def _read(self) -> str:
        assert self.sock is not None
        try:
            return self.sock.recv(4096).decode("utf-8", "replace")
        except socket.timeout:
            return ""

    def _send(self, line: str) -> str:
        assert self.sock is not None
        self.sock.sendall((line + "\r\n").encode())
        return self._read()

    def geo_fix(self, lat: float, lon: float, alt: float, sats: int, speed_ms: float) -> str:
        # `geo fix <lon> <lat> <alt> <satellites> <velocity m/s>` (Netsim GNSS HAL accepts the velocity)
        return self._send(f"geo fix {lon:.6f} {lat:.6f} {alt:.1f} {sats} {speed_ms:.2f}")

    def close(self) -> None:
        if self.sock:
            self.sock.close()


# ---------------------------------------------------------------- frame rewriting

def u16(v: int) -> bytes:
    return (v & 0xFFFF).to_bytes(2, "big")


def i16(v: int) -> bytes:
    return (max(-32768, min(32767, v)) & 0xFFFF).to_bytes(2, "big")


class FrameRewriter:
    def __init__(self, ride: Ride, odometer_start: int, power_on_start: int, template_live_raw: int):
        self.ride = ride
        self.odo0 = odometer_start
        self.po0 = power_on_start
        self.template_live_raw = template_live_raw

    def rewrite(self, cycle: Cycle, t: float) -> bytes:
        kmh = self.ride.speed_kmh(t)
        accel = self.ride.accel_ms2(t)
        dist = int(self.ride.distance_m(t))
        # currents: a plausible but made-up load; battery current is discharge-positive after the decoder's negation
        i_bat = 1.2 + 0.11 * kmh + 7.0 * max(0.0, accel) + 3.0 * min(0.0, accel)
        i_phase = i_bat * 1.7
        duty_pct = int(round(min(100.0, kmh / 85.0 * 100.0)))
        orig_live = int.from_bytes(cycle.frames[0][2:4], "big")
        # the pack voltage drifts down 1 raw unit (10 mV on the live scale) per 30 s and sags with load
        live_raw = max(1, self.template_live_raw - int(t / 30.0) - int(i_bat * 0.8))
        out = []
        for ty in cycle.order:
            f = bytearray(cycle.frames[ty])
            if ty == 0:
                f[2:4] = u16(live_raw)
                f[4:6] = i16(-int(round(kmh / SPEED_KMH_PER_UNIT)))       # captures sign forward negative
                f[8:10] = u16(self.po0 + dist)
                f[10:12] = i16(int(round(i_phase * 100)))
            elif ty == 1:
                v_orig = int.from_bytes(f[6:8], "big")
                f[6:8] = u16(int(round(v_orig * live_raw / max(1, orig_live))))
            elif ty == 4:
                f[2:6] = (self.odo0 + dist).to_bytes(4, "big")
                f[14] = 0
            elif ty == 7:
                f[2:4] = i16(-int(round(i_bat * 100)))
                f[8:10] = i16(duty_pct)
            out.append(bytes(f))
        return b"".join(out)


# ---------------------------------------------------------------- BLE

def log(msg: str) -> None:
    print(f"{time.strftime('%H:%M:%S')} {msg}", flush=True)


async def run(args: argparse.Namespace) -> None:
    all_cycles: list[Cycle] = []
    for path in args.capture:
        recs = load_records(path)
        cyc = cycles_from_records(recs)
        log(f"capture {path.name}: {len(recs)} notifications -> {len(cyc)} complete cycles")
        all_cycles.extend(cyc)
    if not all_cycles:
        raise SystemExit("no complete cycles in the given captures")
    gaps = [b.start_ms - a.start_ms for a, b in zip(all_cycles, all_cycles[1:])]
    sane = [g for g in gaps if 250 <= g <= 400]
    mean_gap = sum(sane) / len(sane) if sane else 313
    log(f"{len(all_cycles)} cycles, mean cycle period {mean_gap:.0f} ms ({1000 / mean_gap:.2f} Hz per frame type)")
    template_live = int.from_bytes(all_cycles[0].frames[0][2:4], "big")

    total_s = args.run_seconds + 300 if args.run_seconds else 4 * 3600
    end_stop = args.run_seconds - 25 if args.run_seconds else None
    speeds, dist, step = build_speed_profile(args.seed, total_s, end_stop)
    ride = Ride(speeds, dist, step)
    rewriter = FrameRewriter(ride, args.odometer_start, args.power_on_start, template_live)
    route = build_route()
    if args.summary_only:
        print(f"profile: {len(speeds) * step:.0f} s, ride distance at run end {ride.distance_m(args.run_seconds or 0):.0f} m")
        return

    bumble.logging.setup_basic_logging("WARNING")
    config = DeviceConfiguration(
        name=args.name, address=Address(args.address), advertising_data=make_advertising_data(args.name),
        scan_response_data=b"", advertising_interval_min=100.0, advertising_interval_max=150.0)

    state = {"t0": None, "connection": None, "replay": None, "gps": None, "drop_until": 0.0, "off": False,
             "sent_cycles": 0, "drop_done": False}
    done = asyncio.Event()

    def ride_t() -> float:
        return 0.0 if state["t0"] is None else time.monotonic() - state["t0"]

    characteristic = Characteristic(
        CHARACTERISTIC_UUID,
        Characteristic.Properties.READ | Characteristic.Properties.WRITE
        | Characteristic.Properties.WRITE_WITHOUT_RESPONSE | Characteristic.Properties.NOTIFY,
        Characteristic.READABLE | Characteristic.WRITEABLE,
        CharacteristicValue(read=lambda _c: b"", write=lambda _c, v: log(f"[write ignored] FFE1 {v.hex(' ')}")))
    service = Service(SERVICE_UUID, [characteristic])

    async def gps_task() -> None:
        if args.no_gps:
            return
        console = EmulatorConsole(args.console_port)
        console.open()
        n = 0
        while True:
            t = ride_t()
            lat, lon = position_on_route(route, ride.distance_m(t))
            reply = console.geo_fix(lat, lon, 60.0, 9, ride.speed_kmh(t) / 3.6)
            n += 1
            if n == 1 or n % 120 == 0:
                log(f"[gps] fix #{n} t={t:.0f}s lat={lat:.5f} lon={lon:.5f} v={ride.speed_kmh(t):.1f}km/h {reply.strip()[:40]!r}")
            await asyncio.sleep(1.0)

    async def replay_task(device: Device) -> None:
        i = 0
        next_start = time.monotonic()
        while True:
            cyc = all_cycles[i % len(all_cycles)]
            t = ride_t()
            payload = rewriter.rewrite(cyc, t)
            base = time.monotonic()
            chunks = [payload[k:k + 20] for k in range(0, len(payload), 20)]
            for k, chunk in enumerate(chunks):
                off = cyc.offsets[k] / 1000.0 if k < len(cyc.offsets) else 0.0
                wait = base + off - time.monotonic()
                if wait > 0:
                    await asyncio.sleep(wait)
                await device.notify_subscribers(characteristic, chunk)
            state["sent_cycles"] += 1
            if state["sent_cycles"] % 200 == 0:
                log(f"[replay] t={t:.0f}s cycles={state['sent_cycles']} speed={ride.speed_kmh(t):.1f}km/h dist={ride.distance_m(t):.0f}m")
            nxt = all_cycles[(i + 1) % len(all_cycles)]
            gap = (nxt.start_ms - cyc.start_ms) / 1000.0
            if not 0.25 <= gap <= 0.4:
                gap = mean_gap / 1000.0
            next_start += gap
            delay = next_start - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
            else:
                next_start = time.monotonic()
            i += 1

    async def schedule(device: Device) -> None:
        """Fires the drop and the switch-off by ride time."""
        while True:
            await asyncio.sleep(0.5)
            t = ride_t()
            if state["t0"] is None:
                continue
            if args.drop_at is not None and not state["drop_done"] and t >= args.drop_at:
                state["drop_done"] = True
                state["drop_until"] = time.monotonic() + args.drop_for
                log(f"[drop] t={t:.0f}s: the wheel leaves range for {args.drop_for:.0f} s")
                await device.stop_advertising()
                conn = state["connection"]
                if conn is not None:
                    await conn.disconnect()
                await asyncio.sleep(args.drop_for)
                log("[drop] back in range, advertising")
                await device.start_advertising(auto_restart=False)
            if args.run_seconds and t >= args.run_seconds:
                state["off"] = True
                log(f"[off] t={t:.0f}s: the wheel is switched off")
                for key in ("replay", "gps"):
                    if state[key]:
                        state[key].cancel()
                try:
                    await device.stop_advertising()
                except Exception:
                    pass
                conn = state["connection"]
                if conn is not None:
                    try:
                        await conn.disconnect()
                    except Exception:
                        pass
                done.set()
                return

    def on_subscription(_bearer, notify_enabled: bool, _indicate: bool) -> None:
        if notify_enabled:
            log("[gatt] FFE1 subscribed")
            if state["t0"] is None:
                state["t0"] = time.monotonic()
                state["gps"] = asyncio.create_task(gps_task())
                state["sched"] = asyncio.create_task(schedule(device))
            if state["replay"] is None or state["replay"].done():
                state["replay"] = asyncio.create_task(replay_task(device))
        else:
            log("[gatt] FFE1 unsubscribed")
            if state["replay"] is not None:
                state["replay"].cancel()
                state["replay"] = None

    characteristic.add_listener(Characteristic.EVENT_SUBSCRIPTION, on_subscription)

    async with await open_transport(args.transport) as hci:
        device = Device.from_config_with_hci(config, hci.source, hci.sink)
        device.add_service(service)

        def on_connection(connection) -> None:
            state["connection"] = connection
            log("[ble] central connected")

            def on_disc(*_a) -> None:
                log("[ble] central disconnected")
                state["connection"] = None
                if state["replay"] is not None:
                    state["replay"].cancel()
                    state["replay"] = None
                if not state["off"] and time.monotonic() >= state["drop_until"]:
                    asyncio.get_event_loop().create_task(device.start_advertising(auto_restart=False))

            connection.on("disconnection", on_disc)

        device.on("connection", on_connection)
        await device.power_on()
        await device.start_advertising(auto_restart=False)
        log(f"[ble] advertising as {args.name}; waiting for the central")
        waiter = asyncio.create_task(done.wait())
        term = asyncio.ensure_future(hci.source.terminated)
        await asyncio.wait({waiter, term}, return_when=asyncio.FIRST_COMPLETED)
        for key in ("replay", "gps", "sched"):
            task = state.get(key)
            if task:
                task.cancel()
        log("exiting")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--capture", type=Path, action="append", default=None,
                   help="diagnostics text or TSV; repeatable (default: the committed T4 TSV)")
    p.add_argument("--transport", default="android-netsim")
    p.add_argument("--name", default="GotWay_12345")
    p.add_argument("--address", default="C0:DE:CA:FE:BA:BE")
    p.add_argument("--console-port", type=int, default=5554)
    p.add_argument("--no-gps", action="store_true")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--run-seconds", type=float, default=0.0, help="switch the wheel off this many seconds after the app subscribed (0: never)")
    p.add_argument("--drop-at", type=float, default=None)
    p.add_argument("--drop-for", type=float, default=40.0)
    p.add_argument("--odometer-start", type=int, default=2_809_000, help="lifetime odometer at ride start, metres")
    p.add_argument("--power-on-start", type=int, default=100, help="since-power-on counter at ride start, metres")
    p.add_argument("--summary-only", action="store_true")
    return p


def main() -> None:
    args = build_parser().parse_args()
    if not args.capture:
        args.capture = [Path(__file__).with_name("t4-no-bms-notifications.tsv")]
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
