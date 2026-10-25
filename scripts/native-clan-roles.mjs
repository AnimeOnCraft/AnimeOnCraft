import assert from 'node:assert/strict';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {once} from 'node:events';
import {mkdirSync,writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
/** Real acceptance launcher against db/clan-roles-smoke.ts: owner and leader role panels, recruiter queue; screenshots into fixture.out. */
export async function nativeClanRoles(fixture){
 const profile=path.join(root,'.tools',`game-native-profile-${randomUUID()}`);mkdirSync(profile,{recursive:true});
 const child=spawn(fixture.executable,[],{cwd:root,windowsHide:true,stdio:'ignore',env:{...process.env,WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:'--remote-debugging-port=19222',WEBVIEW2_USER_DATA_FOLDER:profile}});
 let socket,sequence=0,signed=false;const pending=new Map();
 const call=(method,params)=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(new Error('Native roles UI timeout'));},15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
 async function evaluate(expression){const reply=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.ok(!reply.exceptionDetails,'Native roles expression failed: '+expression.slice(0,120)+' -> '+String(reply.exceptionDetails?.exception?.description??reply.exceptionDetails?.text).slice(0,300));return reply.result.value;}
 const invoke=(command,input)=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(input??{})})`);
 async function wait(expression,what){for(let i=0;i<200;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,100));}throw Error('Native roles UI did not settle: '+what);}
 async function shot(name,selector){if(selector)await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'start'})`);await new Promise(r=>setTimeout(r,400));
  const image=await call('Page.captureScreenshot',{format:'png'});writeFileSync(path.join(fixture.out,name),Buffer.from(image.data,'base64'));}
 async function login(login){if(signed)await invoke('account_action',{input:{action:'logout'}});const account=await invoke('account_action',{input:{action:'login',login,password:fixture.password,remember:false}});assert.equal(account.state,'SIGNED_IN');signed=true;
  await evaluate(`window.dispatchEvent(new Event('animeoncraft-account-changed'))`);}// as accountAction() does for the UI login
 async function openTab(label){await evaluate(`location.hash='#clans/${fixture.clan}'`);await wait(`Array.from(document.querySelectorAll('[role="tab"]')).some(b=>b.textContent.includes(${JSON.stringify(label)}))`,'tabs');
  await evaluate(`Array.from(document.querySelectorAll('[role="tab"]')).find(b=>b.textContent.includes(${JSON.stringify(label)})).click()`);}
 const text=selector=>`(document.querySelector(${JSON.stringify(selector)})?.innerText??'')`;
 try{
  let target;for(let i=0;i<150;i++){try{target=(await(await fetch('http://127.0.0.1:19222/json/list')).json()).find(t=>t.url.includes('tauri.localhost'));if(target)break;}catch{/* Wait for own WebView startup. */}await new Promise(r=>setTimeout(r,150));}assert.ok(target,'WebView did not start');
  socket=new WebSocket(target.webSocketDebuggerUrl);await once(socket,'open');socket.addEventListener('message',({data})=>{const result=JSON.parse(data),item=pending.get(result.id);if(!item)return;pending.delete(result.id);clearTimeout(item.timer);if(result.error)item.reject(new Error('Native roles CDP failure'));else item.resolve(result.result);});
  await wait(`!!window.__TAURI_INTERNALS__&&document.readyState==='complete'`,'tauri bridge');
  assert.equal((await invoke('native_health')).account_environment,'acceptance-loopback-v1');
  assert.equal((await invoke('account_snapshot')).state,'SIGNED_OUT','Unexpected acceptance login preserved');
  // Owner: every role, including the leader.
  await login(fixture.owner);await openTab('Состав');
  await wait(`${text('[data-testid="clan-roles"]')}.includes('Akira_Lead')&&document.querySelectorAll('[data-testid="clan-roles"] select').length>=4`,'owner roles panel');
  await wait(`${text('.clan-roster')}.includes('Глава')`,'roster role labels');
  await shot('01-owner-roster.png','.clan-roster');await shot('02-owner-roles.png','[data-testid="clan-roles"]');
  assert.ok(await evaluate(`Array.from(document.querySelectorAll('[data-testid="clan-roles"] select option')).some(o=>o.value==='LEADER')`),'owner can appoint a leader');
  // Leader: appoints a recruiter, cannot pick a leader.
  await login(fixture.leader);await openTab('Состав');
  await wait(`${text('[data-testid="clan-roles"]')}.includes('• Глава')&&${text('[data-testid="clan-roles"]')}.includes('Sora_Member')`,'leader roles panel');
  assert.ok(!(await evaluate(`Array.from(document.querySelectorAll('[data-testid="clan-roles"] select option')).some(o=>o.value==='LEADER')`)),'leader cannot appoint a leader');
  await evaluate(`(()=>{const select=document.querySelector('[data-testid="clan-roles"] select[aria-label="Роль Sora_Member"]');Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(select,'RECRUITER');select.dispatchEvent(new Event('change',{bubbles:true}));})()`);
  await wait(`${text('[data-testid="clan-roles"]')}.includes('Сохранено.')`,'leader saved');
  await wait(`document.querySelector('[data-testid="clan-roles"] select[aria-label="Роль Sora_Member"]')?.value==='RECRUITER'`,'refreshed role');
  await shot('03-leader-roles.png','[data-testid="clan-roles"]');
  // Recruiter: the application queue of their clan.
  await login(fixture.recruiter);await openTab('Заявки');
  await wait(`${text('[data-testid="staff-applications"]')}.includes('Hana_New')`,'recruiter queue');
  await shot('04-recruiter-applications.png','[data-testid="staff-applications"]');
  await evaluate(`Array.from(document.querySelectorAll('[data-testid="staff-applications"] button')).find(b=>b.textContent==='Принять').click()`);
  await wait(`${text('[data-testid="staff-applications"]')}.includes('Ожидающих заявок нет.')`,'application accepted');
  await shot('05-recruiter-accepted.png','[data-testid="staff-applications"]');
  await openTab('Состав');await wait(`${text('.clan-roster')}.includes('Hana_New')&&${text('.clan-roster')}.includes('Рекрутер')`,'member roster');
  assert.equal(await evaluate(`!!document.querySelector('[data-testid="clan-roles"]')`),false,'recruiter sees no role controls');
  await shot('06-member-roster.png','.clan-roster');
 }finally{
  try{if(signed&&socket?.readyState===WebSocket.OPEN)await invoke('account_action',{input:{action:'logout'}});}finally{socket?.close();for(const item of pending.values())clearTimeout(item.timer);if(child.exitCode===null){const exited=once(child,'exit');child.kill();await exited;}await promisify(execFile)('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'scripts/cleanup-native-game-profile.ps1'),'-Profile',profile],{windowsHide:true});}
 }
}
