import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const base = 'http://127.0.0.1:19222';
const expectBlockedLocal = process.argv.includes('--expect-blocked-local');
const expectedVersion = (await readFile(new URL('../apps/launcher/src-tauri/Cargo.toml', import.meta.url), 'utf8')).match(/^version\s*=\s*"([^"]+)"/m)?.[1];
assert.ok(expectedVersion, 'Native package version was not found');
let target;
for (let attempt = 0; attempt < 30; attempt++) {
  try {
    const pages = await (await fetch(`${base}/json/list`, { signal: AbortSignal.timeout(1000) })).json();
    target = pages.find((page) => page.url.startsWith('http://tauri.localhost') || page.url.startsWith('https://tauri.localhost'));
    if (target) break;
  } catch { /* WebView2 is still starting. */ }
  await new Promise((resolve) => setTimeout(resolve, 1000));
}
assert.ok(target, 'Packaged launcher WebView2 did not start');
assert.ok(target.webSocketDebuggerUrl.startsWith('ws://127.0.0.1:19222/'), 'Unexpected debug endpoint');
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});
let sequence = 0;
const pending = new Map();
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data);
  const request = pending.get(message.id);
  if (!request) return;
  pending.delete(message.id);
  clearTimeout(request.timer);
  if (message.error) request.reject(new Error(message.error.message));
  else request.resolve(message.result);
});
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function capture(name) {
  await call('Runtime.evaluate', { expression: `Promise.all([document.fonts.ready, ...Array.from(document.images).map(image => image.decode().catch(() => {}))])`, awaitPromise: true });
  const screenshot = await call('Page.captureScreenshot', { format: 'png' });
  await mkdir(new URL('../.tools/', import.meta.url), { recursive: true });
  await writeFile(new URL(`../.tools/${name}.png`, import.meta.url), Buffer.from(screenshot.data, 'base64'));
}
try {
  let initialized=false;
  for(let attempt=0;attempt<100;attempt++) {
    const ready=await call('Runtime.evaluate',{expression:`typeof window.__TAURI_INTERNALS__?.invoke==='function'&&!!document.querySelector('[data-testid="play"]')`,returnByValue:true});
    if(ready.result.value===true){initialized=true;break;}
    await new Promise(resolve=>setTimeout(resolve,200));
  }
  assert.ok(initialized,'Native launcher did not initialize IPC and render the home screen');
  const result = await call('Runtime.evaluate', {
    expression: `(async () => {
      const invoke = window.__TAURI_INTERNALS__.invoke;
      const health = await invoke('native_health');
      const settings = await invoke('validate_launcher_settings', { input: { ram_mib: 4096, width: 1920, height: 1080, fullscreen: false } });
      let rejected = false;
      try { await invoke('validate_launcher_settings', { input: { ram_mib: 1, width: 1920, height: 1080, fullscreen: false } }); } catch { rejected = true; }
      const stored = await invoke('load_launcher_settings');
      let privateRejected = false;
      try { await invoke('read_public_resource', { input: { resource: 'auth' } }); } catch { privateRejected = true; }
      return { health, settings, stored, rejected, privateRejected, text: document.body.innerText, disabled: document.querySelector('[data-testid="play"]')?.disabled };
    })()`,
    returnByValue: true,
    awaitPromise: true
  });
  assert.equal(result.exceptionDetails, undefined, 'Native IPC failed');
  const value = result.result.value;
  assert.equal(value.health.status, 'LAUNCHER_ACCOUNT_DEV');
  assert.equal(value.health.account_environment, 'development-loopback-v1');
  assert.equal(value.health.version, expectedVersion, 'Rebuild the native executable before smoke');
  const pack=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('pack_availability')`,returnByValue:true,awaitPromise:true});assert.equal(pack.exceptionDetails,undefined);assert.deepEqual(pack.result.value,{available:false,reason:'RELEASE_NOT_CONFIGURED'});
  const installer = await call('Runtime.evaluate', { expression: `(async()=>{
    const invoke=window.__TAURI_INTERNALS__.invoke;
    const before=await invoke('installer_snapshot');
    const rejected=[];
    for(const input of [{action:'install'},{action:'repair'},{action:'recover'},{action:'confirm',plan_id:'0'.repeat(32)},{action:'discard',plan_id:'0'.repeat(32)},{action:'confirm',plan_id:'../world'},{action:'check',path:'F:/world'},{action:'install',url:'https://evil.invalid/'},{action:'cancel',session_id:before.session_id,job_id:0},{action:'launch'}]) {
      try {await invoke('installer_action',{input});rejected.push(false);}catch{rejected.push(true);}
    }
    let started=null,checkError=null;
    try {started=await invoke('installer_action',{input:{action:'check'}});} catch(error) {checkError=String(error);}
    return {before,rejected,started,checkError};
  })()`, returnByValue:true, awaitPromise:true });
  assert.equal(installer.exceptionDetails,undefined);
  assert.equal(installer.result.value.before.status,expectBlockedLocal?'blocked':'unavailable');
  assert.equal(installer.result.value.before.reason,expectBlockedLocal?'STORE_INVALID':'RELEASE_NOT_CONFIGURED');
  assert.match(installer.result.value.before.session_id,/^[0-9a-f]{32}$/);
  assert.equal(installer.result.value.before.plan,null);
  const local=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('installer_local_state')`,returnByValue:true,awaitPromise:true});
  assert.equal(local.exceptionDetails,undefined);
  assert.deepEqual(local.result.value,{status:expectBlockedLocal?'blocked':'ready',environment:'development-local-v1'});
  assert.ok(installer.result.value.rejected.every(Boolean),'Installer accepted unsupported authority');
  if(expectBlockedLocal) {
    assert.equal(installer.result.value.started,null);
    assert.match(installer.result.value.checkError,/STORE_INVALID/);
    assert.equal(installer.result.value.before.job_id,0);
  } else {
    assert.equal(installer.result.value.checkError,null);
    assert.equal(installer.result.value.started.busy,true);
    assert.equal(installer.result.value.started.status,'checking');
  }
  assert.equal(typeof value.health.play_available, 'boolean'); // Local runtime readiness now depends on game-runtime.json.
  assert.equal(value.settings.ram_mib, 4096);
  assert.equal(value.rejected, true);
  assert.equal(value.privateRejected, true);
  assert.ok(value.stored.settings.ram_mib >= 2048);
  assert.equal(value.disabled, true);
  assert.match(value.text, /Статус неизвестен/);
  for (let attempt=0;attempt<20;attempt++) {
    const rendered=await call('Runtime.evaluate',{expression:'document.body.innerText',returnByValue:true});
    if (rendered.result.value.includes(`Приложение ${value.health.version}`)) break;
    if(attempt===19)throw new Error('Native health was not rendered');
    await new Promise(resolve=>setTimeout(resolve,200));
  }
  const publicReply=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('read_public_resource',{input:{resource:'ready'}})`,returnByValue:true,awaitPromise:true});
  if(process.argv.includes('--require-backend')) {
    assert.equal(publicReply.exceptionDetails,undefined,'Development backend must be running for this acceptance check');
    assert.equal(publicReply.result.value.status,200);
    assert.equal(publicReply.result.value.body.status,'ok');
    for(const resource of ['clans','news']) {
      const reply=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('read_public_resource',{input:{resource:${JSON.stringify(resource)}}})`,returnByValue:true,awaitPromise:true});
      assert.equal(reply.exceptionDetails,undefined);
      assert.equal(reply.result.value.status,200);
      assert.ok(Array.isArray(reply.result.value.body.items));
    }
  } else if(!publicReply.exceptionDetails) {
    assert.ok([200,503].includes(publicReply.result.value.status));
    assert.ok(['ok','degraded','unavailable'].includes(publicReply.result.value.body.status));
  }
  for(const [hash,expected] of [['clans','Официальные кланы'],['news','Новости мира'],['profile','Твой профиль'],['settings','Настройки']]) {
    if(hash==='profile'&&process.argv.includes('--skip-account'))continue;
    await call('Runtime.evaluate',{expression:`window.location.hash=${JSON.stringify(hash)}`});
    let matched=false;
    for(let attempt=0;attempt<20;attempt++) {
      const screen=await call('Runtime.evaluate',{expression:'document.querySelector("h2.page-heading")?.textContent',returnByValue:true});
      if(screen.result.value===expected){matched=true;break;}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.ok(matched,`Native screen did not render: ${hash}`);
    if(hash==='settings') {
      const update=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('launcher_update_snapshot')`,returnByValue:true,awaitPromise:true});
      assert.equal(update.result.value.source_configured,false);assert.equal(update.result.value.status,'unavailable');assert.equal(update.result.value.reason,'UPDATE_NOT_CONFIGURED');assert.equal(update.result.value.plan,null);assert.equal(update.result.value.busy,false);
      const unsafeUpdate=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('launcher_update_action',{input:{action:'check',url:'https://evil.invalid'}}).then(()=>false,()=>true)`,returnByValue:true,awaitPromise:true});
      assert.equal(unsafeUpdate.result.value,true);
      let settled=false;
      for(let attempt=0;attempt<30;attempt++) {
        const panel=await call('Runtime.evaluate',{expression:`(()=>{const root=document.querySelector('[data-testid="installer"]');return {text:root?.innerText,buttons:Array.from(root?.querySelectorAll('button')??[]).map(b=>({text:b.textContent,disabled:b.disabled}))};})()`,returnByValue:true});
        if(panel.result.value.text?.includes(expectBlockedLocal?'Локальные данные установки не прошли проверку.':'Сборка для установки пока не опубликована.')) {
          if(!panel.result.value.text.includes(expectBlockedLocal?'Не удалось подтвердить локальный каталог.':'Локальный каталог экземпляра подготовлен.')) {await new Promise(resolve=>setTimeout(resolve,100));continue;}
          const buttons=panel.result.value.buttons;
          for(const label of ['Установить сборку','Исправить файлы','Восстановить операцию'])assert.equal(buttons.find(b=>b.text===label)?.disabled,true);
          assert.equal(buttons.find(b=>b.text==='Проверить сборку')?.disabled,expectBlockedLocal);
          settled=true;break;
        }
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      assert.ok(settled,'Installer state did not render');
      if(!expectBlockedLocal) await call('Runtime.evaluate',{expression:`Array.from(document.querySelectorAll('[data-testid="installer"] button')).find(b=>b.textContent==='Проверить сборку').click()`});
      let checked=false;
      for(let attempt=0;attempt<30;attempt++) {
        const state=await call('Runtime.evaluate',{expression:`window.__TAURI_INTERNALS__.invoke('installer_snapshot')`,returnByValue:true,awaitPromise:true});
        if(expectBlockedLocal) {
          assert.equal(state.result.value.status,'blocked');assert.equal(state.result.value.busy,false);assert.equal(state.result.value.job_id,0);assert.equal(state.result.value.plan,null);
          checked=true;break;
        }
        if(!state.result.value.busy&&state.result.value.status==='unavailable'&&state.result.value.job_id>installer.result.value.started.job_id){checked=true;break;}
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      assert.ok(checked,'UI installer check did not reach the native actor');
      await new Promise(resolve=>setTimeout(resolve,600));
    }
    if(process.argv.includes('--visual')) await capture(`native-${expectBlockedLocal?'blocked-':''}${hash}`);
  }
  let form;
  for(let attempt=0;attempt<20;attempt++) {
    form=await call('Runtime.evaluate',{expression:`({fields:Array.from(document.querySelectorAll('input')).map(input=>input.name), save:document.querySelector('button[type="submit"]')?.disabled})`,returnByValue:true});
    if(form.result.value.fields.length===4)break;
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.deepEqual(form.result.value.fields.sort(),['fullscreen','height','ram_mib','width']);
  assert.equal(form.result.value.save,false);
  await call('Runtime.evaluate',{expression:`window.location.hash='home'`});
  await new Promise(resolve=>setTimeout(resolve,300));
  if(!process.argv.includes('--require-backend')&&publicReply.exceptionDetails) {
    let unavailable=false;
    for(let attempt=0;attempt<60;attempt++) {
      const screen=await call('Runtime.evaluate',{expression:`({text:document.body.innerText,warnings:document.querySelectorAll('.status-panel--warning').length})`,returnByValue:true});
      if(screen.result.value.text.includes('Нет связи')&&screen.result.value.warnings>=2){unavailable=true;break;}
      await new Promise(resolve=>setTimeout(resolve,200));
    }
    assert.ok(unavailable,'Offline service and news errors did not render');
  }
  await capture(expectBlockedLocal?'native-blocked-smoke':'native-smoke');
  if(process.argv.includes('--visual')) {
    const images=await call('Runtime.evaluate',{expression:`Array.from(document.images).map(image=>({src:new URL(image.src).pathname,loaded:image.complete&&image.naturalWidth>0}))`,returnByValue:true});
    assert.ok(images.result.value.every(image=>image.loaded),'Launcher brand image failed to load');
    for(const [width,height] of [[920,620],[600,800]]) {
      await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});
      await new Promise(resolve=>setTimeout(resolve,100));
      const layout=await call('Runtime.evaluate',{expression:`({viewport:innerWidth,content:document.documentElement.scrollWidth})`,returnByValue:true});
      await capture(`native-${expectBlockedLocal?'blocked-':''}home-${width}`);
      assert.ok(layout.result.value.content<=layout.result.value.viewport,`Launcher overflow at ${width}px: ${JSON.stringify(layout.result.value)}`);
    }
    await call('Emulation.clearDeviceMetricsOverride');
  }
  process.stdout.write(`Native WebView2 smoke (${expectBlockedLocal?'blocked negative acceptance':'Ready acceptance'}): UI navigation/public API/settings/installer ACL+${expectBlockedLocal?'refusal':'check'}/error/disabled PLAY passed\n`);
} finally {
  socket.close();
}
