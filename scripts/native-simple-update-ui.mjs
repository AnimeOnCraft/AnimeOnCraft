import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import path from 'node:path';
const directory=path.resolve(process.argv[2]),mode=process.argv[3];
assert.match(path.basename(directory),/^installer-acceptance-[a-f0-9]{32}$/);
let target;
for(let i=0;i<120;i++){try{target=(await(await fetch('http://127.0.0.1:19226/json/list')).json()).find(p=>p.url.includes('tauri.localhost'));if(target)break;}catch{/* own startup */}await new Promise(r=>setTimeout(r,100));}
assert.ok(target);assert.ok(target.webSocketDebuggerUrl.startsWith('ws://127.0.0.1:19226/'));
const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const message=JSON.parse(data),item=pending.get(message.id);if(!item)return;pending.delete(message.id);clearTimeout(item.timer);if(message.error)item.reject(new Error('CDP failed'));else item.resolve(message.result);});
function call(method,params){return new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>reject(new Error('UI timed out')),15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const value=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.equal(value.exceptionDetails,undefined);return value.result.value;}
async function wait(condition){for(let i=0;i<150;i++){const value=await condition();if(value)return value;await new Promise(r=>setTimeout(r,100));}throw new Error('Updater state did not settle');}
const invoke=(command,args={})=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`);
const snapshot=()=>invoke('simple_update_snapshot');
const status=value=>wait(async()=>(await snapshot()).status===value);
async function click(label){await wait(()=>evaluate(`Array.from(document.querySelectorAll('[data-testid="simple-update"] button')).some(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled)`));return evaluate(`Array.from(document.querySelectorAll('[data-testid="simple-update"] button')).find(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled).click()`);}
async function check(mode){await writeFile(path.join(directory,'mode.txt'),mode);await click('Проверить обновления');await status('available');}
try{
 await call('Emulation.setDeviceMetricsOverride',{width:1180,height:760,deviceScaleFactor:1,mobile:false});
 await wait(()=>evaluate(`!!window.__TAURI_INTERNALS__&&!!document.querySelector('[data-testid="play"]')`));
 await evaluate(`location.hash='#settings'`);await wait(()=>evaluate(`!!document.querySelector('[data-testid="simple-update"]')`));
 const health=await invoke('native_health');assert.equal(health.play_available,false);
 if(mode==='restart'){
  assert.equal(health.version,'0.7.22');await status('current');await wait(()=>evaluate(`document.body.innerText.includes('Установлена последняя опубликованная версия')`));
  const settings=await invoke('load_launcher_settings');assert.equal(settings.settings.ram_mib,4352);assert.equal(settings.saved,true);
  const screen=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(directory,'after.png'),Buffer.from(screen.data,'base64'));
  console.log('PASS: updated app automatically restarted; current version and persisted settings confirmed.');
 }else{
  assert.equal(health.version,'0.7.21');await status('available');assert.equal((await snapshot()).installed,true);
  const settings=await invoke('load_launcher_settings');await invoke('save_launcher_settings',{input:{...settings.settings,ram_mib:4352}});
  for(const input of [{action:'check',url:'https://evil.test'},{action:'install',version:'0.7.22',path:'F:/foreign.exe'},{action:'install',version:'0.7.99'}])assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('simple_update_action',{input:${JSON.stringify(input)}}).then(()=>false,()=>true)`),true);
  await click('Обновить лаунчер');await status('error');assert.equal((await snapshot()).reason,'DOWNLOAD_FAILED');
  await check('too-large');await click('Обновить лаунчер');await status('error');assert.equal((await snapshot()).reason,'TOO_LARGE');
  await check('wrong-version');await click('Обновить лаунчер');await status('error');assert.equal((await snapshot()).reason,'DOWNLOAD_FAILED');
  await check('slow');await click('Обновить лаунчер');await wait(async()=>(await snapshot()).downloaded>0);
  await wait(()=>evaluate(`!!document.querySelector('progress')`));await click('Отменить загрузку');await status('available');assert.equal((await snapshot()).reason,'CANCELLED');
  await check('good');await wait(()=>evaluate(`document.body.innerText.includes('Доступна версия 0.7.22')`));const screen=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(directory,'before.png'),Buffer.from(screen.data,'base64'));
  await writeFile(path.join(directory,'refusals-passed.json'),JSON.stringify({passed:true,tamper:true,signed_version:true,size_limit:true,cancellation:true,renderer_paths_rejected:true}));
  console.log('PASS: actual TLS/signed-version/tamper refusal and download progress/cancellation; installing confirmed update.');
  await click('Обновить лаунчер');
 }
}finally{socket.close();}
