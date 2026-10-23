import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
const fixture=JSON.parse(await readFile(process.argv[2],'utf8')),phase=process.argv[3];
assert.ok(['flow','restore','temporary'].includes(phase));
let target;
for(let i=0;i<60;i++){try{target=(await(await fetch('http://127.0.0.1:19222/json/list')).json()).find(p=>p.url.includes('tauri.localhost'));if(target)break;}catch{/* own startup */}await new Promise(r=>setTimeout(r,200));}
assert.ok(target);const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const message=JSON.parse(data),item=pending.get(message.id);if(!item)return;pending.delete(message.id);clearTimeout(item.timer);if(message.error)item.reject(new Error('CDP request failed'));else item.resolve(message.result);});
function call(method,params){return new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>reject(new Error('Native probe timed out')),20000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const reply=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(reply.exceptionDetails)throw new Error('Native expression failed (details withheld)');return reply.result.value;}
async function invoke(command,args={}){const reply=await evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)}).then(value=>({ok:true,value}),()=>({ok:false}))`);assert.equal(reply.ok,true,'Native IPC failed (details withheld)');assert.equal(/(?:aa|ar|ac)_[A-Za-z0-9_-]{43}/.test(JSON.stringify(reply.value)),false);return reply.value;}
async function wait(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,100));}throw new Error('Native UI condition failed');}
async function button(label){await wait(`Array.from(document.querySelectorAll('button')).some(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled)`);await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent===${JSON.stringify(label)}&&!b.disabled).click()`);}
async function login(password){await wait(`!!document.querySelector('input[name="login"]')&&!document.querySelector('input[name="login"]').matches(':disabled')`);await evaluate(`(()=>{const f=document.querySelector('input[name="login"]').form;f.elements.login.value=${JSON.stringify(fixture.login)};f.elements.password.value=${JSON.stringify(password)};f.elements.remember.checked=true;f.requestSubmit();})()`);await wait(`document.body.innerText.includes('Почта для ручного восстановления')`);}
try{
 await wait(`typeof window.__TAURI_INTERNALS__?.invoke==='function'&&!!document.querySelector('[data-testid="play"]')`);
 const health=await invoke('native_health');assert.equal(health.account_environment,'acceptance-loopback-v1');assert.equal(health.version,'0.7.21');
 await evaluate(`location.hash='#profile'`);let start=await invoke('account_snapshot');
 for(let i=0;i<60&&['RESTORING','REFRESHING'].includes(start.state);i++){await new Promise(r=>setTimeout(r,100));start=await invoke('account_snapshot');}
 if(phase==='flow'){
  assert.equal(start.state,'SIGNED_OUT');await wait(`!!document.querySelector('input[name="login"]')`);await button('Регистрация');
  await wait(`!!document.querySelector('input[name="email"]')`);
  await evaluate(`(()=>{const f=document.querySelector('input[name="email"]').form;f.elements.login.value=${JSON.stringify(fixture.login)};f.elements.email.value=${JSON.stringify(fixture.email)};f.elements.password.value=${JSON.stringify(fixture.password)};f.requestSubmit();})()`);
  await wait(`document.body.innerText.includes('Теперь войдите с паролем')`);await button('Вход');await login(fixture.password);
  const account=await invoke('account_snapshot');assert.equal(account.profile.login,fixture.login);assert.equal(account.profile.email_verified,false);assert.equal(account.remembered,true);
  await wait(`!!document.querySelector('input[name="game_name"]')&&!document.querySelector('input[name="game_name"]').disabled`);
  assert.equal((await invoke('player_read',{input:{resource:'access'}})).reason,'GAME_PROFILE_REQUIRED');
  await evaluate(`document.querySelector('input[name="game_name"]').focus()`);
  await call('Input.insertText',{text:'NativeBuilder'});await button('Сохранить игровой ник');
  await wait(`document.querySelector('[data-testid="game-name"]')?.textContent==='NativeBuilder'`);
  assert.equal((await invoke('game_profile_read')).profile.game_name,'NativeBuilder');
  assert.equal((await invoke('player_read',{input:{resource:'access'}})).reason,'GRANT_REQUIRED');
 }else if(phase==='restore'){
  assert.equal(start.state,'SIGNED_IN');assert.equal(start.remembered,true);assert.equal(start.profile.login,fixture.login);
  await wait(`document.body.innerText.includes('Почта для ручного восстановления')`);
  await wait(`document.querySelector('[data-testid="game-name"]')?.textContent==='NativeBuilder'`);
  assert.equal(await evaluate(`document.body.innerText.includes('Активные сессии')||document.body.innerText.includes('Подключить MFA')`),false);
  await button('Выйти');await wait(`!!document.querySelector('input[name="login"]')`);await button('Забыли пароль?');
  await wait(`document.body.innerText.includes('Восстановление через владельца')`);
  assert.equal(await evaluate(`!!document.querySelector('input[name="email"]')`),false);
 }else{
  assert.equal(start.state,'SIGNED_OUT');const response=await fetch('http://127.0.0.1:39431/__fixture__/friends-reset',{method:'POST'});assert.equal(response.status,200);const reset=await response.json();assert.equal(reset.delivery_email,fixture.email);
  await login(reset.temporary_password);await wait(`document.body.innerText.includes('Смените временный пароль')`);
  assert.equal(await evaluate(`!!document.querySelector('input[name="game_name"]')||!!document.querySelector('[data-testid="game-name"]')`),false);
  assert.equal((await invoke('account_snapshot')).profile.password_change_required,true);
  await evaluate(`(()=>{const f=document.querySelector('[data-testid="password-change"] form');f.elements.password.value=${JSON.stringify(reset.temporary_password)};f.elements.new_password.value=${JSON.stringify(fixture.newPassword)};f.requestSubmit();})()`);
  await wait(`!!document.querySelector('input[name="login"]')&&!document.querySelector('input[name="login"]').matches(':disabled')`);await login(fixture.newPassword);
  assert.equal((await invoke('account_snapshot')).profile.password_change_required,false);
  await wait(`document.querySelector('[data-testid="game-name"]')?.textContent==='NativeBuilder'`);
 }
 const screen=await call('Page.captureScreenshot',{format:'png'});await writeFile(new URL(`../.tools/friends-native-${phase}.png`,import.meta.url),Buffer.from(screen.data,'base64'));
 await evaluate(`location.hash='#settings'`);await wait(`!!document.querySelector('[data-testid="simple-update"]')`);
 assert.equal(await evaluate(`!!document.querySelector('[data-testid="launcher-update"]')`),false);
 await evaluate(`location.hash='#home'`);await wait(`!!document.querySelector('[data-testid="play"]')`);
 assert.equal(await evaluate(`document.querySelector('[data-testid="play"]').disabled`),true);
 assert.equal(await evaluate(`JSON.stringify({...localStorage,...sessionStorage}).includes(${JSON.stringify(fixture.password)})`),false);
 console.log(`PASS: friends native UI ${phase}, real account forms/remembered login/manual recovery/password change; PLAY closed.`);
}finally{socket.close();}
