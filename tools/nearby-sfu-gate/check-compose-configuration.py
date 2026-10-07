"""Check a merged disposable Compose fixture; never prints credential-bearing configuration."""
import argparse
import json
import pathlib
import re
parser = argparse.ArgumentParser()
parser.add_argument('--config', required=True)
parser.add_argument('--image', required=True)
args = parser.parse_args()
def require(condition, message):
    if not condition:
        raise RuntimeError(message)
require(re.fullmatch(r'sha256:[a-f0-9]{64}', args.image), 'Exact candidate image ID required')
value = json.loads(pathlib.Path(args.config).read_text(encoding='utf-8-sig'))
sfu = value['services']['livekit']
app = value['services']['app']['environment']
require(sfu['image'] == args.image and sfu.get('pull_policy') == 'never', 'Candidate image binding missing')
require(app['VOLTY_NEARBY_MODE'] == 'voice_spaces' and app['VOLTY_VOICE_PROVIDER'] == 'livekit', 'New voice graph not selected')
require(app['VOLTY_NEARBY_SFU_CONTROL_ORIGIN'] == 'http://livekit:7880' and app['VOLTY_NEARBY_SINGLETON_ORIGIN'] == 'false', 'Control origin or conservative cleanup changed')
require(app['VOLTY_DATABASE_URL'] == 'jdbc:postgresql://db:5432/volty', 'Fixture database binding changed')
require(any(m['target'] == '/etc/livekit/livekit.yaml' and m.get('read_only') for m in sfu['volumes']), 'Read-only SFU config missing')
require(sfu['entrypoint'] == ['/bin/sh', '-ec'] and '/livekit-server' in str(sfu['command']), 'Existing server launch contract changed')
require(any(m['target'] == '/var/lib/postgresql/data' and m['source'] == 'volty-postgres' for m in value['services']['db']['volumes']), 'Fixture data volume binding changed')
print('merged_compose_checks=7 failed=0')
