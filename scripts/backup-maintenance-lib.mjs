import assert from 'node:assert/strict';
import {lstatSync,readdirSync,readFileSync,realpathSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';

export const snapshotName=/^friends-[0-9TZ_-]+-[a-f0-9]{8}$/;
export function calendarDay(now=new Date()){
  const parts=Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Yekaterinburg',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(now).map(p=>[p.type,p.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}
function dayNumber(day){
  assert.match(day,/^\d{4}-\d{2}-\d{2}$/);const value=Date.parse(day+'T00:00:00Z');
  assert.equal(new Date(value).toISOString().slice(0,10),day);return value/86400000;
}
export function maintenanceDue(previousDay,now=new Date()){return dayNumber(calendarDay(now))-dayNumber(previousDay)>=2;}

// No cleanup/record on a failed snapshot or failed restart. Always try to return
// an initially running game to service, including when systemctl stop times out.
export async function performMaintenance(ops){
  await ops.preflight();let snapshot;
  try{await ops.stop();snapshot=await ops.snapshot();await ops.verify(snapshot);}
  finally{await ops.start();}
  const archive=await ops.archive(snapshot);
  await ops.record(archive);
  await ops.prune(archive);
  return archive;
}
export function verifySnapshot(input,parent){
  assert.ok(!lstatSync(parent).isSymbolicLink());assert.ok(!lstatSync(input).isSymbolicLink());
  const directory=realpathSync(input);assert.equal(path.dirname(directory),realpathSync(parent));assert.match(path.basename(directory),snapshotName);
  const actual=[];
  function walk(relative=''){
    for(const entry of readdirSync(path.join(directory,relative),{withFileTypes:true})){
      const name=relative?`${relative}/${entry.name}`:entry.name;
      assert.ok(!entry.isSymbolicLink(),'Backup symlink refused');
      if(entry.isDirectory())walk(name);else {assert.ok(entry.isFile());actual.push(name);}
    }
  }
  walk();
  const manifest=JSON.parse(readFileSync(path.join(directory,'manifest.json'),'utf8'));
  assert.equal(manifest.format_version,1);assert.equal(manifest.complete,true);assert.match(manifest.world,/^[A-Za-z0-9_-]{1,80}$/);
  assert.ok(Number.isFinite(Date.parse(manifest.created_at)));assert.ok(Array.isArray(manifest.files));
  const inventory=new Set();
  for(const file of manifest.files){
    assert.equal(typeof file.path,'string');assert.ok(!file.path.includes('\\')&&!path.posix.isAbsolute(file.path));
    assert.equal(path.posix.normalize(file.path),file.path);assert.ok(!file.path.split('/').includes('..'));
    assert.ok(!inventory.has(file.path));inventory.add(file.path);
    const full=path.resolve(directory,file.path);assert.ok(full.startsWith(directory+path.sep));assert.ok(lstatSync(full).isFile());
    const bytes=readFileSync(full);assert.equal(bytes.length,file.bytes);assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256,'Backup checksum mismatch');
  }
  for(const required of ['database.dump','modpack-profile.json','private/environment.env','private/friends-game-server.json','server/server.properties',`server/${manifest.world}/level.dat`])assert.ok(inventory.has(required),'Incomplete backup inventory');
  assert.deepEqual(actual.filter(p=>p!=='manifest.json').sort(),[...inventory].sort());
  return {directory,manifest};
}
