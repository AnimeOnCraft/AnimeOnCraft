"""Build a deterministic external HD archive; never put it in the installer bootstrap."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TITLE = ROOT / 'minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/textures/title'
OUT = ROOT / '.tools/title-media'
OUT.mkdir(parents=True, exist_ok=True)
animation = json.loads((TITLE / 'animation.json').read_text('utf-8'))
assert (animation['width'], animation['height'], animation['fps'], animation['frames']) == (1920, 1080, 24, 238)
temporary = OUT / 'title-building.zip'
with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_STORED) as archive:
    for number in range(animation['frames']):
        name = f'frame_{number:04}.jpg'
        data = (TITLE / name).read_bytes()
        assert 0 < len(data) <= 1048576
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o100644 << 16
        archive.writestr(info, data)
digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
target = OUT / f'{digest}.zip'
temporary.replace(target)
manifest = {'format_version': 1, 'sha256': digest, 'size': target.stat().st_size,
            'url': f'https://charizma1337.github.io/animeoncraft-updates/assets/title/{digest}.zip',
            'width': 1920, 'height': 1080, 'fps': 24, 'frames': 238}
(TITLE / 'media.json').write_text(json.dumps(manifest, indent=2) + '\n', 'utf-8')
(OUT / 'artifact.json').write_text(json.dumps({'archive': str(target), 'manifest': manifest,
    'local_only': True, 'published': False}, indent=2) + '\n', 'utf-8')
print(json.dumps({'archive': str(target), 'bytes': target.stat().st_size, 'sha256': digest}))
