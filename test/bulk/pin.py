import hashlib
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PRIOR = Path('/home/kgadberry/projects/minecraft-dev/rkc/forks/FabricAsyncWorldEdit/.claude/worktrees/wf_6f7c091d-06c-3/test/candidate-manifest.json')

def digest(data):
    return hashlib.sha256(data).hexdigest()

def nested(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        metadata = json.loads(archive.read('fabric.mod.json')) if 'fabric.mod.json' in archive.namelist() else {}
        return {'id': metadata.get('id'), 'version': metadata.get('version'), 'sha256': digest(data),
                'nested': [dict(entry=info.filename, **nested(archive.read(info))) for info in archive.infolist() if info.filename.endswith('.jar')]}

def pin(path):
    data = path.read_bytes()
    return dict(path=str(path), **nested(data))

prior = json.loads(PRIOR.read_text())
manifest = {key: prior[key] for key in ['minecraft', 'loader', 'eula', 'seed']}
manifest['runLabel'] = 'polymer-s7-api'
manifest['mods'] = [['polymer', 'polymer'], ['fixture', 'fixture'], ['fabric', 'fabric']]
manifest['priorManifest'] = {'path': str(PRIOR), 'sha256': digest(PRIOR.read_bytes())}
manifest['artifacts'] = {
    'polymer': pin(ROOT / 'build/libs/polymer-bundled-0.18.6-fawe-s7.1+26.3.jar'),
    'fixture': pin(ROOT / 'build/libs/polymer-bundled-0.18.6-fawe-s7.1+26.3-bulk-test.jar'),
    'native': pin(ROOT / 'build/libs/polymer-bundled-0.18.6-fawe-s7.1+26.3-bulk-native.jar'),
    'ordinary': pin(ROOT / 'build/libs/polymer-bundled-0.18.6-fawe-s7.1+26.3-bulk-ordinary.jar'),
    'reference': pin(ROOT / 'handoff/s7-polymer/artifacts/reference/polymer-bundled-0.18.6+26.3.jar'),
    'fabric': pin(Path('/home/kgadberry/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.162.0+26.3/273cd2dcbd92d1559edcc91c9f23fee47f6ff93f/fabric-api-0.162.0+26.3.jar'))
}
(ROOT / 'test/bulk/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
