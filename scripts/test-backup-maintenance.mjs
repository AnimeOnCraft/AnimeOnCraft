import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,symlinkSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {maintenanceDue,calendarDay,performMaintenance,verifySnapshot} from './backup-maintenance-lib.mjs';

test('every two local calendar days, including month/year boundaries and UTC offset',()=>{
  assert.equal(calendarDay(new Date('2026-10-18T21:00:00Z')),'2026-10-19');
  assert.equal(maintenanceDue('2026-10-17',new Date('2026-10-18T02:00:00Z')),false);
  assert.equal(maintenanceDue('2026-10-17',new Date('2026-10-19T02:00:00Z')),true);
  assert.equal(maintenanceDue('2026-12-31',new Date('2027-01-01T02:00:00Z')),false);
  assert.equal(maintenanceDue('2026-12-31',new Date('2027-01-02T02:00:00Z')),true);
  assert.equal(maintenanceDue('2026-10-19',new Date('2026-10-17T02:00:00Z')),false);
});
function operations(fail){
  const calls=[];
  return {calls,ops:Object.fromEntries(['preflight','stop','snapshot','verify','start','archive','record','prune'].map(name=>[name,async()=>{calls.push(name);if(name===fail)throw Error(name+' failed');return 'snapshot';}]))};
}
test('verified stopped copy, restart before archiving, record only after export',async()=>{
  const {calls,ops}=operations();await performMaintenance(ops);
  assert.deepEqual(calls,['preflight','stop','snapshot','verify','start','archive','record','prune']);
});
for(const stage of ['stop','snapshot','verify'])test(stage+' failure still attempts restart and preserves old archives',async()=>{
  const {calls,ops}=operations(stage);await assert.rejects(performMaintenance(ops),new RegExp(stage+' failed'));
  assert.ok(calls.includes('start'));assert.ok(!calls.includes('archive'));assert.ok(!calls.includes('prune'));
});
test('preflight failure never stops Minecraft',async()=>{
  const {calls,ops}=operations('preflight');await assert.rejects(performMaintenance(ops));assert.deepEqual(calls,['preflight']);
});
for(const stage of ['start','archive'])test(stage+' failure never advances schedule or removes copies',async()=>{
  const {calls,ops}=operations(stage);await assert.rejects(performMaintenance(ops));assert.ok(!calls.includes('record'));assert.ok(!calls.includes('prune'));
});
function fixture(){
  const root=mkdtempSync(path.join(tmpdir(),'aoc-backup-fixture-')),dir=path.join(root,'friends-2026-10-17T00-00-00-000Z-1234abcd');mkdirSync(dir);
  const names=['database.dump','modpack-profile.json','private/environment.env','private/friends-game-server.json','server/server.properties','server/world/level.dat'];
  const files=names.map(name=>{const f=path.join(dir,name);mkdirSync(path.dirname(f),{recursive:true});writeFileSync(f,'fixture');return {path:name,bytes:7,sha256:createHash('sha256').update('fixture').digest('hex')};});
  const manifest={format_version:1,complete:true,world:'world',created_at:'2026-10-17T00:00:00Z',files};writeFileSync(path.join(dir,'manifest.json'),JSON.stringify(manifest));return {root,dir,manifest};
}
test('snapshot validation detects corruption, missing inventory and extra untracked files',()=>{
  const f=fixture();try{
    assert.equal(verifySnapshot(f.dir,f.root).manifest.files.length,6);
    writeFileSync(path.join(f.dir,'database.dump'),'changed');assert.throws(()=>verifySnapshot(f.dir,f.root));
    writeFileSync(path.join(f.dir,'database.dump'),'fixture');writeFileSync(path.join(f.dir,'extra-secret'),'x');assert.throws(()=>verifySnapshot(f.dir,f.root));
  }finally{rmSync(f.root,{recursive:true,force:true});}
});
test('snapshot validation refuses incomplete or escaping manifests and missing essential files',()=>{
  const f=fixture();try{
    for(const manifest of [{...f.manifest,complete:false},{...f.manifest,files:[]},{...f.manifest,files:[...f.manifest.files,{path:'../escape',bytes:0,sha256:'0'.repeat(64)}]}]){
      writeFileSync(path.join(f.dir,'manifest.json'),JSON.stringify(manifest));assert.throws(()=>verifySnapshot(f.dir,f.root));
    }
  }finally{rmSync(f.root,{recursive:true,force:true});}
});
test('snapshot validation refuses directory symlinks/junctions',()=>{
  const f=fixture();try{
    const outside=path.join(f.root,'outside');mkdirSync(outside);rmSync(path.join(f.dir,'server'),{recursive:true});symlinkSync(outside,path.join(f.dir,'server'),process.platform==='win32'?'junction':'dir');
    assert.throws(()=>verifySnapshot(f.dir,f.root));
  }finally{rmSync(f.root,{recursive:true,force:true});}
});
