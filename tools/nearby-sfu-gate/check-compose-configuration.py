"""Check a merged disposable Compose fixture; never prints credential-bearing configuration."""
import argparse
import json
import pathlib
import re
parser = argparse.ArgumentParser()
parser.add_argument('--config', required=True)
parser.add_argument('--image', required=True)
parser.add_argument('--backend-image')
args = parser.parse_args()
def require(condition, message):
    if not condition:
        raise RuntimeError(message)
require(re.fullmatch(r'sha256:[a-f0-9]{64}', args.image), 'Exact candidate image ID required')
value = json.loads(pathlib.Path(args.config).read_text(encoding='utf-8-sig'))
sfu = value['services']['livekit']
app_service = value['services']['app']
app = app_service['environment']
require(sfu['image'] == args.image and sfu.get('pull_policy') == 'never', 'Candidate image binding missing')
require(app['VOLTY_NEARBY_MODE'] == 'voice_spaces' and app['VOLTY_VOICE_PROVIDER'] == 'livekit', 'New voice graph not selected')
require(app['VOLTY_NEARBY_SFU_CONTROL_ORIGIN'] == 'http://livekit:7880' and app['VOLTY_NEARBY_SINGLETON_ORIGIN'] == 'false', 'Control origin or conservative cleanup changed')
require(app['VOLTY_DATABASE_URL'] == 'jdbc:postgresql://db:5432/volty', 'Fixture database binding changed')
require(any(m['target'] == '/etc/livekit/livekit.yaml' and m.get('read_only') for m in sfu['volumes']), 'Read-only SFU config missing')
require(sfu['entrypoint'] == ['/bin/sh', '-ec'] and '/livekit-server' in str(sfu['command']), 'Existing server launch contract changed')
require(any(m['target'] == '/var/lib/postgresql/data' and m['source'] == 'volty-postgres' for m in value['services']['db']['volumes']), 'Fixture data volume binding changed')
if args.backend_image:
    require(re.fullmatch(r'sha256:[a-f0-9]{64}', args.backend_image), 'Exact backend image ID required')
    require(app_service['image'] == args.backend_image and app_service.get('pull_policy') == 'never', 'Backend candidate image binding missing')
    require(app_service.get('build') is None, 'Backend still depends on source checkout build')
    require(app_service['healthcheck']['test'] == ['CMD', '/usr/bin/curl', '--fail', '--silent', '--max-time', '5', 'http://127.0.0.1:8080/health'], 'Candidate health executable contract changed')
print('merged_compose_checks=' + str(10 if args.backend_image else 7) + ' failed=0')
