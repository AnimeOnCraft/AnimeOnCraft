import assert from 'node:assert/strict';
import {readFile,writeFile,access} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const [mode,directory]=process.argv.slice(2);
assert.ok(['first','update','repair','restart','foreign'].includes(mode));
const root=path.resolve(fileURLToPath(new URL('../',import.meta.url)));
assert.equal(path.dirname(directory),path.join(root,'.tools'));assert.match(path.basename(directory),/^pack-acceptance-[a-f0-9]{32}$/);
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let page;for(let i=0;i<40;i++){try{const pages=await(await fetch('http://127.0.0.1:19223/json/list',{signal:AbortSignal.timeout(1000)})).json();page=pages.find(p=>/^https?:\/\/tauri.localhost/.test(p.url));if(page)break;}catch{/* WebView2 is still starting. */}await pause(500);}
assert.ok(page,'Pack WebView2 did not start');assert.ok(page.webSocketDebuggerUrl.startsWith('ws://127.0.0.1:19223/'));
const socket=new WebSocket(page.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const message=JSON.parse(data),item=pending.get(message.id);if(!item)return;clearTimeout(item.timer);pending.delete(message.id);if(message.error)item.reject(Error(message.error.message));else item.resolve(message.result);});
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;const timer=setTimeout(()=>{pending.delete(id);reject(Error(`CDP timeout ${method}`));},10000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
const evaluate=async expression=>{const reply=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.equal(reply.exceptionDetails,undefined,JSON.stringify(reply.exceptionDetails));return reply.result.value;};
const snapshot=()=>evaluate(`window.__TAURI_INTERNALS__.invoke('installer_snapshot')`);
async function waitStatus(status){for(let i=0;i<140;i++){const value=await snapshot();if(!value.busy&&value.status===status)return value;if(!value.busy&&['blocked','recovery_required'].includes(value.status)&&value.status!==status)throw Error(`Unexpected installer state ${JSON.stringify(value)}`);await pause(100);}throw Error(`Installer never reached ${status}: ${JSON.stringify(await snapshot())}`);}
async function waitButton(label){for(let i=0;i<100;i++){if(await evaluate(`!!Array.from(document.querySelectorAll('[data-testid="installer"] button')).find(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled)`))return;await pause(100);}throw Error(`Missing enabled button ${label}`);}
async function click(label){await waitButton(label);await evaluate(`Array.from(document.querySelectorAll('[data-testid="installer"] button')).find(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled).click()`);await pause(100);}
async function serverMode(value){const reply=await fetch('http://127.0.0.1:39442/mode',{method:'POST',headers:{authorization:`Bearer ${process.env.AOC_PACK_CONTROL_TOKEN}`,'content-type':'application/json'},body:JSON.stringify({mode:value}),signal:AbortSignal.timeout(1000)});assert.equal(reply.status,204);}
async function waitSlowMetadata(){for(let i=0;i<120;i++){const reply=await fetch('http://127.0.0.1:39442/state',{method:'POST',headers:{authorization:`Bearer ${process.env.AOC_PACK_CONTROL_TOKEN}`},signal:AbortSignal.timeout(1000)});assert.equal(reply.status,200);if((await reply.json()).slowStarted)return;await pause(100);}throw Error('Metadata socket never reached the suspended snapshot');}
async function capture(name){await evaluate(`document.querySelector('[data-testid="installer"]').scrollIntoView({block:'start'});Promise.all([document.fonts.ready,...Array.from(document.images).map(i=>i.decode().catch(()=>{}))])`);const result=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(root,'.tools',`native-pack-${name}.png`),Buffer.from(result.data,'base64'));}
const game=path.join(directory,'local','AnimeOnCraft.InstallEngineDevelopment.v1','install','game-instance','mods','fixture.jar');
try{
 for(let i=0;i<100;i++){if(await evaluate(`typeof window.__TAURI_INTERNALS__?.invoke==='function'&&!!document.querySelector('[data-testid="play"]')`))break;await pause(100);}
 const health=await evaluate(`window.__TAURI_INTERNALS__.invoke('native_health')`);assert.equal(health.account_environment,'pack-acceptance-loopback-v1');assert.equal(health.play_available,false);
 assert.equal(health.version,(await readFile(path.join(root,'apps/launcher/src-tauri/Cargo.toml'),'utf8')).match(/^version\s*=\s*"([^"]+)"/m)[1]);
 assert.deepEqual(await evaluate(`window.__TAURI_INTERNALS__.invoke('installer_local_state')`),{status:'ready',environment:'development-local-v1'});
 await evaluate(`document.querySelector('a[href="#settings"]').click()`);await pause(200);
 if(mode==='foreign'){
  await waitStatus('blocked');assert.ok(await evaluate(`Array.from(document.querySelectorAll('[data-testid="installer"] button')).filter(b=>b.textContent!=='Обновить состояние установщика').every(b=>b.disabled)`));await capture('foreign-refused');
 }else if(mode==='restart'){
  assert.equal((await snapshot()).status,'idle');assert.equal((await snapshot()).plan,null);await capture('restart');
 }else{
  if(mode==='first'){
   await serverMode('slow');await click('Проверить сборку');await waitSlowMetadata();await click('Отменить операцию');const cancelled=await waitStatus('repository_unavailable');assert.equal(cancelled.plan,null);
   await serverMode('tamper');await click('Проверить сборку');const refused=await waitStatus('repository_unavailable');assert.equal(refused.plan,null);
   await assert.rejects(access(game));await waitButton('Проверить сборку');await capture('repository-refused');
   assert.ok(await evaluate(`Array.from(document.querySelectorAll('[data-testid="installer"] button')).filter(b=>['Установить сборку','Исправить файлы','Восстановить операцию'].includes(b.textContent)).every(b=>b.disabled)`));
   await serverMode('good');await click('Проверить сборку');await waitStatus('completed');
  }
  const before=mode==='first'?null:await readFile(game);
  await click(mode==='repair'?'Исправить файлы':'Установить сборку');const plan=await waitStatus('awaiting_confirmation');assert.equal(plan.plan.summary[mode==='first'?'install':'replace'],1);assert.equal(plan.plan.summary.reuse,0);
  if(before)assert.deepEqual(await readFile(game),before);else await assert.rejects(access(game));
  await waitButton('Подтвердить изменения');await capture(`${mode}-plan`);await click('Подтвердить изменения');await waitStatus('completed');await waitButton('Проверить сборку');await capture(`${mode}-completed`);
  assert.ok(await evaluate(`document.querySelector('[data-testid="installer"]').innerText.includes('Операция завершена')`));
 }
 const refused=await evaluate(`(async()=>{const out=[];for(const input of [{action:'install',url:'https://evil.invalid/'},{action:'check',path:'F:/world'},{action:'confirm',plan_id:'../world'},{action:'launch'}]){try{await window.__TAURI_INTERNALS__.invoke('installer_action',{input});out.push(false);}catch{out.push(true);}}return out;})()`);assert.ok(refused.every(Boolean));
 await evaluate(`document.querySelector('a[href="#home"]').click()`);await pause(150);assert.equal(await evaluate(`document.querySelector('[data-testid="play"]').disabled`),true);
 console.log(`PASS: packaged pack GUI ${mode}; no PLAY authority`);
}finally{socket.close();}
