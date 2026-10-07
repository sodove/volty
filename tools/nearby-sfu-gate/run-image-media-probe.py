"""Runs only an owned disposable host-loopback SFU and a verified synthetic RTP probe."""
import argparse
import hashlib
import json
import pathlib
import re
import socket
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--workspace', required=True)
parser.add_argument('--image', required=True)
parser.add_argument('--name', required=True)
parser.add_argument('--probe-sha256', required=True)
args = parser.parse_args()
def require(condition, message):
    if not condition:
        raise RuntimeError(message)
work = pathlib.Path(args.workspace).resolve()
require(re.fullmatch(r'/tmp/volty-nearby-media-[a-z0-9]+', str(work)), 'Owned workspace required')
require(re.fullmatch(r'sha256:[a-f0-9]{64}', args.image), 'Exact image ID required')
require(re.fullmatch(r'nearby-media-[a-z0-9]+', args.name), 'Owned container name required')
probe = work / 'probe'
require(hashlib.sha256(probe.read_bytes()).hexdigest() == args.probe_sha256, 'Probe identity mismatch')
require((work / 'probe.yaml').read_text().strip().startswith('port: 17881'), 'Disposable probe configuration required')

def docker(*parts):
    return subprocess.check_output(['docker', *parts], text=True, stderr=subprocess.DEVNULL).strip()
require(docker('image', 'inspect', args.image, '--format', '{{.Id}}') == args.image, 'Image identity mismatch')
for port, kind in ((17881, socket.SOCK_STREAM), (17882, socket.SOCK_STREAM), (17884, socket.SOCK_STREAM), (17883, socket.SOCK_DGRAM)):
    with socket.socket(socket.AF_INET, kind) as check:
        check.bind(('127.0.0.1', port))
container = docker('run', '-d', '--name', args.name, '--label', 'volty.nearby.task=' + args.name,
    '--network', 'host', '--memory=512m', '--cpus=1', '--read-only', '--tmpfs', '/tmp:rw,size=16m',
    '--mount', 'type=bind,src=' + str(work / 'probe.yaml') + ',dst=/etc/volty-probe.yaml,readonly',
    args.image, '--config', '/etc/volty-probe.yaml')
require(re.fullmatch(r'[a-f0-9]{64}', container), 'Unexpected container identity')
(work / 'container-id.txt').write_text(container + '\n')
code = 1
try:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('http://127.0.0.1:17881', timeout=1) as response:
                if response.status == 200:
                    break
        except OSError:
            pass
        time.sleep(0.1)
    else:
        raise RuntimeError('Owned SFU startup precondition failed')
    with (work / 'probe.log').open('w') as log:
        code = subprocess.run([str(probe), 'production'], cwd=work, stdout=log,
            stderr=subprocess.STDOUT, timeout=170).returncode
finally:
    identity = docker('inspect', container, '--format', '{{.Id}}|{{.Image}}|{{index .Config.Labels "volty.nearby.task"}}')
    require(identity == container + '|' + args.image + '|' + args.name, 'Refusing cleanup of changed container')
    try:
        with (work / 'server.log').open('w') as log:
            subprocess.run(['docker', 'logs', container], stdout=log, stderr=subprocess.STDOUT, check=True)
    finally:
        docker('rm', '-f', container)
        (work / 'cleanup.json').write_text(json.dumps({'container': container, 'removed': True}) + '\n')
        print('owned_container_removed=true')

for path in work.glob('*.log'):
    if re.search(r'[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}', path.read_text()):
        raise RuntimeError('Credential-looking log withheld')
for line in (work / 'probe.log').read_text().splitlines():
    if re.match(r'^(probe_mode=|nonce=|case=|baseline_|original_exp=|revoke_ack_|post_ack_|participant_control_|participant_observation_|connected_renewal_|race_admission_|expiry_without_|matrix_|readiness_protocol=|node_fence_|receipt_|production_barrier_|panic:)', line):
        print(line)
print('probe_exit=' + str(code))
raise SystemExit(code)
