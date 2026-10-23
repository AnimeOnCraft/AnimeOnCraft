import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import {createServer} from 'node:https';import {spawn} from 'node:child_process';
const directory=path.resolve(process.argv[2]);assert.match(path.basename(directory),/^installer-acceptance-[a-f0-9]{32}$/);assert.equal(path.dirname(directory),path.join(process.cwd(),'.tools'));
let mode='current',socket,child,log='';const requests=[],pending=new Map();let sequence=0;
const server=createServer({cert:fs.readFileSync(path.join(directory,'cert.pem')),key:fs.readFileSync(path.join(directory,'key.pem'))},(req,res)=>{
 assert.equal(req.url,'/aoc/latest.json');assert.equal(req.headers.authorization,undefined);requests.push(Date.now());
 if(mode==='offline'){res.writeHead(503).end();return;}
 const version=mode==='current'?'0.8.7':'0.8.8';res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'}).end(JSON.stringify({version,platforms:{'windows-x86_64-nsis':{url:`https://localhost:39447/aoc/AnimeOnCraft_${version}_x64-setup.exe`,signature:'eA=='.repeat(30)}}}));
});
async function wait(check,timeout=20000){const end=Date.now()+timeout;for(;;){if(await check())return;assert.ok(Date.now()<end,'Native poll test timed out; '+directory);await new Promise(r=>setTimeout(r,100));}}
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.equal(r.exceptionDetails,undefined);return r.result.value;};
const invoke=(command,args={})=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`);
try{
 await new Promise(r=>server.listen(39447,'127.0.0.1',r));
 child=spawn(path.join(directory,'install/animeoncraft-launcher.exe'),[],{cwd:process.cwd(),windowsHide:true,env:{...process.env,AOC_INSTALLER_ACCEPTANCE_DIRECTORY:directory,WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:'--remote-debugging-port=19226',WEBVIEW2_USER_DATA_FOLDER:path.join(directory,'webview')},stdio:['ignore','pipe','pipe']});for(const s of [child.stdout,child.stderr])s.on('data',b=>log+=b.toString());
 let target;await wait(async()=>{try{target=(await(await fetch('http://127.0.0.1:19226/json/list')).json()).find(t=>t.url.includes('tauri.localhost'));return !!target;}catch{return false;}});
 socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise(r=>socket.addEventListener('open',r,{once:true}));socket.addEventListener('message',e=>{const value=JSON.parse(e.data),p=pending.get(value.id);if(p){pending.delete(value.id);if(value.error)p.reject(Error('CDP failure'));else p.resolve(value.result);}});
 await wait(()=>evaluate('!!window.__TAURI_INTERNALS__&&!!document.querySelector("[data-testid=play]")'));
 await wait(async()=>(await invoke('simple_update_snapshot')).status==='current');
 await wait(()=>requests.length>=2);assert.ok(requests[1]-requests[0]>=4000&&requests[1]-requests[0]<=7000,'Five-second native schedule');
 mode='available';await wait(async()=>(await invoke('simple_update_snapshot')).status==='available',9000);
 await wait(()=>evaluate('document.querySelector("[data-testid=launch-control]")?.dataset.action==="install-update"'));
 const blocked=await evaluate('window.__TAURI_INTERNALS__.invoke("game_launch").then(()=>"UNEXPECTED",e=>String(e))');assert.equal(blocked,'LAUNCHER_UPDATE_REQUIRED');
 mode='offline';await invoke('simple_update_action',{input:{action:'check'}});await wait(async()=>(await invoke('simple_update_snapshot')).status==='error');
 assert.equal((await invoke('simple_update_snapshot')).latest_version,'0.8.8');assert.equal(await evaluate('window.__TAURI_INTERNALS__.invoke("game_launch").then(()=>"UNEXPECTED",e=>String(e))'),'LAUNCHER_UPDATE_REQUIRED');
 const result={passed:true,checked_at:new Date().toISOString(),directory,real_native_app:true,hidden_window:true,real_https:true,automatic_check_seconds:5,request_intervals_ms:requests.slice(1).map((v,i)=>v-requests[i]),mandatory_update_native_guard:true,known_update_preserved_on_outage:true,frontend_refreshed_without_click:true,production_untouched:true};fs.writeFileSync('.tools/update-polling-result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{socket?.close();if(child&&child.exitCode===null){child.kill();await new Promise(r=>child.once('exit',r));}server.closeAllConnections();await new Promise(r=>server.close(r));fs.writeFileSync(path.join(directory,'app.log'),log);}
