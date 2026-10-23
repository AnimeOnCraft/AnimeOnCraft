// Run only in a disposable Node Linux container, with scripts mounted at /input.
import assert from 'node:assert/strict';
import {execFileSync,spawnSync} from 'node:child_process';
import {existsSync,mkdirSync,writeFileSync,readFileSync,copyFileSync,readdirSync,symlinkSync,utimesSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
assert.equal(process.env.AOC_BACKUP_FIXTURE,'1');assert.equal(process.platform,'linux');assert.equal(process.getuid(),0);
const root='/srv/animeoncraft';assert.equal(existsSync(root),false,'Disposable container only; never run on production');
for(const directory of ['scripts','.tools/backups','.tools/modpack-mvp/server/logs'])mkdirSync(path.join(root,directory),{recursive:true});
for(const name of ['scheduled-friends-backup.mjs','backup-maintenance-lib.mjs','friends-backup-lib.mjs'])copyFileSync('/input/'+name,path.join(root,'scripts',name));
if(!existsSync('/usr/bin/node'))symlinkSync(process.execPath,'/usr/bin/node');
mkdirSync('/fixture-bin');
const creator=`
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const parent='${root}/.tools/backups';const at=new Date().toISOString();const name='friends-'+at.replaceAll(/[:.]/g,'-')+'-'+crypto.randomBytes(4).toString('hex');const dir=path.join(parent,name);fs.mkdirSync(dir);
const files=['database.dump','modpack-profile.json','private/environment.env','private/friends-game-server.json','server/server.properties','server/world/level.dat'].map(name=>{const f=path.join(dir,name);fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,'fixture');return {path:name,bytes:7,sha256:crypto.createHash('sha256').update('fixture').digest('hex')};});
if(fs.existsSync('/fail-corrupt-stop'))files[0].sha256='0'.repeat(64);
fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({format_version:1,complete:true,world:'world',created_at:at,files}));fs.writeFileSync(path.join(parent,'latest.json'),JSON.stringify({directory:dir,created_at:at}));
`;
writeFileSync('/create-snapshot.cjs',creator);execFileSync(process.execPath,['/create-snapshot.cjs']);
writeFileSync('/fixture-bin/systemctl',`#!/usr/bin/env node
const fs=require('fs'),cp=require('child_process');const [action,unit]=process.argv.slice(2);
if(action==='is-active'){if(unit==='animeoncraft-game'&&fs.existsSync('/game-stopped'))process.exit(3);console.log('active');}
else if(action==='stop'){fs.appendFileSync('/events','stop\\n');fs.writeFileSync('/game-stopped','');cp.execFileSync(process.execPath,['/create-snapshot.cjs']);}
else if(action==='start'){fs.appendFileSync('/events','start\\n');fs.rmSync('/game-stopped',{force:true});fs.writeFileSync('${root}/.tools/modpack-mvp/server/logs/latest.log','Done (1.0s)! For help, type help');}
else process.exit(9);
`,{mode:0o755});
writeFileSync('/fixture-bin/docker','#!/bin/sh\necho "accepting connections"\n',{mode:0o755});
writeFileSync('/fixture-bin/runuser','#!/bin/sh\nexit 1\n',{mode:0o755});
const env={...process.env,PATH:'/fixture-bin:'+process.env.PATH};
const run=mode=>spawnSync(process.execPath,[path.join(root,'scripts/scheduled-friends-backup.mjs'),mode],{env,encoding:'utf8',timeout:60000});
assert.equal(run('--initialize').status,0);
assert.equal(existsSync('/events'),false);
assert.equal(run('--scheduled').status,0);assert.equal(existsSync('/events'),false);
const day=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Yekaterinburg',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const oldDay=new Date(Date.parse(day+'T00:00:00Z')-2*86400000).toISOString().slice(0,10);
const state=path.join(root,'.tools/backup-maintenance/state.json');
const arm=()=>writeFileSync(state,JSON.stringify({format_version:1,last_day:oldDay}));
const abandoned=path.join(root,'.tools/backups/friends-2026-01-01T00-00-00-000Z-1234abcd');mkdirSync(abandoned);writeFileSync(path.join(abandoned,'database.dump'),'incomplete');utimesSync(abandoned,new Date(0),new Date(0));
for(let i=0;i<8;i++){arm();const r=run('--scheduled');assert.equal(r.status,0,r.stderr+r.stdout);}
assert.equal(existsSync(abandoned),false,'Old incomplete snapshot removed only after a successful verified export/restart');
const exports=path.join(root,'.tools/backup-exports');
const archives=()=>readdirSync(exports).filter(x=>x.endsWith('.tar.gz'));
assert.equal(archives().length,7);
assert.equal(readdirSync(path.join(root,'.tools/backups')).filter(x=>x.startsWith('friends-')).length,2);
const report=JSON.parse(readFileSync(path.join(exports,'latest.json')));
const bytes=readFileSync(path.join(exports,report.filename));assert.equal(createHash('sha256').update(bytes).digest('hex'),report.sha256);
assert.equal(bytes.length,report.bytes);assert.equal((await import('./backup-maintenance-lib.mjs')).calendarDay(new Date()),day);
const before=archives();arm();writeFileSync('/fail-corrupt-stop','');
assert.notEqual(run('--scheduled').status,0);assert.equal(existsSync('/game-stopped'),false);assert.deepEqual(archives(),before);
assert.equal(JSON.parse(readFileSync(state)).last_day,oldDay);
const eventLines=readFileSync('/events','utf8').trim().split(/\r?\n/).filter(Boolean);assert.equal(eventLines.at(-1),'start');
const result={passed:true,isolated_linux:true,real_tar_roundtrip:true,eight_cycles:true,retained_archives:7,retained_raw_snapshots:2,old_incomplete_snapshot_pruned_after_success:true,corrupt_snapshot_restarts_game:true,failed_copy_preserves_archives_and_schedule:true,initialize_and_not_due_do_not_stop_game:true,checked_at:new Date().toISOString()};
writeFileSync('/evidence/backup-maintenance-linux-result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
