import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import {createReadStream,existsSync,mkdirSync,mkdtempSync,lstatSync,readFileSync,readdirSync,renameSync,rmSync,statSync,statfsSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {root,backupRoot,server,pg,portClosed,files} from './friends-backup-lib.mjs';
import {calendarDay,maintenanceDue,performMaintenance,snapshotName,verifySnapshot} from './backup-maintenance-lib.mjs';

assert.equal(process.platform,'linux');assert.equal(root,'/srv/animeoncraft/');assert.equal(process.getuid(),0,'Maintenance runs as root');
process.umask(0o077);
const run=promisify(execFile),control=path.join(root,'.tools/backup-maintenance'),exports=path.join(root,'.tools/backup-exports'),stateFile=path.join(control,'state.json');
const mode=process.argv[2]??'--scheduled';assert.ok(['--scheduled','--initialize','--check','--export-latest'].includes(mode));assert.ok(process.argv.length<=3);
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const command=(file,args,timeout=240000)=>run(file,args,{cwd:root,encoding:'utf8',timeout,maxBuffer:32*1024*1024});
const active=async service=>{try{return (await command('systemctl',['is-active',service],15000)).stdout.trim()==='active';}catch{return false;}};
const atomic=(file,data)=>{const temporary=file+'.'+randomUUID()+'.part';writeFileSync(temporary,JSON.stringify(data,null,2)+'\n',{flag:'wx',mode:0o600});renameSync(temporary,file);};
const digest=async file=>{const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);return hash.digest('hex');};
function privateDirectory(directory){if(existsSync(directory))assert.ok(lstatSync(directory).isDirectory()&&!lstatSync(directory).isSymbolicLink());else mkdirSync(directory,{recursive:true,mode:0o700});}
function latest(){const metadata=JSON.parse(readFileSync(path.join(backupRoot,'latest.json'),'utf8'));return verifySnapshot(metadata.directory,backupRoot);}
function safeRemoveDirectory(directory,parent){assert.equal(path.dirname(path.resolve(directory)),path.resolve(parent));assert.ok(!lstatSync(directory).isSymbolicLink());rmSync(directory,{recursive:true});}

async function archiveSnapshot(snapshot){
  privateDirectory(exports);privateDirectory(control);
  verifySnapshot(snapshot.directory,backupRoot);
  const name=path.basename(snapshot.directory),filename=name+'.tar.gz',destination=path.join(exports,filename);
  if(existsSync(destination)){
    const old=JSON.parse(readFileSync(destination+'.json','utf8'));
    assert.equal(old.filename,filename);assert.equal(await digest(destination),old.sha256);assert.equal(statSync(destination).size,old.bytes);
    atomic(path.join(exports,'latest.json'),old);return old;
  }
  const temporary=path.join(exports,filename+'.'+randomUUID()+'.part');
  writeFileSync(temporary,'',{flag:'wx',mode:0o600});
  await command('tar',['-czf',temporary,'-C',backupRoot,name],600000);
  const listing=(await command('tar',['-tzf',temporary],600000)).stdout.trim().split('\n');
  assert.ok(listing.length>snapshot.manifest.files.length);
  for(const entry of listing)assert.ok(entry.startsWith(name+'/')&&!entry.split('/').includes('..')&&!entry.includes('\\'),'Archive path refused');
  const check=mkdtempSync(path.join(control,'archive-check-'));
  try{
    await command('tar',['-xzf',temporary,'-C',check],600000);
    verifySnapshot(path.join(check,name),check);
  }finally{safeRemoveDirectory(check,control);}
  const report={format_version:1,filename,bytes:statSync(temporary).size,sha256:await digest(temporary),created_at:snapshot.manifest.created_at,world:snapshot.manifest.world,files:snapshot.manifest.files.length};
  renameSync(temporary,destination);atomic(destination+'.json',report);atomic(path.join(exports,'latest.json'),report);
  return report;
}
async function prune(){
  const records=[];
  for(const name of readdirSync(exports)){
    if(!name.endsWith('.tar.gz.json')||!snapshotName.test(name.slice(0,-12)))continue;
    const metadataPath=path.join(exports,name);assert.ok(!lstatSync(metadataPath).isSymbolicLink());
    const report=JSON.parse(readFileSync(metadataPath,'utf8'));
    assert.equal(report.filename+'.json',name);assert.ok(Number.isFinite(Date.parse(report.created_at)));
    const archive=path.join(exports,report.filename);assert.ok(lstatSync(archive).isFile()&&!lstatSync(archive).isSymbolicLink());
    assert.equal(await digest(archive),report.sha256);assert.equal(statSync(archive).size,report.bytes);records.push(report);
  }
  records.sort((a,b)=>b.created_at.localeCompare(a.created_at));
  const pinned=JSON.parse(readFileSync(path.join(exports,'latest.json'),'utf8')).filename;
  for(const old of records.slice(7)){
    if(old.filename===pinned)continue;
    for(const suffix of ['', '.json']){const file=path.join(exports,old.filename+suffix);assert.equal(path.dirname(file),exports);rmSync(file);}
  }
  // Start/stop hooks produce extra raw snapshots. Keep the newest two working
  // directories; seven independently verified compressed restore points remain.
  const snapshots=[],abandoned=[];
  for(const entry of readdirSync(backupRoot,{withFileTypes:true}).filter(e=>snapshotName.test(e.name)&&e.isDirectory())){
    const directory=path.join(backupRoot,entry.name);
    if(existsSync(path.join(directory,'manifest.json')))snapshots.push(entry);
    else if(statSync(directory).mtimeMs<Date.now()-2*86400000)abandoned.push(directory);
  }
  snapshots.sort((a,b)=>b.name.localeCompare(a.name));
  const current=JSON.parse(readFileSync(path.join(backupRoot,'latest.json'),'utf8')).directory;
  for(const entry of snapshots.slice(2)){
    const directory=path.join(backupRoot,entry.name);if(directory===current)continue;
    verifySnapshot(directory,backupRoot);safeRemoveDirectory(directory,backupRoot);
  }
  // Failed start/stop copies must not grow forever. Recent failures remain for
  // diagnosis; only generated, link-free incomplete dirs older than 48h qualify,
  // and this code is reached only after verified export + successful restart.
  for(const directory of abandoned){if(directory===current)continue;files(directory);safeRemoveDirectory(directory,backupRoot);}
}
async function main(){
  if(mode==='--check'){
    const state=existsSync(stateFile)?JSON.parse(readFileSync(stateFile,'utf8')):null;
    console.log(JSON.stringify({api_active:await active('animeoncraft-api'),game_active:await active('animeoncraft-game'),https_timer_active:await active('animeoncraft-certbot.timer'),backup_timer_active:await active('animeoncraft-backup.timer'),schedule:'07:00 Asia/Yekaterinburg; every 2 calendar days',last_day:state?.last_day??null,next_run_due:state?maintenanceDue(state.last_day):null,export_available:existsSync(path.join(exports,'latest.json'))}));return;
  }
  privateDirectory(control);privateDirectory(exports);
  if(mode==='--initialize'){
    if(!existsSync(stateFile))atomic(stateFile,{format_version:1,last_day:calendarDay(),initialized_at:new Date().toISOString()});
    const state=JSON.parse(readFileSync(stateFile,'utf8'));assert.equal(state.format_version,1);maintenanceDue(state.last_day);
    console.log(JSON.stringify({initialized:true,anchor_day:state.last_day,interval_days:2,time:'07:00 Asia/Yekaterinburg',game_restarted:false}));return;
  }
  if(mode==='--export-latest'){
    const report=await archiveSnapshot(latest());console.log(JSON.stringify({exported:true,...report,game_restarted:false}));return;
  }
  const state=JSON.parse(readFileSync(stateFile,'utf8'));assert.equal(state.format_version,1);
  if(!maintenanceDue(state.last_day)){console.log(JSON.stringify({skipped:true,reason:'NOT_DUE',last_day:state.last_day}));return;}
  let stoppedAt;
  const report=await performMaintenance({
    preflight:async()=>{
      assert.ok(await active('animeoncraft-game'),'Game is already stopped; preserve intentional downtime');
      assert.ok(await active('animeoncraft-api'),'API must be available');
      await pg(['pg_isready','-U','anime_migrator','-d','animeoncraft']);
      const previous=latest();const bytes=previous.manifest.files.reduce((sum,item)=>sum+item.bytes,0),disk=statfsSync(backupRoot,{bigint:true});
      assert.ok(disk.bavail*disk.bsize>BigInt(Math.max(3*1024**3,bytes*4)),'Insufficient disk space; game left running');
    },
    stop:async()=>{stoppedAt=Date.now();await command('systemctl',['stop','animeoncraft-game'],240000);await portClosed(25565);},
    snapshot:async()=>{
      let candidate;try{candidate=latest();}catch{/* Failed stop hook: create a new copy below. */}
      if(!candidate||Date.parse(candidate.manifest.created_at)<stoppedAt){
        await command('runuser',['-u','animeoncraft','--','/usr/bin/node',path.join(root,'scripts/backup-friends-server.mjs')],600000);candidate=latest();
      }
      assert.ok(Date.parse(candidate.manifest.created_at)>=stoppedAt);return candidate;
    },
    verify:async snapshot=>{verifySnapshot(snapshot.directory,backupRoot);},
    start:async()=>{
      const started=Date.now();await command('systemctl',['start','animeoncraft-game'],240000);
      const log=path.join(server,'logs/latest.log');
      for(let i=0;i<120;i++){
        if(await active('animeoncraft-game')){
          if(existsSync(log)&&statSync(log).mtimeMs>=started&&/Done \([^\n]*\)! For help/.test(readFileSync(log,'utf8'))){console.log('Minecraft startup Done confirmed');return;}
        }
        await pause(2000);
      }
      throw Error('Minecraft restart not ready; inspect game journal; copies preserved');
    },
    archive:archiveSnapshot,
    record:async archive=>{atomic(stateFile,{format_version:1,last_day:calendarDay(),completed_at:new Date().toISOString(),filename:archive.filename});},
    prune,
  });
  console.log(JSON.stringify({maintenance_complete:true,...report}));
}
main().catch(()=>{console.error('AOC_BACKUP_MAINTENANCE_FAILED: no credentials logged; inspect unit status, disk space and backup manifests. Existing copies preserved unless verified retention already ran.');process.exitCode=1;});
