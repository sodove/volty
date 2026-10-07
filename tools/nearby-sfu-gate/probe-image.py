"""Owned loopback artifact probe with public disposable credentials, never production JWTs."""
import argparse
import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--url', required=True)
args = parser.parse_args()
u = urllib.parse.urlsplit(args.url)
if u.scheme != 'http' or u.hostname not in ('127.0.0.1', 'localhost') or not u.port or u.path not in ('', '/') or u.username or u.password or u.query or u.fragment:
    raise SystemExit('Owned loopback origin required')
origin = args.url.rstrip('/')
room = 'artifact-' + uuid.uuid4().hex
identity = uuid.uuid4().hex
now = int(time.time())
def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')
header = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}, separators=(',', ':')).encode())
claims = b64(json.dumps({'iss': 'nearby-probe', 'iat': now, 'nbf': now, 'exp': now + 120,
    'video': {'room': room, 'roomAdmin': True}}, separators=(',', ':')).encode())
unsigned = header + '.' + claims
token = unsigned + '.' + b64(hmac.new(b'local-nearby-probe-secret-not-production-20261003', unsigned.encode(), hashlib.sha256).digest())
def post(path, body, authorized=True):
    headers = {'Content-Type': 'application/json'}
    if authorized:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(origin + path, data=json.dumps(body).encode(), headers=headers, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=3) as reply:
            return reply.status, reply.read(8192)
    except urllib.error.HTTPError as reply:
        return reply.code, reply.read(8192)

for attempt in range(50):
    try:
        status, payload = post('/volty/leases/readiness', {'room': room})
        if status == 200 and json.loads(payload).get('ready'):
            break
    except (OSError, ValueError):
        pass
    time.sleep(0.1)
else:
    raise SystemExit('Owned SFU readiness precondition failed')
value = json.loads(payload)
assert value['protocolVersion'] == 4 and value['singleNode'] is True and value['maximumLeaseSeconds'] == 30
assert value['capacity'] > value['slotsUsed'] and 0 <= value['lastSweepAgeMillis'] < 2000
assert post('/volty/leases/readiness', {'room': room}, False)[0] in (401, 403)
# Even a valid room admin cannot activate prototype barriers in this artifact.
assert post('/volty/test/barrier/arm', {'room': room, 'identity': identity})[0] == 404
body = {'room': room, 'identity': identity, 'nodeEpoch': value['nodeEpoch'], 'ttlSeconds': 30}
assert post('/volty/leases/register', body)[0] == 204
assert post('/volty/leases/renew-connected', dict(body, participantSid='never-connected'))[0] == 409
assert post('/volty/leases/participant', {'room': room, 'identity': identity, 'nodeEpoch': value['nodeEpoch']})[0] == 200
assert post('/volty/leases/renew', body)[0] == 204
assert post('/volty/leases/revoke', body)[0] == 204
assert post('/volty/leases/register', body)[0] == 409
print(json.dumps({'artifact_checks': 9, 'failed': 0, 'protocol': 4, 'test_barrier': 'absent'}))
