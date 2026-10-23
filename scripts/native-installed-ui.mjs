import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const [pass,directory]=process.argv.slice(2),root=path.resolve(fileURLToPath(new URL('../',import.meta.url)));
assert.ok(['1','2'].includes(pass));assert.equal(path.dirname(directory),path.join(root,'.tools'));assert.match(path.basename(directory),/^installer-acceptance-[a-f0-9]{32}$/);
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let target;for(let i=0;i<50;i++){try{const pages=await(await fetch('http://127.0.0.1:19224/json/list',{signal:AbortSignal.timeout(1000)})).json();target=pages.find(p=>/^https?:\/\/tauri.localhost/.test(p.url));if(target)break;}catch{/* Installed WebView2 is starting. */}await pause(200);}
assert.ok(target);assert.ok(target.webSocketDebuggerUrl.startsWith('ws://127.0.0.1:19224/'));
const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const reply=JSON.parse(data),item=pending.get(reply.id);if(!item)return;pending.delete(reply.id);clearTimeout(item.timer);if(reply.error)item.reject(Error(reply.error.message));else item.resolve(reply.result);});
const call=(method,params)=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(Error(`CDP timeout ${method}`));},10000);pending.set(id,{timer,resolve,reject});socket.send(JSON.stringify({id,method,params}));});
const evaluate=async expression=>{const value=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.equal(value.exceptionDetails,undefined,JSON.stringify(value.exceptionDetails));return value.result.value;};
try{
 for(let i=0;i<100;i++){if(await evaluate(`typeof window.__TAURI_INTERNALS__?.invoke==='function'&&!!document.querySelector('[data-testid="play"]')`))break;await pause(100);}
 const expected=(await readFile(path.join(root,'apps/launcher/src-tauri/Cargo.toml'),'utf8')).match(/^version\s*=\s*"([^"]+)"/m)[1];
 const health=await evaluate(`window.__TAURI_INTERNALS__.invoke('native_health')`);assert.equal(health.version,expected);assert.equal(health.account_environment,'installer-acceptance-loopback-v1');assert.equal(health.play_available,false);
 assert.deepEqual(await evaluate(`window.__TAURI_INTERNALS__.invoke('installer_local_state')`),{status:'ready',environment:'development-local-v1'});
 const installer=await evaluate(`window.__TAURI_INTERNALS__.invoke('installer_snapshot')`);assert.equal(installer.status,'unavailable');assert.equal(installer.plan,null);
 await evaluate(`document.querySelector('a[href="#settings"]').click()`);await pause(300);
 if(pass==='1'){
  await evaluate(`(()=>{const input=document.querySelector('[name="ram_mib"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'4352');input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));})()`);await pause(100);
  await evaluate(`document.querySelector('button[type="submit"]').click()`);
  let saved=false;for(let i=0;i<50;i++){const value=await evaluate(`window.__TAURI_INTERNALS__.invoke('load_launcher_settings')`);if(value.saved&&value.settings.ram_mib===4352){saved=true;break;}await pause(100);}assert.ok(saved,'Installed settings did not save');
 }else{const settings=await evaluate(`window.__TAURI_INTERNALS__.invoke('load_launcher_settings')`);assert.equal(settings.saved,true);assert.equal(settings.settings.ram_mib,4352);}
 assert.ok(await evaluate(`document.querySelector('[data-testid="installer"]').innerText.includes('Сборка для установки пока не опубликована')`));
 const image=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(root,'.tools',`native-installed-settings-${pass}.png`),Buffer.from(image.data,'base64'));
 await evaluate(`document.querySelector('a[href="#home"]').click()`);await pause(150);assert.equal(await evaluate(`document.querySelector('[data-testid="play"]').disabled`),true);
 console.log(`PASS: installed WebView2 run ${pass}, persisted settings/local Ready, PLAY closed`);
}finally{socket.close();}
