"""Pin the S7 suite manifest: the combined gate's inputs, the s7 client probe and the given FAWE jars."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def pin(path):
    path = Path(path).resolve()
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


if len(sys.argv) != 3:
    sys.exit('usage: pin-s7.py <fawe-dist.jar> <fawe-testmod.jar>')
manifest = json.loads((ROOT / 'test/bulk/combined-manifest.json').read_text())
manifest['runLabel'] = 'fawe-s7-suite'
manifest['artifacts']['fawe'] = pin(sys.argv[1])
manifest['artifacts']['fawetest'] = pin(sys.argv[2])
manifest['artifacts']['s7probe'] = pin(ROOT / 'build/libs/polymer-bundled-0.18.6-fawe-s7.1+26.3-bulk-s7.jar')
# Older watchers: clients from pin-clients.py, probes from build-older.js, server-side ViaFabric.
manifest['olderClients'] = json.loads((ROOT / 'test/bulk/older-clients.json').read_text())
for version, entry in manifest['olderClients'].items():
    missing = [key for key in ('dataVersion', 'protocol', 'client', 'libraries', 'assetIndex') if not entry.get(key)]
    if missing:
        sys.exit(f'older-clients.json {version} lacks {missing}; rerun pin-clients.py')
    manifest['artifacts'][f'native-{version}'] = pin(ROOT / f'build/libs/polymer-bulk-native-{version}.jar')
    manifest['artifacts'][f's7probe-{version}'] = pin(ROOT / f'build/libs/polymer-bulk-s7-{version}.jar')
VIA = Path('/mnt/WORKSPACE/scratchpad/fawe-s7-evidence/client-inputs/via')
manifest['artifacts']['viafabric'] = pin(VIA / 'ViaFabric-0.4.22+185-26.x.jar')
manifest['artifacts']['viabackwards'] = pin(VIA / 'ViaBackwards-5.12.1-SNAPSHOT.jar')
manifest['mods'] += [['viafabric', 'viafabric'], ['viabackwards', 'viabackwards']]
(ROOT / 'test/bulk/s7-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: value['sha256'][:16] for key, value in manifest['artifacts'].items()
                  if key in ('fawe', 'fawetest', 's7probe', 'viafabric', 'viabackwards') or '-26.' in key}))
