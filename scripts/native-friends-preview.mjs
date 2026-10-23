import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const version=(await readFile(new URL('../apps/launcher/src-tauri/Cargo.toml',import.meta.url),'utf8')).match(/^version\s*=\s*"([^"]+)"/m)[1];
const updateSource=JSON.parse(await readFile(new URL('../apps/launcher/src-tauri/simple-update.json',import.meta.url),'utf8'));
const configured=!!(updateSource.endpoint&&updateSource.public_key);
let target;
for(let i=0;i<100;i++){try{target=(await(await fetch('http://127.0.0.1:19222/json/list')).json()).find(p=>p.url.includes('tauri.localhost'));if(target)break;}catch{/* startup */}await new Promise(r=>setTimeout(r,100));}
assert.ok(target);assert.ok(target.webSocketDebuggerUrl.startsWith('ws://127.0.0.1:19222/'));
const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map();socket.addEventListener('message',({data})=>{const message=JSON.parse(data),item=pending.get(message.id);if(!item)return;pending.delete(message.id);clearTimeout(item.timer);if(message.error)item.reject(new Error('CDP request failed'));else item.resolve(message.result);});
function call(method,params){return new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>reject(new Error('Native preview timed out')),15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const reply=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});assert.equal(reply.exceptionDetails,undefined);return reply.result.value;}
async function wait(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,100));}throw new Error('Native UI did not settle');}
try{
 await call('Emulation.setDeviceMetricsOverride',{width:1180,height:760,deviceScaleFactor:1,mobile:false});
 await wait(`!!document.querySelector('[data-testid="play"]')&&!!window.__TAURI_INTERNALS__`);
 const health=await evaluate(`window.__TAURI_INTERNALS__.invoke('native_health')`);
 const expectedEnvironment=process.env.AOC_API_ORIGIN?`friends-${createHash('sha256').update(process.env.AOC_API_ORIGIN.replace(/\/$/, '')).digest('hex')}`:'development-loopback-v1';
 assert.equal(health.version,version);assert.equal(health.account_environment,expectedEnvironment);
 const game=await evaluate(`window.__TAURI_INTERNALS__.invoke('game_snapshot')`);assert.equal(health.play_available,game.can_launch);if(process.argv.includes('--require-game-config')){assert.equal(game.can_launch,true);assert.equal(game.reason,'READY');}
 if(process.argv.includes('--require-pack-update')){assert.equal(game.can_launch,false);assert.equal(game.reason,'RUNTIME_MOD_UPDATE_REQUIRED');}
 const server=await evaluate(`window.__TAURI_INTERNALS__.invoke('game_server_status')`);
 if(process.argv.includes('--require-game-server')){assert.equal(server.status,'AVAILABLE');assert.ok(Number.isInteger(server.players_online));assert.ok(Number.isInteger(server.players_max));await wait(`document.querySelector('[data-testid="game-server-state"]')?.dataset.state==='AVAILABLE'`);assert.equal(await evaluate(`document.querySelector('[data-testid="game-server-players"]').textContent`),`${server.players_online} / ${server.players_max===0?'∞':server.players_max}`);}
 const account=await evaluate(`window.__TAURI_INTERNALS__.invoke('account_snapshot')`);const canPlay=game.can_launch&&server.status==='AVAILABLE'&&account.state==='SIGNED_IN'&&!account.profile?.password_change_required;
 // The primary button can also offer sign-in, repair or an update while launching is unavailable.
 await wait(`!!document.querySelector('[data-testid="launch-control"]')&&document.querySelector('[data-testid="play"]').textContent.trim().length>0`);
 if(!canPlay)assert.notEqual(await evaluate(`document.querySelector('[data-testid="launch-control"]').dataset.action`),'launch');
 if(process.argv.includes('--launch-game')){
  const started=await evaluate(`window.__TAURI_INTERNALS__.invoke('game_launch').then(x=>JSON.stringify(x),e=>'ERR:'+e)`); console.log('launch result',started);
  await new Promise(resolve=>setTimeout(resolve,30000));
  console.log('launch snapshot',await evaluate(`window.__TAURI_INTERNALS__.invoke('game_snapshot')`));
 }
 const homeScreenshot=await call('Page.captureScreenshot',{format:'png'});await writeFile(new URL('../.tools/friends-normal-home.png',import.meta.url),Buffer.from(homeScreenshot.data,'base64'));
 if(process.argv.includes('--prepare-game')){
  await wait(`!!document.querySelector('[data-testid="game-prepare"]')&&!document.querySelector('[data-testid="game-prepare"]').disabled`);
  await evaluate(`document.querySelector('[data-testid="game-prepare"]').click()`);
  await wait(`document.querySelector('[data-testid="game-prepare"]').disabled`);
  const concurrent=await evaluate(`window.__TAURI_INTERNALS__.invoke('game_prepare').then(()=>null,error=>String(error))`);assert.equal(concurrent,'GAME_ALREADY_RUNNING');
  let preparation;
  for(let i=0;i<1200;i++){
   preparation=await evaluate(`window.__TAURI_INTERNALS__.invoke('game_preparation_snapshot')`);
   if(preparation.state!=='RUNNING')break;
   await new Promise(resolve=>setTimeout(resolve,250));
  }
  assert.equal(preparation.state,'READY',JSON.stringify(preparation));assert.equal(preparation.downloaded,0);
  assert.equal((await evaluate(`window.__TAURI_INTERNALS__.invoke('game_snapshot')`)).can_launch,true);
  assert.equal((await evaluate(`window.__TAURI_INTERNALS__.invoke('game_server_status')`)).status,'AVAILABLE');
  await wait(`document.querySelector('[data-testid="game-preparation"]').innerText.includes('Сборка готова')`);
  await wait(`document.querySelector('[data-testid="game-server-state"]').dataset.state==='AVAILABLE'`);
  const image=await call('Page.captureScreenshot',{format:'png'});await writeFile(new URL('../.tools/friends-normal-preparation.png',import.meta.url),Buffer.from(image.data,'base64'));
  console.log('PASS: real native preparation button, progress, concurrent action rejected, READY, no redownloads, launch runtime available.');
 }
  await evaluate(`location.hash='#settings/updates'`);
  await wait(`!!document.querySelector('[data-testid="simple-update"]')&&document.body.innerText.includes('Текущая версия: ${version}')`);
 const updater=await evaluate(`window.__TAURI_INTERNALS__.invoke('simple_update_snapshot')`);assert.equal(updater.source_configured,configured);if(!configured){assert.equal(updater.status,'unavailable');assert.equal(updater.reason,'NOT_CONFIGURED');}else if(!updater.installed){assert.equal(updater.status,'unavailable');assert.equal(updater.reason,'NOT_INSTALLED');}
 assert.equal(await evaluate(`!!document.querySelector('[data-testid="installer"]')||!!document.querySelector('[data-testid="launcher-update"]')`),false);
  for(const [screen,title] of [['clans','Найди свой клан'],['news','Новости AnimeOnCraft']]){await evaluate(`location.hash='#'+${JSON.stringify(screen)}`);await wait(`document.querySelector('h2.page-heading')?.textContent===${JSON.stringify(title)}`);}
 if(process.argv.includes('--require-backend'))for(const resource of ['ready','clans','news']){const reply=await evaluate(`window.__TAURI_INTERNALS__.invoke('read_public_resource',{input:{resource:${JSON.stringify(resource)}}})`);assert.equal(reply.status,200);}
 if(process.argv.includes('--gameplay-check')){
  const clans=await evaluate(`window.__TAURI_INTERNALS__.invoke('read_public_resource',{input:{resource:'clans'}})`);
  assert.equal(clans.status,200);const clan=clans.body.items[0];assert.ok(clan);
  for(const resource of ['clan_progression','clan_competitions','clan_wars']){
   const reply=await evaluate(`window.__TAURI_INTERNALS__.invoke('read_public_resource',{input:{resource:${JSON.stringify(resource)},key:${JSON.stringify(clan.id)}}})`);
   assert.equal(reply.status,200,resource);
  }
  await evaluate(`location.hash='#clans/'+${JSON.stringify(clan.id)}`);
  await wait(`document.body.innerText.includes('Развитие клана')&&document.querySelectorAll('progress').length===3&&!!document.querySelector('[aria-label="Войны клана"]')`);
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`),true);
  await writeFile(new URL('../.tools/gameplay-clan-live.png',import.meta.url),Buffer.from((await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true})).data,'base64'));
  for(const width of [1180,920]){
   await call('Emulation.setDeviceMetricsOverride',{width,height:760,deviceScaleFactor:1,mobile:false});
   assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`),true,`gameplay overflow at ${width}`);
   await evaluate(`document.querySelector('[aria-label="Войны клана"]').scrollIntoView({block:'end'})`);
   await writeFile(new URL(`../.tools/gameplay-clan-live-${width}.png`,import.meta.url),Buffer.from((await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true})).data,'base64'));
  }
  console.log('PASS: real native gameplay routes and live clan layout at 1180/920. No backend records or account state changed.');
 }
 if(process.argv.includes('--require-owner-clans')){
  assert.equal(account.state,'SIGNED_IN');assert.equal(account.profile.system_role,'OWNER');
  await evaluate(`location.hash='#admin'`);await wait(`!!document.querySelector('[data-testid="owner-clans"] select option')`);
  assert.equal(await evaluate(`document.querySelectorAll('[data-testid="owner-clans"] select option').length`),5);
  assert.equal(await evaluate(`!!document.querySelector('[data-testid="owner-clan-join"]')`),true);
  const ownerClans=await evaluate(`window.__TAURI_INTERNALS__.invoke('player_read',{input:{resource:'owner_clans'}})`);
  const queue=await evaluate(`window.__TAURI_INTERNALS__.invoke('player_read',{input:{resource:'owner_applications',clan_id:${JSON.stringify(ownerClans.items[0].id)},cursor:null}})`);
  assert.ok(Array.isArray(queue.items));
  await evaluate(`location.hash='#clans/'+${JSON.stringify(ownerClans.items[0].id)}`);await wait(`!!document.querySelector('textarea[name="message"]')`);
  assert.equal(await evaluate(`document.querySelector('textarea[name="message"]').required`),false);
  assert.equal(await evaluate(`document.querySelector('textarea[name="message"]').minLength`),-1);
  console.log('PASS: current OWNER sees five clans, self-join button and native application queue; about field optional. Read-only: no memberships or applications changed.');
 }
 if(process.argv.includes('--visual-redesign')) {
  for(const width of [1180,1660,640]) {
   await call('Emulation.setDeviceMetricsOverride',{width,height:960,deviceScaleFactor:1,mobile:false});
   for(const screen of ['home','profile']) {
    await evaluate(`location.hash='#${screen}'`);
    await wait(screen==='home'?`!!document.querySelector('[data-testid="play"]')`:`!!document.querySelector('.account-panel')`);
    await new Promise(resolve=>setTimeout(resolve,1200));
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`),true,`${screen} horizontal overflow at ${width}`);
    if(screen==='home' && account.state==='SIGNED_IN') {
     const savedSkin=await evaluate(`window.__TAURI_INTERNALS__.invoke('skin_read')`);
     if(savedSkin.skin) {
      await wait(`!!document.querySelector('[data-testid="character-model"]')`);
      assert.equal(await evaluate(`document.querySelectorAll('.skin-face').length`),72);
      assert.equal(await evaluate(`document.querySelector('[data-testid="character-model"]').dataset.model`),savedSkin.skin.model);
      assert.equal(await evaluate(`!!document.querySelector('input[aria-label="Поворот персонажа"]')`),true);
     }
    }
    const shot=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});
    await writeFile(new URL(`../.tools/redesign-${screen}-${width}.png`,import.meta.url),Buffer.from(shot.data,'base64'));
   }
  }
  console.log('PASS: redesign home/profile at 1180,1660,640; no horizontal overflow.');
 }
 await evaluate(`location.hash='#settings'`);await wait(`!!document.querySelector('[data-testid="simple-update"]')`);
 const screenshot=await call('Page.captureScreenshot',{format:'png'});await writeFile(new URL('../.tools/friends-normal-settings.png',import.meta.url),Buffer.from(screenshot.data,'base64'));
 console.log(`PASS: local runtime configured=${game.can_launch}; server=${server.status}, players=${server.players_online}/${server.players_max===0?'∞':server.players_max}; ordinary native ${version}, account environment=${health.account_environment}, home/clans/news/settings, updater source configured=${configured}, no experimental installer UI, PLAY enabled=${canPlay}.`);
}finally{socket.close();}
