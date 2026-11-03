"""Pin older native clients (26.1.x, 26.2) for the S7 Via watchers from the Loom and Gradle caches.

Never downloads: anything absent is listed and the script exits 2. Every input is checked against the
SHA-1 in that version's Mojang metadata before it is pinned.
"""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

CACHE = Path.home() / '.gradle/caches'
ASSETS = CACHE / 'fabric-loom/assets'
# Libraries Gradle never resolved, fetched once in Maven layout (see fetch-clients.py).
FETCHED = Path('/mnt/WORKSPACE/scratchpad/fawe-s7-evidence/client-inputs/libraries')
OUTPUT = Path(__file__).resolve().parent / 'older-clients.json'


def artifact(path, sha1):
    path = path.resolve(strict=True)
    data = path.read_bytes()
    if hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError(f'Upstream SHA1 mismatch: {path}')
    return {'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}


def library(item, missing):
    group, module, version, *classifier = item['name'].split(':')
    file = f'{module}-{version}' + (f'-{classifier[0]}' if classifier else '') + '.jar'
    matches = list((CACHE / 'modules-2/files-2.1' / group / module / version).glob('*/' + file))
    fetched = FETCHED / item['downloads']['artifact']['path']
    if not matches and fetched.is_file():
        matches = [fetched]
    if len(matches) != 1:
        missing.append({'library': item['name'], 'url': item['downloads']['artifact']['url'],
                        'path': item['downloads']['artifact']['path'],
                        'sha1': item['downloads']['artifact']['sha1'], 'found': [str(m) for m in matches]})
        return None
    return artifact(matches[0], item['downloads']['artifact']['sha1'])


def linux(item):
    allowed = not item.get('rules')
    for rule in item.get('rules', []):
        os = rule.get('os', {})
        if os.get('name', 'linux') == 'linux' and os.get('arch', 'amd64') in ('amd64', 'x86_64'):
            allowed = rule['action'] == 'allow'
    return allowed


def pin(version, missing):
    mc = CACHE / 'fabric-loom' / version
    info_path = mc / 'mojang_minecraft_info.json'
    info = json.loads(info_path.read_text())
    if info['id'] != version:
        raise ValueError(f'{info_path} describes {info["id"]}')
    libraries = [library(item, missing) for item in info['libraries'] if linux(item)]
    index_name = f'{version}-{info["assetIndex"]["id"]}'
    index_path = ASSETS / 'indexes' / f'{index_name}.json'
    if not index_path.exists():
        missing.append({'assetIndex': index_name, 'url': info['assetIndex']['url'], 'sha1': info['assetIndex']['sha1']})
        index = None
    else:
        index = artifact(index_path, info['assetIndex']['sha1'])
        objects = json.loads(index_path.read_text())['objects'].values()
        absent = [obj['hash'] for obj in objects if not (ASSETS / 'objects' / obj['hash'][:2] / obj['hash']).is_file()]
        if absent:
            missing.append({'assetObjects': index_name, 'hashes': sorted(set(absent))})
    client = artifact(mc / 'minecraft-client.jar', info['downloads']['client']['sha1'])
    with zipfile.ZipFile(client['path']) as jar:
        stamp = json.loads(jar.read('version.json'))
    return {'version': version, 'metadata': {'path': str(info_path), 'sha256': hashlib.sha256(info_path.read_bytes()).hexdigest()},
            'dataVersion': stamp['world_version'], 'protocol': stamp['protocol_version'], 'client': client,
            'libraries': libraries, 'assetsRoot': str(ASSETS), 'assetIndex': index, 'assetIndexName': index_name}


versions = sys.argv[1:] or ['26.1.2', '26.2']
missing = []
pinned = {version: pin(version, missing) for version in versions}
if missing:
    print(json.dumps({'missing': missing}, indent=2))
    sys.exit(2)
OUTPUT.write_text(json.dumps(pinned, indent=2) + '\n')
print(json.dumps({version: {'client': entry['client']['sha256'][:16], 'libraries': len(entry['libraries'])}
                  for version, entry in pinned.items()}))
