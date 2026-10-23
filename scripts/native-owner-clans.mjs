import assert from 'node:assert/strict';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {once} from 'node:events';
import {mkdirSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
export async function nativeOwnerClans(fixture){
 const profile=path.join(root,'.tools',`game-native-profile-${randomUUID()}`);mkdirSync(profile,{recursive:true});
 const child=spawn(path.join(root,'.tools/native-game-fixture.exe'),[],{cwd:root,windowsHide:true,stdio:'ignore',env:{...process.env,WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:'--remote-debugging-port=19222',WEBVIEW2_USER_DATA_FOLDER:profile}});
 let socket,sequence=0,ownedLogin=false;const pending=new Map();
 const call=(method,params)=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(new Error('Native owner UI timeout'));},15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
 async function evaluate(expression){const reply=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.ok(!reply.exceptionDetails,'Native owner expression failed; details withheld');return reply.result.value;}
 const invoke=(command,input)=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(input??{})})`);
 async function wait(expression){for(let i=0;i<150;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,100));}throw Error('Native owner UI did not settle');}
 try{
  let target;for(let i=0;i<100;i++){try{target=(await(await fetch('http://127.0.0.1:19222/json/list')).json()).find(t=>t.url.includes('tauri.localhost'));if(target)break;}catch{/* Wait for own WebView startup. */}await new Promise(r=>setTimeout(r,150));}assert.ok(target);
  socket=new WebSocket(target.webSocketDebuggerUrl);await once(socket,'open');socket.addEventListener('message',({data})=>{const result=JSON.parse(data),item=pending.get(result.id);if(!item)return;pending.delete(result.id);clearTimeout(item.timer);if(result.error)item.reject(new Error('Native owner CDP failure'));else item.resolve(result.result);});
  assert.equal((await invoke('native_health')).account_environment,'acceptance-loopback-v1');
  assert.equal((await invoke('account_snapshot')).state,'SIGNED_OUT','Unexpected acceptance login preserved');
  const account=await invoke('account_action',{input:{action:'login',login:fixture.login,password:fixture.password,remember:false}});assert.equal(account.state,'SIGNED_IN');assert.equal(account.profile.system_role,'OWNER');ownedLogin=true;
  await evaluate(`location.hash='#profile'`);await wait(`!!document.querySelector('[data-testid="owner-clans"] select option')`);
  async function select(id){await evaluate(`(()=>{const select=document.querySelector('[data-testid="owner-clans"] select');select.value=${JSON.stringify(id)};select.dispatchEvent(new Event('change',{bubbles:true}));})()`);await wait(`!document.querySelector('[data-testid="owner-clan-join"]').disabled`);}
  await select(fixture.join_clan);
  await evaluate(`document.querySelector('[data-testid="owner-clan-join"]').click()`);
  await wait(`document.querySelector('[data-testid="owner-clans"]').innerText.includes('Действие выполнено.')`);
  assert.equal((await invoke('player_read',{input:{resource:'clan'}})).clan.id,fixture.join_clan);
  await select(fixture.review_clan);
  await wait(`Array.from(document.querySelectorAll('[data-testid="owner-clans"] button')).some(b=>b.textContent==='Принять'&&!b.disabled)`);
  await evaluate(`Array.from(document.querySelectorAll('[data-testid="owner-clans"] button')).find(b=>b.textContent==='Принять').click()`);
  await wait(`document.querySelector('[data-testid="owner-clans"]').innerText.includes('На этой странице нет ожидающих заявок.')`);
  assert.equal((await invoke('player_read',{input:{resource:'clan'}})).clan.id,fixture.join_clan);
  await evaluate(`document.querySelector('[data-testid="owner-clans"]').scrollIntoView({block:'start'})`);
  const screenshot=await call('Page.captureScreenshot',{format:'png'});writeFileSync(path.join(root,'.tools/owner-clans-native-profile.png'),Buffer.from(screenshot.data,'base64'));
  const publicClans=await(await fetch('http://127.0.0.1:3000/api/v1/clans')).json();assert.ok(publicClans.items[0]?.id);
  await evaluate(`location.hash='#clans/'+${JSON.stringify(publicClans.items[0].id)}`);await wait(`!!document.querySelector('textarea[name="message"]')`);
  assert.equal(await evaluate(`document.querySelector('textarea[name="message"]').required`),false);assert.equal(await evaluate(`document.querySelector('textarea[name="message"]').minLength`),-1);
  console.log('Native OWNER: real profile UI self-join and approval in another clan; optional about input; isolated account/database: passed');
 }finally{
  try{if(ownedLogin&&socket?.readyState===WebSocket.OPEN)await invoke('account_action',{input:{action:'logout'}});}finally{socket?.close();for(const item of pending.values())clearTimeout(item.timer);if(child.exitCode===null){const exited=once(child,'exit');child.kill();await exited;}await promisify(execFile)('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'scripts/cleanup-native-game-profile.ps1'),'-Profile',profile],{windowsHide:true});}
 }
}
