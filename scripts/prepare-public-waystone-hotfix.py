"""Build a server-only patch from the verified public JAR; preserve all unrelated entries."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.tools/public-waystones-20261025'
BASE_SHA = 'f5d3fe091e06506cd144bb27479e99b6db83d2b67fdda224773b8f800d5930a3'
NAME = 'animeoncraft-admission-0.3.0-friends-dev.jar'
PREFIX = 'ru/animeoncraft/integration/neoforge/'
baseline = Path(sys.argv[1]).resolve()
assert hashlib.sha256(baseline.read_bytes()).hexdigest() == BASE_SHA, 'Expected verified 0.8.12 server JAR'
built = ROOT / 'minecraft/integration/neoforge/build/libs' / NAME
javap = Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/javap.exe')
def methods(jar):
    result = subprocess.run([str(javap), '-c', '-p', '-classpath', str(jar), 'ru.animeoncraft.integration.neoforge.TerritoryProtection'],capture_output=True,check=True,text=True).stdout
    chunks = re.split(r'(?m)(?=^  (?:public|private|protected|static).*\([^\n]*\);$)', result)
    return {part.splitlines()[0]: re.sub(r'#\d+', '#CP', part) for part in chunks}
old, new = methods(baseline), methods(built)
assert old.keys() == new.keys(), 'Protection method list changed; review separately'
changed_methods = [key for key in old if old[key] != new[key]]
assert len(changed_methods) == 1 and ' void useBlock(' in changed_methods[0], changed_methods
replacement = {PREFIX+'TerritoryProtection.class', PREFIX+'PublicWaystoneAccess.class'}
def assemble(target, names):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(built) as current, zipfile.ZipFile(target, 'w',compression=zipfile.ZIP_DEFLATED) as after:
        for info in before.infolist():
            after.writestr(info, current.read(info.filename) if info.filename in names else before.read(info.filename))
        for name in sorted(names-set(before.namelist())):
            after.writestr(name,current.read(name))
    return hashlib.sha256(target.read_bytes()).hexdigest()
server = OUT / 'server' / NAME
server_sha = assemble(server,replacement)
with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(server) as after:
    differences={name for name in set(before.namelist())|set(after.namelist()) if name not in before.namelist() or name not in after.namelist() or before.read(name)!=after.read(name)}
assert differences == replacement, differences
probe = OUT / 'probe' / NAME
probe_sha = assemble(probe,replacement|{PREFIX+'TerritoryProtectionProbe.class', PREFIX+'PublicWaystoneProbe.class'})
result={'local_only':True,'installed':False,'baseline_sha256':BASE_SHA,'server':str(server),'server_sha256':server_sha,
        'probe':str(probe),'probe_sha256':probe_sha,'changed_entries':sorted(differences),'changed_protection_methods':changed_methods}
(OUT/'patch.json').write_text(json.dumps(result,indent=2)+'\n','utf-8')
print(json.dumps(result))
