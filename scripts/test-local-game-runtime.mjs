import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {once,EventEmitter} from 'node:events';
import {createWriteStream,readFileSync,writeFileSync,existsSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {loadLocalGameConfig} from './local-game-config.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const worldName=/^level-name=([^\r\n]+)$/m.exec(readFileSync(path.join(root,'.tools/modpack-mvp/server/server.properties'),'utf8'))?.[1];
assert.ok(worldName&&/^[A-Za-z0-9_-]+$/.test(worldName));
const config=loadLocalGameConfig();assert.ok(config);
const prepared=JSON.parse(readFileSync(path.join(root,'.tools/modpack-mvp/local-game-prepared.json'),'utf8'));
const client=JSON.parse(readFileSync(prepared.launcher_config,'utf8'));
assert.equal(client.server_id,config.server_id);assert.equal(client.server_port,25565);
assert.ok(!JSON.stringify(client).includes(config.server_key)&&!Object.hasOwn(client,'server_key'));
const hash=file=>createHash('sha256').update(readFileSync(file)).digest('hex');
for(const side of ['client','server'])assert.equal(hash(path.join(root,'.tools/modpack-mvp',side,'mods/animeoncraft-admission-0.3.0-friends-dev.jar')),prepared.admission_sha256);
const base='http://127.0.0.1:3000/api/v1/';
const get=await fetch(base+'auth/me',{headers:{'x-aoc-client':'launcher'},signal:AbortSignal.timeout(5000)});assert.equal(get.status,401);await get.body?.cancel();
const body=JSON.stringify({ticket:'gt_'+'z'.repeat(43),game_name:'aocgameprobe',minecraft_uuid:'34237c32-b733-39aa-8838-90e69387170c',connection_id:randomUUID()});
for(const [key,expected] of [['0'.repeat(64),401],[config.server_key,403]]){
 const response=await fetch(base+'game/admission/redeem',{method:'POST',headers:{authorization:`Bearer ${key}`,'content-type':'application/json'},body,signal:AbortSignal.timeout(5000)});
 assert.equal(response.status,expected,'Actual host API must load the local server credential and reject an unissued ticket');await response.body?.cancel();
}
await new Promise((resolve,reject)=>{const socket=net.connect(25565,'127.0.0.1');socket.once('connect',()=>{socket.destroy();reject(new Error('Existing server preserved: port 25565 occupied'));});socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(new Error('Port check failed')));});
async function run(label){
 const output=createWriteStream(path.join(root,`.tools/local-game-server-${label}.log`));
 const process=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'scripts/start-modpack-server.ps1')],{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});
 const exit=once(process,'exit');const events=new EventEmitter();let text='',exited=false;
 for(const stream of [process.stdout,process.stderr])stream.on('data',bytes=>{const chunk=bytes.toString().replaceAll(config.server_key,'[REDACTED]');text+=chunk;output.write(chunk);events.emit('output');});
 process.once('exit',()=>{exited=true;events.emit('output');});
 const wait=(pattern,start=0)=>new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>finish(new Error(`Local server ${label} timeout; see own server log`)),180000);
  function finish(error){clearTimeout(timer);events.off('output',check);if(error)reject(error);else resolve();}
  function check(){if(pattern.test(text.slice(start)))finish();else if(exited)finish(new Error(`Local server ${label} exited early; see own server log`));}
  events.on('output',check);check();
 });
 try{
  await wait(/Done \([^\n]*\)! For help/);
  const offset=text.length;process.stdin.write('list\nsave-all flush\n');await wait(/Saved the game/,offset);
  process.stdin.write('stop\n');const [code]=await exit;assert.equal(code,0);
  assert.ok(existsSync(path.join(root,'.tools/modpack-mvp/server',worldName,'level.dat')));
  console.log(`Local account server: ${label}, existing world loaded/saved, clean shutdown`);
 }finally{
  if(!exited){process.stdin.write('stop\n');const timer=setTimeout(()=>process.kill(),30000);await exit;clearTimeout(timer);}
  output.end();
 }
}
await run('first');await run('restart');
writeFileSync(path.join(root,'.tools/local-game-runtime-result.json'),JSON.stringify({passed:true,server_id:config.server_id,loopback_only:true,account_api_enabled:true,server_credential_verified:true,invalid_ticket_rejected:true,prepared_runtime_has_no_server_secret:true,world_preserved:true,server_restarted:true,human_account_join_tested:false,checked_at:new Date().toISOString()},null,2)+'\n');
