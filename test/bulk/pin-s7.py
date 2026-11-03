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
(ROOT / 'test/bulk/s7-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: manifest['artifacts'][key]['sha256'] for key in ('fawe', 'fawetest', 's7probe')}))
