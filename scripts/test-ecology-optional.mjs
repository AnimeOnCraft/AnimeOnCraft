import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {once} from 'node:events';
import {copyFileSync,cpSync,mkdirSync,readdirSync,readFileSync,symlinkSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';

const root=path.resolve(import.meta.dirname,'..'),work=path.join(root,'.tools/ecology-20261025');
const passed=JSON.parse(readFileSync(path.join(work,'result.json'),'utf8'));assert.equal(passed.passed,true);
const previous=path.resolve(passed.fixture_directory);assert.ok(previous.startsWith(path.join(root,'.tools')+path.sep));
await new Promise((resolve,reject)=>{
 const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
 socket.once('connect',()=>{socket.destroy();reject(Error('Fixture port occupied; preserve existing process'));});
 socket.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));socket.once('timeout',()=>{socket.destroy();reject(Error('Port check timed out'));});
});
const directory=path.join(root,`.tools/ecology-optional-${Date.now()}`);mkdirSync(path.join(directory,'mods'),{recursive:true});
symlinkSync(path.join(previous,'libraries'),path.join(directory,'libraries'),'junction');
cpSync(path.join(previous,'config'),path.join(directory,'config'),{recursive:true});
for(const file of ['server.properties','eula.txt'])copyFileSync(path.join(previous,file),path.join(directory,file));
for(const file of readdirSync(path.join(previous,'mods')))
 if(file.endsWith('.jar')&&!file.startsWith('animeoncraft-')&&!file.startsWith('cleanmaid-')&&!file.startsWith('incontrol-'))
  copyFileSync(path.join(previous,'mods',file),path.join(directory,'mods',file));
const patch=JSON.parse(readFileSync(path.join(work,'patch.json'),'utf8')),jar=readFileSync(patch.server.path);
assert.equal(createHash('sha256').update(jar).digest('hex'),patch.server.sha256);
writeFileSync(path.join(directory,'mods',path.basename(patch.server.path)),jar);
const child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),['-Xms256m','-Xmx1536m','-Dterminal.jline=false','-Dterminal.ansi=false','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{
 cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'}});
let output='',ready=false,forced;
for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{
 output+=bytes.toString();
 if(!ready&&output.includes('AOC_ECOLOGY_READY')&&output.includes('For help, type "help"')){
  ready=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),45000);
 }
});
const timeout=setTimeout(()=>{child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),30000);},180000);
try{
 const [code]=await once(child,'exit');assert.equal(code,0);assert.equal(ready,true);
 const result={passed:true,directory,server_sha256:patch.server.sha256,cleanmaid_installed:false,incontrol_installed:false,optional_mixin_safe:true,checked_at:new Date().toISOString()};
 writeFileSync(path.join(work,'optional-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);clearTimeout(forced);writeFileSync(path.join(work,'optional-probe.log'),output);}
