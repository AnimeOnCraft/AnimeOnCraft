"""Preserve the verified public baseline and the pending Waystones fix; no unrelated changes."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.tools/ecology-20261025'
NAME = 'animeoncraft-admission-0.3.0-friends-dev.jar'
PREFIX = 'ru/animeoncraft/integration/neoforge/'
pending = ROOT / '.tools/public-waystones-20261025/server' / NAME
assert hashlib.sha256(pending.read_bytes()).hexdigest() == '3c05b12979f3cab63d8c33ba3001c4623133c744cf7095cacf8c0306e9a17a71'
built = ROOT / 'minecraft/integration/neoforge/build/libs' / NAME
added = {PREFIX + name + '.class' for name in ['NightRules', 'ServerEcology', 'mixin/CleanMaidDeathDropMixin']}

def assemble(target, probe=False):
    names = added | ({PREFIX+'TerritoryProtectionProbe.class', PREFIX+'EcologyProbe.class'} if probe else set())
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pending) as old, zipfile.ZipFile(built) as current, zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as out:
        mixins = json.loads(old.read('animeoncraft.mixins.json'))
        mixins['mixins'].insert(0, 'CleanMaidDeathDropMixin')
        for info in old.infolist():
            data = current.read(info.filename) if info.filename in names else old.read(info.filename)
            if info.filename == 'animeoncraft.mixins.json': data = (json.dumps(mixins,indent=2)+'\n').encode()
            out.writestr(info, data)
        for name in sorted(names-set(old.namelist())):
            info = zipfile.ZipInfo(name, (2026,10,25,0,0,0));info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info,current.read(name))
    with zipfile.ZipFile(pending) as before, zipfile.ZipFile(target) as after:
        differences={name for name in set(before.namelist())|set(after.namelist()) if name not in before.namelist() or name not in after.namelist() or before.read(name)!=after.read(name)}
    assert differences == names | {'animeoncraft.mixins.json'}, differences
    return {'path':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'changed_from_pending_waystones':sorted(differences)}

result={'local_only':True,'installed':False,'public_baseline_sha256':'f5d3fe091e06506cd144bb27479e99b6db83d2b67fdda224773b8f800d5930a3',
        'server':assemble(OUT/'server'/NAME),'probe':assemble(OUT/'probe'/NAME,True)}
(OUT/'patch.json').write_text(json.dumps(result,indent=2)+'\n','utf-8')
print(json.dumps(result))
