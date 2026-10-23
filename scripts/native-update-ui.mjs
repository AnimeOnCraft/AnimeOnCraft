import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const [mode,directory]=process.argv.slice(2),root=path.resolve(fileURLToPath(new URL('../',import.meta.url)));
assert.ok(['wrong','update','restart','inspect'].includes(mode));assert.equal(path.dirname(directory),path.join(root,'.tools'));assert.match(path.basename(directory),/^installer-acceptance-[a-f0-9]{32}$/);
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let target;for(let i=0;i<100;i++){try{const pages=await(await fetch('http://127.0.0.1:19225/json/list',{signal:AbortSignal.timeout(1000)})).json();target=pages.find(p=>/^https?:\/\/tauri.localhost/.test(p.url));if(target)break;}catch{/* Startup */}await pause(100);}
assert.ok(target);assert.ok(target.webSocketDebuggerUrl.startsWith('ws://127.0.0.1:19225/'));
const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const reply=JSON.parse(data),item=pending.get(reply.id);if(!item)return;pending.delete(reply.id);clearTimeout(item.timer);if(reply.error)item.reject(Error(reply.error.message));else item.resolve(reply.result);});
const call=(method,params)=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(Error(`CDP timeout ${method}`));},15000);pending.set(id,{timer,resolve,reject});socket.send(JSON.stringify({id,method,params}));});
const evaluate=async expression=>{const value=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.equal(value.exceptionDetails,undefined,JSON.stringify(value.exceptionDetails));return value.result.value;};
const until=async(predicate)=>{const limit=Date.now()+30000;while(Date.now()<limit){const value=await predicate();if(value)return value;await pause(100);}throw Error('Native updater state timed out');};
const click=async label=>{await until(()=>evaluate(`!!Array.from(document.querySelectorAll('[data-testid="launcher-update"] button')).find(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled)`));await evaluate(`Array.from(document.querySelectorAll('[data-testid="launcher-update"] button')).find(b=>b.textContent===${JSON.stringify(label)}).click()`);};
const snapshot=()=>evaluate(`window.__TAURI_INTERNALS__.invoke('launcher_update_snapshot')`);
try{
 await until(()=>evaluate(`typeof window.__TAURI_INTERNALS__?.invoke==='function'&&!!document.querySelector('[data-testid="play"]')`));
 const expected=mode==='restart'?'0.7.19':'0.7.18';
 const health=await evaluate(`window.__TAURI_INTERNALS__.invoke('native_health')`);assert.equal(health.version,expected);assert.equal(health.account_environment,'installer-acceptance-loopback-v1');assert.equal(health.play_available,false);
 assert.equal((await evaluate(`window.__TAURI_INTERNALS__.invoke('installer_local_state')`)).status,'ready');
 await evaluate(`document.querySelector('a[href="#settings"]').click()`);await pause(250);
 for(const input of [{action:'check',url:'http://evil'},{action:'confirm',plan_id:'old'},{action:'check',path:'F:/world'}])assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('launcher_update_action',{input:${JSON.stringify(input)}}).then(()=>false,()=>true)`),true);
 if(mode==='wrong'){
  await evaluate(`(()=>{const input=document.querySelector('[name="ram_mib"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'4352');input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('form button[type="submit"]').click();})()`);
  await until(async()=> (await evaluate(`window.__TAURI_INTERNALS__.invoke('load_launcher_settings')`)).settings.ram_mib===4352);
 }else{const settings=await evaluate(`window.__TAURI_INTERNALS__.invoke('load_launcher_settings')`);assert.equal(settings.saved,true);assert.equal(settings.settings.ram_mib,4352);}
 await click('Проверить обновление лаунчера');
 const ready=await until(async()=>{const state=await snapshot();if(state.status==='failed')throw Error(`Updater preparation failed ${state.reason}`);return mode==='restart'?state.status==='current'&&state:state.status==='awaiting_confirmation'&&state;});
 if(mode==='restart'){
  assert.equal(ready.current_version,'0.7.19');assert.equal(ready.plan,null);
 }else{
  assert.equal(ready.plan.version,mode==='wrong'?'0.7.99':'0.7.19');assert.equal(ready.plan.bytes,(await readFile(path.join(directory,'signed-installer.exe'))).length);
  assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('installer_action',{input:{action:'check'}}).then(()=>false,()=>true)`),true,'pack worker entered during consent');
  assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('launcher_update_action',{input:{action:'confirm',plan_id:'00000000000000000000000000000000'}}).then(()=>false,()=>true)`),true);
  if(mode==='update'){
   const first=ready.plan.plan_id;await click('Отказаться от обновления');await until(async()=> (await snapshot()).status==='idle');
   assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('launcher_update_action',{input:{action:'confirm',plan_id:${JSON.stringify(first)}}}).then(()=>false,()=>true)`),true);
   await click('Проверить обновление лаунчера');await until(async()=> (await snapshot()).status==='awaiting_confirmation');
  }
 }
 if(mode!=='restart')await until(()=>evaluate(`Array.from(document.querySelectorAll('[data-testid="launcher-update"] button')).some(b=>b.textContent==='Установить обновление'&&!b.disabled)`));
 await evaluate(`document.querySelector('[data-testid="launcher-update"]').scrollIntoView({block:'start'})`);await pause(150);
 const image=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(root,'.tools',`native-updater-${mode}.png`),Buffer.from(image.data,'base64'));
 if(mode==='wrong'){
  await click('Установить обновление');const refused=await until(async()=>{const value=await snapshot();return !value.busy&&value.status==='failed'&&value;});assert.equal(refused.reason,'UPDATE_BINARY_IDENTITY');
  assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('installer_snapshot').then(v=>!v.busy&&v.plan===null)`),true);
  await writeFile(path.join(directory,'choice.json'),' {"mode":"good"}');await click('Проверить обновление лаунчера');
  const rollback=await until(async()=>{const value=await snapshot();return !value.busy&&value.status==='failed'&&value;});assert.equal(rollback.reason,'UPDATE_ROLLBACK');
 }else if(mode==='inspect'){
  await click('Отказаться от обновления');await until(async()=> (await snapshot()).status==='idle');
 }else if(mode==='update'){
  await click('Установить обновление'); // Remaining validation happens in the parent after this app exits.
 }
 console.log(`PASS: actual installed updater UI ${mode}, identity/consent/settings/gates.`);
}finally{socket.close();}
