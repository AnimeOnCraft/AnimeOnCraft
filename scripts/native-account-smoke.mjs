import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const {TOTP,Secret}=createRequire(new URL('../apps/backend/package.json',import.meta.url))('otpauth');
const fixture=JSON.parse(await readFile(process.argv[2],'utf8')),phase=process.argv[3];
let target;
for(let i=0;i<60;i++){try{const pages=await(await fetch('http://127.0.0.1:19222/json/list')).json();target=pages.find(p=>p.url.includes('tauri.localhost'));if(target)break;}catch{/* own WebView starting */}await new Promise(r=>setTimeout(r,200));}
assert.ok(target);const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const message=JSON.parse(data),request=pending.get(message.id);if(!request)return;pending.delete(message.id);clearTimeout(request.timer);if(message.error)request.reject(new Error('Native CDP failed'));else request.resolve(message.result);});
function call(method,params){return new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(new Error(`Native test timed out: ${method}`));},20000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const result=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw new Error('Native fixture expression failed (details withheld)');return result.result.value;}
async function invoke(command,args={}){const reply=await evaluate(`(async()=>{try{return {ok:true,value:await window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})};}catch(error){return {ok:false,code:typeof error==='string'&&/^[A-Z_]+$/.test(error)?error:'IPC_FAILURE'};}})()`);if(!reply.ok)throw new Error(`Native fixture ${command} failed: ${reply.code}`);const value=reply.value;assert.equal(/(?:aa|ar|ac)_[A-Za-z0-9_-]{43}/.test(JSON.stringify(value)),false,'Secret leaked across IPC');return value;}
async function control(name){const result=await fetch(`http://127.0.0.1:39431/__fixture__/${name}`,{method:'POST'});assert.equal(result.status,200);return result.json();}
async function waitFor(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,100));}assert.fail('Native UI condition not reached');}
try{
 await waitFor(`typeof window.__TAURI_INTERNALS__?.invoke==='function'&&!!document.querySelector('[data-testid="play"]')`);
 assert.equal((await invoke('native_health')).account_environment,'acceptance-loopback-v1');
 const snapshot=await invoke('account_snapshot');
 if(phase==='flow'){
  assert.equal(snapshot.state,'SIGNED_OUT');
  await evaluate(`location.hash='#profile'`);await waitFor(`!!document.querySelector('input[name="email"]')&&!document.querySelector('input[name="email"]').matches(':disabled')`);
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='Войти'&&!b.disabled)`),true);
  await invoke('account_action',{input:{action:'register',email:fixture.email,password:fixture.password}});
  const mail=await(await fetch('http://127.0.0.1:39431/__fixture__/mail')).json();assert.ok(mail.token);
  await invoke('account_action',{input:{action:'verify',token:mail.token,password:fixture.password}});
  // Use the real React login form, not a mock transport.
  await evaluate(`(()=>{const f=document.querySelector('input[name="email"]').form;f.elements.email.value=${JSON.stringify(fixture.email)};f.elements.password.value=${JSON.stringify(fixture.password)};f.elements.remember.checked=true;f.requestSubmit();})()`);
  await waitFor(`document.body.innerText.includes(${JSON.stringify(fixture.email)})&&document.body.innerText.includes('Выйти')`);
  const signed=await invoke('account_snapshot');assert.equal(signed.state,'SIGNED_IN');assert.equal(signed.remembered,true);
  const screenshot=await call('Page.captureScreenshot',{format:'png'});await writeFile(new URL('../.tools/native-account-profile.png',import.meta.url),Buffer.from(screenshot.data,'base64'));
  const access=await invoke('player_read',{input:{resource:'access'}});assert.equal(access.reason,'MINECRAFT_IDENTITY_REQUIRED');
  let operation=await invoke('player_prepare_action',{input:{action:'apply',clan_id:fixture.clanId,message:'Native acceptance application with enough actual fixture text.'}});
  assert.equal((await invoke('player_submit_action',{operationId:operation.operation_id,retry:false})).status,'REJECTED');
  await control('identity');
  await control('lose-write');
  operation=await invoke('player_prepare_action',{input:{action:'apply',clan_id:fixture.clanId,message:'Native acceptance application with enough actual fixture text.'}});
  assert.equal((await invoke('player_submit_action',{operationId:operation.operation_id,retry:false})).status,'UNKNOWN_OUTCOME');
  assert.equal((await invoke('player_submit_action',{operationId:operation.operation_id,retry:true})).status,'COMPLETED');
  const applications=await invoke('player_read',{input:{resource:'applications',cursor:null}});assert.equal(applications.items.length,1);
  const row=applications.items[0];operation=await invoke('player_prepare_action',{input:{action:'withdraw',application_id:row.id,version:row.version,reason:'Native fixture withdrawal'}});
  assert.equal((await invoke('player_submit_action',{operationId:operation.operation_id,retry:false})).status,'COMPLETED');
  await control('join');const clan=await invoke('player_read',{input:{resource:'clan'}});operation=await invoke('player_prepare_action',{input:{action:'leave',version:clan.membership.version,reason:'Native fixture leave'}});
  assert.equal((await invoke('player_submit_action',{operationId:operation.operation_id,retry:false})).status,'COMPLETED');
  await control('expire');const reads=await Promise.all(Array.from({length:3},()=>invoke('player_read',{input:{resource:'access'}})));assert.equal(reads.length,3);
  assert.equal((await(await fetch('http://127.0.0.1:39431/__fixture__/state')).json()).refreshCount,1,'Concurrent401 caused repeated refresh');
  assert.equal(await evaluate(`JSON.stringify({...localStorage,...sessionStorage}).includes(${JSON.stringify(fixture.password)})`),false);
  assert.equal(await evaluate(`document.querySelector('[data-testid="play"]')?.disabled??true`),true);
 }else if(phase==='restore'){
  assert.equal(snapshot.state,'SIGNED_IN');assert.equal(snapshot.remembered,true);assert.equal(snapshot.profile.email,fixture.email);
  await invoke('player_read',{input:{resource:'clan'}});
 }else if(phase==='crash'){
  assert.equal(snapshot.state,'SIGNED_IN');await control('expire');await control('hold-refresh');
  // Start refresh without awaiting it; parent terminates only this fixture process after pending/send.
  await evaluate(`window.__TAURI_INTERNALS__.invoke('player_read',{input:{resource:'access'}}).catch(()=>{});true`);
  let observed=false;for(let i=0;i<100;i++){if((await(await fetch('http://127.0.0.1:39431/__fixture__/state')).json()).refreshStarted){observed=true;break;}await new Promise(r=>setTimeout(r,100));}assert.ok(observed,'Refresh request did not reach held fixture handler');
 }else if(phase==='reauth'){
  assert.equal(snapshot.state,'REAUTH_REQUIRED','Pending credential must not be retried on restart');
  await invoke('account_action',{input:{action:'recover',email:fixture.email}});const mail=await(await fetch('http://127.0.0.1:39431/__fixture__/mail')).json();
  await invoke('account_action',{input:{action:'reset',token:mail.token,password:fixture.newPassword}});

  let account=await invoke('account_action',{input:{action:'login',email:fixture.email,password:fixture.newPassword,remember:true}});assert.equal(account.state,'SIGNED_IN');
  const abandoned=account.profile.session_id;
  const rejected=await evaluate(`window.__TAURI_INTERNALS__.invoke('security_action',${JSON.stringify({input:{action:'password',password:'Wrong-Fixture-Password-2026!',new_password:'Rejected-Fixture-New-Password-2026!',proof:null}})}).then(()=>false,e=>e==='AUTHENTICATION_REQUIRED')`);assert.equal(rejected,true);const failed=await invoke('account_snapshot');assert.equal(failed.state,'SIGNED_OUT');assert.equal(failed.notice,'SECURITY_ACTION_REJECTED','Rejected write was presented as successful');
  account=await invoke('account_action',{input:{action:'login',email:fixture.email,password:fixture.newPassword,remember:true}});assert.equal(account.state,'SIGNED_IN');await invoke('security_action',{input:{action:'revoke',session_id:abandoned}});
  await evaluate(`location.hash='#profile'`);await waitFor(`!!document.querySelector('.security-panel')&&document.body.innerText.includes('текущий вход')`);
  const safeImage=await call('Page.captureScreenshot',{format:'png'});await writeFile(new URL('../.tools/native-security-profile.png',import.meta.url),Buffer.from(safeImage.data,'base64'));
  await evaluate(`(()=>{const b=Array.from(document.querySelectorAll('.security-panel button')).find(b=>b.textContent==='Подключить MFA');const f=b.form;f.elements.password.value=${JSON.stringify(fixture.newPassword)};f.requestSubmit();})()`);
  await waitFor(`!!document.querySelector('.provisioning-secret')`);const seed=await evaluate(`document.querySelector('.provisioning-secret').textContent`);assert.equal(seed.length,32);
  const totp=new TOTP({issuer:'AnimeOnCraft',label:fixture.email,algorithm:'SHA1',digits:6,period:30,secret:Secret.fromBase32(seed)});
  await evaluate(`(()=>{const f=document.querySelector('.security-panel input[name="proof"]').form;f.elements.password.value=${JSON.stringify(fixture.newPassword)};f.elements.proof.value=${JSON.stringify(totp.generate())};f.requestSubmit();})()`);
  await waitFor(`document.querySelectorAll('.recovery-codes code').length===10`);const codes=await evaluate(`Array.from(document.querySelectorAll('.recovery-codes code')).map(c=>c.textContent)`);assert.equal(new Set(codes).size,10);
  account=await invoke('account_snapshot');assert.equal(account.state,'SIGNED_OUT');
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Коды сохранены — закрыть').click()`);assert.equal(await evaluate(`document.querySelectorAll('.recovery-codes code').length`),0);
  account=await invoke('account_action',{input:{action:'login',email:fixture.email,password:fixture.newPassword,remember:false}});assert.equal(account.state,'MFA_REQUIRED');
  await evaluate(`location.hash='#home'`);await waitFor(`document.querySelector('[data-testid="play"]')!==null`);await evaluate(`location.hash='#profile'`);await waitFor(`!!document.querySelector('input[name="proof"]')&&!document.querySelector('input[name="proof"]').matches(':disabled')`);
  await evaluate(`(()=>{const f=document.querySelector('input[name="proof"]').form;f.elements.proof.value=${JSON.stringify(codes[0])};f.requestSubmit();})()`);await waitFor(`!!document.querySelector('.security-panel')&&document.body.innerText.includes('текущий вход')`);
  const changed='Native-Fixture-Security-Password-2026!';
  await evaluate(`(()=>{const f=document.querySelector('.security-panel input[name="new_password"]').form;f.elements.password.value=${JSON.stringify(fixture.newPassword)};f.elements.new_password.value=${JSON.stringify(changed)};f.elements.proof.value=${JSON.stringify(totp.generate({timestamp:Date.now()+30000}))};f.requestSubmit();})()`);
  await waitFor(`document.body.innerText.includes('Настройки безопасности изменились')&&!document.querySelector('.security-panel')`);assert.equal((await invoke('account_snapshot')).state,'SIGNED_OUT');
  account=await invoke('account_action',{input:{action:'login',email:fixture.email,password:changed,remember:true}});assert.equal(account.state,'MFA_REQUIRED');
  const reused=await evaluate(`window.__TAURI_INTERNALS__.invoke('account_action',${JSON.stringify({input:{action:'mfa',operation_id:account.operation_id,proof:codes[0]}})}).then(()=>false,()=>true)`);assert.equal(reused,true,'Consumed recovery code was reused');
  account=await invoke('account_action',{input:{action:'mfa',operation_id:account.operation_id,proof:codes[1]}});assert.equal(account.state,'SIGNED_IN');
  const request=async(route,body,access)=>{const response=await fetch('http://127.0.0.1:39431/api/v1/auth/'+route,{method:'POST',headers:{'x-aoc-client':'launcher','content-type':'application/json',...(access?{authorization:'Bearer '+access}:{})},body:JSON.stringify(body)});assert.equal(response.ok,true,'Own secondary fixture request failed');return response.status===204?null:response.json();};
  const challenge=await request('login',{email:fixture.email,password:changed});const other=await request('mfa/login',{token:challenge.challenge,proof:codes[2]});
  let list=await invoke('security_sessions');assert.equal(list.items.length,2);assert.equal(list.items.filter(v=>v.current).length,1);
  assert.equal((await invoke('security_action',{input:{action:'revoke',session_id:other.session_id}})).kind,'completed');list=await invoke('security_sessions');assert.equal(list.items.length,1);
  assert.equal((await fetch('http://127.0.0.1:39431/api/v1/auth/me',{headers:{'x-aoc-client':'launcher',authorization:'Bearer '+other.access_token}})).status,401);
  await control('lose-security-write');const finalPassword='Native-Fixture-Final-Password-2026!';
  const unknown=await evaluate(`window.__TAURI_INTERNALS__.invoke('security_action',${JSON.stringify({input:{action:'password',password:changed,new_password:finalPassword,proof:codes[3]}})}).then(()=>false,e=>e==='SECURITY_OUTCOME_UNKNOWN')`);assert.equal(unknown,true);assert.equal((await invoke('account_snapshot')).state,'REAUTH_REQUIRED');assert.equal((await(await fetch('http://127.0.0.1:39431/__fixture__/state')).json()).passwordWrites,2,'Security write automatically replayed');
  account=await invoke('account_action',{input:{action:'login',email:fixture.email,password:finalPassword,remember:true}});account=await invoke('account_action',{input:{action:'mfa',operation_id:account.operation_id,proof:codes[5]}});
  list=await invoke('security_sessions');const current=list.items.find(v=>v.current);assert.ok(current);assert.equal((await invoke('security_action',{input:{action:'revoke',session_id:current.id}})).account.state,'SIGNED_OUT');
  assert.equal(await evaluate(`/(?:rc)_[A-Za-z0-9_-]{43}/.test(JSON.stringify({...localStorage,...sessionStorage}))||JSON.stringify({...localStorage,...sessionStorage}).includes(${JSON.stringify(seed)})`),false);
  const denied=await evaluate(`window.__TAURI_INTERNALS__.invoke('player_read',{input:{resource:'management'}}).then(()=>false,()=>true)`);assert.equal(denied,true);
  assert.equal(await evaluate(`window.__TAURI_INTERNALS__.invoke('plugin:window|create',{options:{label:'untrusted'}}).then(()=>false,()=>true)`),true,'Window creation must be denied');
 }
 console.log(`PASS: native account fixture ${phase}; typed secret-free IPC and disabled PLAY.`);
}finally{socket.close();for(const request of pending.values()){clearTimeout(request.timer);}pending.clear();}
