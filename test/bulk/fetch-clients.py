"""Fetch what pin-clients.py reported missing, keeping only files whose SHA-1 matches Mojang's metadata.

Usage: python3 -I pin-clients.py > missing.json; python3 -I fetch-clients.py missing.json
Libraries go to pin-clients' FETCHED dir; an asset index and objects go to Loom's asset root,
where Loom itself would put them.
"""
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

ASSETS = Path.home() / '.gradle/caches/fabric-loom/assets'
FETCHED = Path('/mnt/WORKSPACE/scratchpad/fawe-s7-evidence/client-inputs/libraries')


def fetch(url, sha1, target):
    if target.is_file() and hashlib.sha1(target.read_bytes()).hexdigest() == sha1:
        return False
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError(f'SHA1 mismatch for {url}')
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + '.part')
    partial.write_bytes(data)
    partial.replace(target)
    return True


report = json.loads(Path(sys.argv[1]).read_text())
fetched = 0
for entry in report['missing']:
    if 'library' in entry:
        if entry['found']:
            raise ValueError(f'Ambiguous cache for {entry["library"]}: {entry["found"]}')
        fetched += fetch(entry['url'], entry['sha1'], FETCHED / entry['path'])
    elif 'assetIndex' in entry:
        fetched += fetch(entry['url'], entry['sha1'], ASSETS / 'indexes' / f'{entry["assetIndex"]}.json')
    elif 'assetObjects' in entry:
        for digest in entry['hashes']:
            fetched += fetch(f'https://resources.download.minecraft.net/{digest[:2]}/{digest}', digest,
                             ASSETS / 'objects' / digest[:2] / digest)
print(json.dumps({'fetched': fetched}))
