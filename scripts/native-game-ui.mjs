import assert from 'node:assert/strict';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {once} from 'node:events';
import {mkdirSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));

// Used only by the isolated admission test. Password stays in memory/stdin.
export async function nativeGame(fixture){
 const profile=path.join(root,'.tools',`game-native-profile-${randomUUID()}`);
 mkdirSync(profile,{recursive:true});
 const runtime=path.join(profile,'game-runtime.json');
 writeFileSync(runtime,JSON.stringify(nativeLaunchPlan({serverId:fixture.server_id,serverPort:25566,gameDirectory:path.join(root,'.tools/game-admission-smoke/client'),java:path.join(process.env.JAVA_HOME,'bin/java.exe'),capeProbe:true})));
 const child=spawn(path.join(root,'.tools/native-game-fixture.exe'),[],{cwd:root,windowsHide:true,stdio:'ignore',env:{...process.env,AOC_GAME_RUNTIME_FIXTURE:runtime,WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:'--remote-debugging-port=19222',WEBVIEW2_USER_DATA_FOLDER:profile}});
 let socket,sequence=0;const pending=new Map();
 async function close(){
  try{if(socket?.readyState===WebSocket.OPEN){await invoke('game_stop');for(let i=0;i<100;i++){if((await invoke('game_snapshot')).state!=='RUNNING')break;await new Promise(resolve=>setTimeout(resolve,100));}await invoke('account_action',{input:{action:'logout'}});}}finally{socket?.close();if(child.exitCode===null){const exit=once(child,'exit');child.kill();await exit;}await promisify(execFile)('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'scripts/cleanup-native-game-profile.ps1'),'-Profile',profile],{windowsHide:true});}
 }
 const call=(method,params)=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(new Error(`Native game CDP timeout: ${method}`));},45000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
 async function evaluate(expression){const reply=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.ok(!reply.exceptionDetails,'Native game expression failed (details withheld)');return reply.result.value;}
 async function invoke(command,args={}){const result=await evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)}).then(value=>({ok:true,value}),error=>({ok:false,error}))`);assert.equal(result.ok,true,`Native game command ${command} failed: ${result.error}`);assert.ok(!/(?:aa|ar|gt)_[A-Za-z0-9_-]{43}/.test(JSON.stringify(result.value)));return result.value;}
 try{
  let target;
  for(let i=0;i<100;i++){try{target=(await(await fetch('http://127.0.0.1:19222/json/list')).json()).find(t=>t.url.includes('tauri.localhost'));if(target)break;}catch{/* own startup */}await new Promise(r=>setTimeout(r,200));}
  assert.ok(target,'Own native game window did not open');
  socket=new WebSocket(target.webSocketDebuggerUrl);await once(socket,'open');
  socket.addEventListener('message',({data})=>{const result=JSON.parse(data),request=pending.get(result.id);if(!request)return;pending.delete(result.id);clearTimeout(request.timer);if(result.error)request.reject(new Error('Native game CDP failure'));else request.resolve(result.result);});
  assert.equal((await invoke('native_health')).account_environment,'acceptance-loopback-v1','Only the isolated account environment may run this test');
  const account=await invoke('account_snapshot');assert.equal(account.state,'SIGNED_OUT','Preserve any unexpected acceptance account');
  assert.equal((await invoke('account_action',{input:{action:'login',login:'GameNativeProbe',password:fixture.password,remember:false}})).state,'SIGNED_IN');
  if(fixture.skin_png_base64){
   await evaluate(`location.hash='#profile'`);
   for(let i=0;i<150;i++){if(await evaluate(`!!document.querySelector('[data-testid="skin-file"]')`))break;await new Promise(r=>setTimeout(r,100));}
   const skinFile=path.join(profile,'fixture-skin.png');writeFileSync(skinFile,Buffer.from(fixture.skin_png_base64,'base64'));
   const doc=await call('DOM.getDocument',{});const input=await call('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'[data-testid="skin-file"]'});assert.ok(input.nodeId);
   await call('DOM.setFileInputFiles',{nodeId:input.nodeId,files:[skinFile]});
   await evaluate(`(()=>{const select=document.querySelector('[data-testid="skin-model"]');Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(select,'slim');select.dispatchEvent(new Event('change',{bubbles:true}));})()`);
   for(let i=0;i<100;i++){if(await evaluate(`!!document.querySelector('[data-testid="skin-preview"]')&&!document.querySelector('[data-testid="skin-save"]').disabled`))break;await new Promise(r=>setTimeout(r,100));}
   await evaluate(`document.querySelector('[data-testid="skin-save"]').click()`);
   for(let i=0;i<150;i++){if(await evaluate(`document.body.innerText.includes('Сохранено. Новый вид')`))break;await new Promise(r=>setTimeout(r,100));}
   assert.equal((await invoke('skin_read')).skin.model,'slim');
   await evaluate(`document.querySelector('#skin-title').scrollIntoView({block:'start'})`);
   const screenshot=await call('Page.captureScreenshot',{format:'png'});writeFileSync(path.join(root,'.tools/skin-native-profile.png'),Buffer.from(screenshot.data,'base64'));
   await evaluate(`location.hash='#home'`);
   console.log('Native skin: returned to home');
   console.log('Native skin: selected real PNG file, saved slim model through profile UI and read it back');
  }
  for(let i=0;i<300;i++){if(await evaluate(`!!document.querySelector('[data-testid="play"]')&&!document.querySelector('[data-testid="play"]').disabled`))break;await new Promise(r=>setTimeout(r,100));}
  assert.equal(await evaluate(`document.querySelector('[data-testid="play"]').disabled`),false);
  console.log('Native PLAY: button available');
  await evaluate(`document.querySelector('[data-testid="play"]').click()`);
  console.log('Native PLAY: click delivered');
  for(let i=0;i<100;i++){const status=await invoke('game_snapshot');if(status.state==='RUNNING')break;assert.notEqual(status.state,'FAILED',status.reason);await new Promise(r=>setTimeout(r,100));}
  assert.equal((await invoke('game_snapshot')).state,'RUNNING');
  const second=await evaluate(`window.__TAURI_INTERNALS__.invoke('game_launch').then(()=>false,e=>e==='GAME_ALREADY_RUNNING')`);assert.equal(second,true);
  console.log('Native PLAY: actual Windows button started Java; duplicate launch rejected; account tokens remain native');
  async function checkCape(){
   await evaluate(`location.hash='#profile'`);
   for(let i=0;i<100;i++){if(await evaluate(`!!document.querySelector('[data-testid="cape-refresh"]')`))break;await new Promise(r=>setTimeout(r,100));}
   await evaluate(`document.querySelector('[data-testid="cape-refresh"]').click()`);
   for(let i=0;i<100;i++){if((await invoke('cape_read')).equipped==='beta')break;await new Promise(r=>setTimeout(r,100));}
   assert.equal((await invoke('cape_read')).equipped,'beta');
   for(let i=0;i<100;i++){if(await evaluate(`!!document.querySelector('[data-testid="cape-remove"]')&&!document.querySelector('[data-testid="cape-remove"]').disabled`))break;await new Promise(r=>setTimeout(r,100));}
   await evaluate(`document.querySelector('[data-testid="cape-remove"]').click()`);
   for(let i=0;i<100;i++){if((await invoke('cape_read')).equipped===null)break;await new Promise(r=>setTimeout(r,100));}
   assert.equal((await invoke('cape_read')).equipped,null);
   for(let i=0;i<100;i++){if(await evaluate(`!!document.querySelector('[data-testid="cape-equip"]')&&!document.querySelector('[data-testid="cape-equip"]').disabled`))break;await new Promise(r=>setTimeout(r,100));}
   await evaluate(`document.querySelector('[data-testid="cape-equip"]').click()`);
   for(let i=0;i<100;i++){if((await invoke('cape_read')).equipped==='beta')break;await new Promise(r=>setTimeout(r,100));}
   assert.equal((await invoke('cape_read')).equipped,'beta');
   await evaluate(`document.querySelector('[data-testid="cape-refresh"]').scrollIntoView({block:'center'})`);
   const image=await call('Page.captureScreenshot',{format:'png'});writeFileSync(path.join(root,'.tools/beta-cape-native-profile.png'),Buffer.from(image.data,'base64'));
   console.log('Native cape: actual Beta ownership after world join; profile remove/equip buttons persist selection');
  }
  return {close,checkCape,screenshot:async()=>{const screen=await call('Page.captureScreenshot',{format:'png'});writeFileSync(path.join(root,'.tools/game-native-play.png'),Buffer.from(screen.data,'base64'));}};
 }catch(error){console.error(`Native game test failed: ${error.message}`);try{await close();}catch{console.error('Own native game cleanup also failed; original error preserved');}throw error;}
}
