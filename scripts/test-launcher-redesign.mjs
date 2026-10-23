// Browser review of the launcher UI with a fixture IPC (no real accounts). Windows + Edge required.
// node scripts/test-launcher-redesign.mjs  → screenshots and result.json in .tools/redesign-review-0.7.38/
import assert from 'node:assert/strict';
import {build,preview} from '../apps/launcher/node_modules/vite/dist/node/index.js';
import react from '../apps/launcher/node_modules/@vitejs/plugin-react/dist/index.js';
import {spawn} from 'node:child_process';
import {mkdirSync,writeFileSync,rmSync} from 'node:fs';
import path from 'node:path';
const root=process.cwd(),out=path.join(root,'.tools/redesign-review-0.7.38');rmSync(path.join(out,'dist'),{recursive:true,force:true});mkdirSync(out,{recursive:true});
await build({configFile:false,logLevel:'warn',root:path.join(root,'apps/launcher'),plugins:[react()],resolve:{alias:{'@tauri-apps/api/core':path.join(root,'scripts/fixtures/redesign-core.mjs')}},build:{outDir:path.join(out,'dist'),emptyOutDir:true}});
const server=await preview({configFile:false,root:path.join(root,'apps/launcher'),build:{outDir:path.join(out,'dist')},preview:{host:'127.0.0.1',port:1436,strictPort:true}});
const edge=spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',['--headless=new','--disable-gpu','--disable-background-timer-throttling','--no-first-run','--remote-debugging-port=19336',`--user-data-dir=${path.join(out,'edge-profile')}`,'about:blank'],{windowsHide:true,stdio:'ignore'});
let socket;const pending=new Map();let sequence=0;const checks=[];
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method));},15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
async function wait(expression,label=expression){for(let i=0;i<80;i++){if(await evaluate(`!!(${expression})`))return;await delay(150);}throw Error('UI wait failed '+label);}
async function click(text,scope='button'){await evaluate(`(()=>{const b=[...document.querySelectorAll(${JSON.stringify(scope)})].find(b=>b.textContent.trim()===${JSON.stringify(text)}&&b.getClientRects().length);if(!b)throw Error('Missing: '+${JSON.stringify(text)});b.click();})()`);await delay(250);}
async function route(hash){await evaluate(`location.hash=${JSON.stringify(hash)}`);await delay(450);await wait(`!document.querySelector('main.startup-fallback')`);}
async function shot(name){await delay(350);const r=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});writeFileSync(path.join(out,name+'.png'),Buffer.from(r.data,'base64'));}
async function noOverflow(label){assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&[...document.querySelectorAll('.product-main')].every(e=>e.scrollWidth<=e.clientWidth+1)`),true,'Horizontal overflow '+label);}
async function size(width,height){await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});await delay(200);}
async function state(values){await evaluate(`(()=>{for(const [k,v] of Object.entries(${JSON.stringify(values)}))v===null?localStorage.removeItem(k):localStorage.setItem(k,v);})()`);await call('Page.reload');await delay(600);}
const playText=`document.querySelector('[data-testid="play"]')?.textContent.trim()`;
try{
 let pages;for(let i=0;i<60;i++){try{pages=await(await fetch('http://127.0.0.1:19336/json')).json();if(pages.length)break;}catch{/* Edge is still starting. */}await delay(200);}
 socket=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise(r=>socket.addEventListener('open',r,{once:true}));socket.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.method==='Runtime.exceptionThrown')console.error(JSON.stringify(m.params));const p=pending.get(m.id);if(p){clearTimeout(p.timer);pending.delete(m.id);if(m.error)p.reject(Error(m.error.message));else p.resolve(m.result);}});
 await call('Page.enable');await call('Runtime.enable');await size(1180,760);
 await call('Page.navigate',{url:'http://127.0.0.1:1436/'});await state({fixtureRole:'OWNER',fixtureUpdate:null,fixtureGame:null});
 // Home: PLAY ready, repair, launch.
 await wait(`document.querySelector('[data-testid="play"]')&&!document.querySelector('[data-testid="play"]').disabled`,'play enabled');
 assert.equal(await evaluate(playText),'Играть');await wait(`document.querySelectorAll('.clan-mini').length===5`);await shot('01-home');
 await evaluate(`document.querySelector('[data-testid="game-prepare"]').click()`);await wait(`window.fixtureCalls.some(c=>c.command==='game_prepare')`);
 await evaluate(`document.querySelector('[data-testid="play"]').click()`);await wait(`window.fixtureCalls.some(c=>c.command==='game_launch')`);checks.push('home: PLAY launches, «Проверить сборку» runs preparation');
 // Mandatory launcher update replaces PLAY.
 await state({fixtureUpdate:'available'});await wait(`${playText}==='Обновление'`,'update button');await shot('02-home-update');
 await evaluate(`document.querySelector('[data-testid="play"]').click()`);await wait(`window.fixtureCalls.some(c=>c.command==='simple_update_action'&&c.args.input.action==='install'&&c.args.input.version==='0.7.39')`);
 await state({fixtureUpdate:'downloading'});await wait(`${playText}==='Отменить загрузку'`,'download state');await shot('03-home-downloading');
 await state({fixtureUpdate:null,fixtureGame:'mod_update'});await wait(`${playText}==='Обновить сборку'`,'mod update');await shot('04-home-mod-update');
 await evaluate(`document.querySelector('[data-testid="play"]').click()`);await wait(`window.fixtureCalls.some(c=>c.command==='game_prepare')`);
 await state({fixtureGame:'install'});await wait(`${playText}==='Установить сборку'`,'install');
 await state({fixtureGame:null});await wait(`${playText}==='Играть'`);checks.push('PLAY → Обновление (install 0.7.39) → загрузка → Обновить/Установить сборку → Играть');
 // Clans.
 await route('clans');await wait(`document.querySelectorAll('.clan-catalog-card').length===5`);await wait(`document.querySelector('[data-testid="player-panel"]')`);await shot('05-clans');
 await route('clans/00000000-0000-4000-8000-000000000001');await wait(`document.querySelector('.clan-progression')`);await wait(`document.body.textContent.includes('TestPlayer')`,'owner queue in clan card');
 assert.equal(await evaluate(`document.querySelector('textarea[name="message"]').required`),false);await shot('06-clan-overview');
 await click('Принять');await wait(`window.fixtureCalls.some(c=>c.command==='player_prepare_action'&&c.args.input.action==='owner_approve')`);checks.push('clan card: owner approves inside the card, «О себе» optional');
 await click('Состав');await wait(`document.querySelector('.clan-roster-member')`);await shot('07-clan-roster');
 await click('События и войны');await wait(`document.querySelector('.achievement-card')`);await shot('08-clan-events');
 await click('Заявки');await wait(`document.querySelector('[data-testid="owner-clans"]')`);await shot('09-clan-applications');
 // Profile.
 await route('profile');await wait(`document.querySelector('[data-testid="skin-file"]')`);await wait(`document.querySelector('[data-testid="character-model"]')`);
 assert.equal(await evaluate(`!!document.querySelector('[data-testid="password-change"]')`),false);await shot('10-profile');
 await click('Плащ','[role=tab]');await wait(`document.querySelector('[data-testid="cape-beta"]')`);await shot('11-profile-cape');
 await evaluate(`document.querySelector('[data-testid="cape-remove"]').click()`);await wait(`document.querySelector('[data-testid="cape-state"]').textContent.includes('не надет')`);checks.push('profile: skin, model, cape wardrobe and removal');
 // News.
 await route('news');await wait(`document.querySelectorAll('.news-card').length===2`);await shot('12-news');
 await route('news/release-0728');await wait(`document.querySelector('.article-body h4')`);await shot('13-article');
 // World map (stage 2).
 await route('map');await wait(`document.querySelectorAll('[data-testid="world-map"] .map-region polygon').length===5&&document.querySelectorAll('[data-point]').length===7`,'world map regions and points');
 assert.equal(await evaluate(`[...document.querySelectorAll('.map-region-label')].map(e=>e.textContent).join(',')`),'SCAM,NYA,AKUMA,ZXC,YAKUZA');
 assert.equal(await evaluate(`getComputedStyle(document.querySelector('.map-region polygon')).fill`),'rgb(232, 74, 95)','region colour comes from the clan registry');
 await shot('27-map');
 await evaluate(`document.querySelector('[data-point="RESOURCE_SITE"]').dispatchEvent(new MouseEvent('click',{bubbles:true}))`);
 await wait(`(document.querySelector('[data-testid="map-point-card"]')?.textContent??'').includes('Аметистовая расщелина')&&document.querySelector('[data-testid="map-point-card"]').textContent.includes('Магия · T3')`,'resource site card');
 await shot('28-map-site');
 await click('Ресурсы','.map-layers button');await wait(`!document.querySelector('[data-point="RESOURCE_SITE"]')`,'resources layer toggles off');
 await evaluate(`(()=>{const e=document.querySelector('.map-search input');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,'Замок ZXC');e.dispatchEvent(new Event('input',{bubbles:true}));})()`);
 await wait(`document.querySelector('.map-results button')`);await evaluate(`document.querySelector('.map-results button').click()`);
 await wait(`(document.querySelector('[data-testid="map-point-card"]')?.textContent??'').includes('Замок ZXC')`,'search focuses a point');
 checks.push('map: five registry-coloured territories, capitals, resource site card with owner/state/timers, layer toggle, search');
 // Settings.
 await route('settings');await wait(`document.querySelector('[name="ram_mib"]')`);await evaluate(`(()=>{const e=document.querySelector('[name="ram_mib"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,'6144');e.dispatchEvent(new Event('input',{bubbles:true}));})()`);
 await click('Сохранить настройки');await wait(`window.fixtureCalls.some(c=>c.command==='save_launcher_settings'&&c.args.input.ram_mib===6144)`);await shot('14-settings-game');checks.push('settings: RAM saved through IPC; left menu shows only the chosen section');
 await route('settings/account');await wait(`document.querySelector('[data-testid="password-change"]').getClientRects().length>0`);assert.equal(await evaluate(`document.querySelector('[name="ram_mib"]').getClientRects().length`),0,'Game section must be hidden on the account section');await shot('15-settings-account');
 await route('settings/sessions');await wait(`document.body.textContent.includes('Активные сессии')`);await shot('16-settings-sessions');
 await route('settings/updates');await wait(`document.querySelector('[data-testid="simple-update"]').getClientRects().length>0`);assert.equal(await evaluate(`document.querySelector('[data-testid="password-change"]').getClientRects().length+document.querySelector('[name="ram_mib"]').getClientRects().length`),0,'Only the chosen settings section is visible');await shot('17-settings-updates');
 await click('Аккаунт','.settings-nav button');await wait(`document.querySelector('[data-testid="password-change"]').getClientRects().length>0&&document.querySelector('[data-testid="simple-update"]').getClientRects().length===0`,'settings nav click switches section');await route('settings/diagnostics');await wait(`document.querySelectorAll('.readiness-row').length>=5`);await shot('18-settings-diagnostics');
 // Admin tab kept for OWNER.
 await route('admin');await wait(`document.querySelector('[data-testid="owner-clans"] select')`);await wait(`document.body.textContent.includes('TestPlayer')`);await shot('19-admin');
 // Header menu.
 await route('home');await evaluate(`document.querySelector('.account-chip').click()`);await wait(`document.querySelector('.menu-popover')`);await shot('20-account-menu');
 // PLAYER: no admin.
 await state({fixtureRole:'PLAYER'});await route('admin');await wait(`document.body.textContent.includes('Доступ только для создателя')`);assert.equal(await evaluate(`!!document.querySelector('.admin-nav')`),false);
 assert.equal(await evaluate(`window.fixtureCalls.some(c=>c.command==='player_read'&&c.args.input.resource.startsWith('owner_'))`),false);
 await route('clans/00000000-0000-4000-8000-000000000001');await wait(`document.querySelector('.clan-progression')`);assert.equal(await evaluate(`!!document.querySelector('[data-testid="owner-clans"]')`),false);checks.push('PLAYER: no admin tab, no owner block, no owner IPC');
 // Signed out.
 await state({fixtureRole:'OUT'});await route('profile');await wait(`document.querySelector('input[name="password"]')`);await shot('21-login');
 await click('Регистрация','[role=tab]');await wait(`document.querySelector('input[name="email"]')`);await shot('22-register');
 await click('Вход','[role=tab]');await click('Забыли пароль?');await wait(`document.body.textContent.includes('Восстановление через владельца')`);
 await route('home');await wait(`${playText}==='Войти'`,'sign-in button');await shot('23-home-signed-out');checks.push('signed out: login/register/recovery; PLAY becomes «Войти»');
 // Window sizes: default 1180×760 and minimum 920×620.
 await state({fixtureRole:'OWNER'});
 for(const [w,h] of [[1180,760],[920,620]]){await size(w,h);for(const name of ['home','clans','clans/00000000-0000-4000-8000-000000000001','map','profile','news','settings','admin']){await route(name);await noOverflow(name+' '+w);}await route('home');await shot(`24-home-${w}`);await route('clans/00000000-0000-4000-8000-000000000001');await shot(`25-clan-${w}`);await route('profile');await shot(`26-profile-${w}`);await route('map');await shot(`29-map-${w}`);}
 checks.push('no horizontal overflow at 1180×760 and 920×620');
 writeFileSync(path.join(out,'result.json'),JSON.stringify({passed:true,checks,fixture_only:true,production_accounts_modified:false},null,2));console.log('Redesign browser checks passed:\n- '+checks.join('\n- '));
}catch(e){console.error(await evaluate('({text:document.body.innerText.slice(0,1500),calls:window.fixtureCalls?.slice(-10)})').catch(()=>null));await shot('failure').catch(()=>{});throw e;}
finally{if(socket?.readyState===1){await call('Browser.close').catch(()=>{});socket.close();}edge.kill();await new Promise(r=>server.httpServer.close(r));}
