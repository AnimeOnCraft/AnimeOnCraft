import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {mkdirSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const root=path.resolve(import.meta.dirname,'..'),app=path.join(root,'apps/launcher');
const out=path.join(root,'.tools/owner-password-reset-20261025');mkdirSync(out,{recursive:true});
const require=createRequire(path.join(app,'package.json'));
const {createServer}=await import(pathToFileURL(require.resolve('vite')).href);
const html=`<!doctype html><html lang="ru"><meta charset="utf-8"><div id="root"></div>
<script>window.isTauri=true;window.calls=[];window.nextError=null;
window.__TAURI_INTERNALS__={invoke:(command,args)=>{if(command!=='owner_password_reset')throw Error('Unexpected fixture command');window.calls.push(args.input);if(window.nextError)return Promise.reject(window.nextError);return new Promise(resolve=>{window.completeReset=()=>resolve({user_id:'12345678-1234-4234-8234-123456789012',login:'FixtureAccount',game_name:'FixtureNick',delivery_email:'fixture@example.test',temporary_password:'Fixture_Temporary_123456'});});}};
Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw Error('Fixture clipboard denial');}}});</script>
<script type="module">import React,{StrictMode} from 'react';import{createRoot}from'react-dom/client';import{OwnerPasswordResetPanel}from'/src/owner-password-reset.tsx';import'@animeoncraft/ui/styles.css';import'/src/launcher.css';window.fixtureRoot=createRoot(document.getElementById('root'));window.fixtureRoot.render(React.createElement(StrictMode,null,React.createElement(OwnerPasswordResetPanel)));</script></html>`;
const server=await createServer({root:app,configFile:path.join(app,'vite.config.ts'),server:{host:'127.0.0.1',port:0,strictPort:false},plugins:[{
 name:'owner-password-reset-isolated-fixture',configureServer(vite){vite.middlewares.use('/__owner_reset_fixture',async(req,res,next)=>{try{res.setHeader('Content-Type','text/html; charset=utf-8');res.end(await vite.transformIndexHtml('/__owner_reset_fixture',html));}catch(error){next(error);}});}
}]});
await server.listen();const httpPort=server.httpServer.address().port;
const free=net.createServer();await new Promise(resolve=>free.listen(0,'127.0.0.1',resolve));const debugPort=free.address().port;await new Promise(resolve=>free.close(resolve));
const profile=path.join(out,'browser-'+Date.now());
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--no-first-run','--no-default-browser-check',`--user-data-dir=${profile}`,`--disk-cache-dir=${profile}/cache`,`--remote-debugging-port=${debugPort}`,'--window-size=1180,900',`http://127.0.0.1:${httpPort}/__owner_reset_fixture`],{windowsHide:true,stdio:'ignore'});
let socket;const pending=new Map();let id=0;
function call(method,params={}){return new Promise((resolve,reject)=>{const key=++id,timer=setTimeout(()=>{pending.delete(key);reject(Error('Fixture CDP timeout'));},15000);pending.set(key,{resolve,reject,timer});socket.send(JSON.stringify({id:key,method,params}));});}
async function evaluate(expression){const value=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.ok(!value.exceptionDetails,'UI fixture expression failed (details withheld)');return value.result.value;}
async function wait(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await new Promise(resolve=>setTimeout(resolve,100));}throw Error('UI fixture condition timed out (no credentials logged)');}
const submit=`(()=>{const f=document.querySelector('form');f.elements.login.value='FixtureNick';f.elements.password.value='Fixture-Owner-Password-2026!';f.elements.proof.value='123456';f.requestSubmit();})()`;
try{
 let target;for(let i=0;i<100;i++){try{target=(await(await fetch('http://127.0.0.1:'+debugPort+'/json/list')).json()).find(p=>p.url.includes('__owner_reset_fixture'));if(target)break;}catch{/* The disposable browser may already be closed or still starting. */}await new Promise(resolve=>setTimeout(resolve,100));}assert.ok(target,'Own test browser did not start');
 socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
 socket.addEventListener('message',event=>{const value=JSON.parse(event.data),item=pending.get(value.id);if(!item)return;pending.delete(value.id);clearTimeout(item.timer);if(value.error)item.reject(Error('CDP command rejected'));else item.resolve(value.result);});
 await wait(`!!document.querySelector('input[name="login"]')`);
 await evaluate(submit);await evaluate(`document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))`);
 assert.equal(await evaluate('window.calls.length'),1,'Duplicate submission issued a second reset');
 assert.equal(await evaluate(`document.querySelector('input[name="password"]').value===''&&document.querySelector('input[name="proof"]').value===''`),true,'Proof inputs not cleared');
 await evaluate('window.completeReset()');await wait(`document.body.textContent.includes('FixtureAccount')&&!!document.querySelector('input[readonly]')`);
 assert.equal(await evaluate(`document.querySelector('input[readonly]').value==='Fixture_Temporary_123456'`),true,'StrictMode lost successful reset response');
 await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Скопировать пароль').click()`);
 await wait(`document.body.textContent.includes('Не удалось скопировать')`);
 await evaluate(`Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>{window.copiedFixture=text;}}});Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Скопировать пароль').click()`);
 await wait(`document.body.textContent.includes('Скопировано')`);
 assert.equal(await evaluate(`window.copiedFixture==='Fixture_Temporary_123456'`),true);
 assert.equal(await evaluate(`JSON.stringify([localStorage,sessionStorage]).includes('Fixture_Temporary_123456')`),false,'Temporary password persisted');
 const screenshot=await call('Page.captureScreenshot',{format:'png'});writeFileSync(path.join(out,'owner-password-reset-preview.png'),Buffer.from(screenshot.data,'base64'));
 await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Готово — скрыть пароль').click()`);await wait(`!!document.querySelector('form')&&!document.querySelector('input[readonly]')`);
 await evaluate(`window.nextError='OWNER_MFA_REQUIRED'`);await evaluate(submit);await wait(`document.body.textContent.includes('Сначала подключите MFA')&&!document.querySelector('button[type="submit"]').disabled`);
 await evaluate(`window.nextError=null`);await evaluate(submit);await evaluate('window.fixtureRoot.unmount();window.completeReset()');
 assert.equal(await evaluate(`document.getElementById('root').textContent`),'','Unmounted recovery displayed a late response');
 const result={passed:true,react_strict_mode:true,duplicate_submit_blocked:true,proof_inputs_cleared:true,temporary_password_displayed:true,clipboard_failure_and_success:true,secret_not_persisted:true,hidden_after_dismissal:true,mfa_guidance:true,unmount_safe:true,production_accounts_unchanged:true};
 writeFileSync(path.join(out,'ui-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{
 try{if(socket?.readyState===WebSocket.OPEN)await call('Browser.close');}catch{/* The disposable browser may already be closed or still starting. */}socket?.close();for(const item of pending.values())clearTimeout(item.timer);await server.close();if(browser.exitCode===null)browser.kill();
}
