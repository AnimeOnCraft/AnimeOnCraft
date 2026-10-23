import assert from 'node:assert/strict';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once, EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import net from 'node:net';
import { copyFileSync, cpSync, createWriteStream, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, renameSync, symlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const base = path.join(root, '.tools/modpack-mvp');
const runtime = path.join(root, '.tools/game-admission-smoke');
const serverDir = path.join(runtime, 'server'), clientDir = path.join(runtime, 'client');
assert.equal(process.argv.length, 2, 'Fixture credentials must arrive through stdin, not argv/files');
let input = ''; for await (const bytes of process.stdin) { input += bytes; assert.ok(input.length < 2048); }
const fixture = JSON.parse(input);
const apiPort=fixture.native_launcher?39431:39432;
assert.equal(fixture.game_name, 'aocgameprobe');
assert.match(fixture.server_key, /^[a-f0-9]{64}$/); assert.match(fixture.access_token, /^aa_[A-Za-z0-9_-]{43}$/);
const mod = path.join(root, 'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
assert.ok(existsSync(mod), 'Build the admission mod first');
assert.match(readFileSync(path.join(base, 'server/eula.txt'), 'utf8'), /^eula=true\s*$/m, 'Existing owner EULA agreement required');
await new Promise((resolve,reject) => {
  const socket = net.connect(25566,'127.0.0.1');
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25566 already in use; existing server preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
});
for (const dir of [serverDir,clientDir,path.join(runtime,'tmp'),path.join(runtime,'disabled')]) mkdirSync(dir,{recursive:true});
const libraries = path.join(serverDir,'libraries');
if (!existsSync(libraries)) symlinkSync(path.join(base,'server/libraries'),libraries,'junction');
else assert.ok(lstatSync(libraries).isSymbolicLink(),'Expected own libraries junction');
for (const [source,target] of [['server',serverDir],['client',clientDir]]) {
  mkdirSync(path.join(target,'mods'),{recursive:true});
  for (const entry of readdirSync(path.join(base,source,'mods'))) if (entry.endsWith('.jar')) copyFileSync(path.join(base,source,'mods',entry),path.join(target,'mods',entry));
  copyFileSync(mod,path.join(target,'mods',path.basename(mod)));
}
if(existsSync(path.join(clientDir,'screenshots/beta-cape-acceptance.png')))renameSync(path.join(clientDir,'screenshots/beta-cape-acceptance.png'),path.join(clientDir,`screenshots/beta-cape-previous-${Date.now()}.png`));
copyFileSync(path.join(base,'client/options.txt'),path.join(clientDir,'options.txt'));
const worldName=fixture.native_launcher?`clan-skin-world-${Date.now()}`:'admission-smoke-world';
cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(serverDir,`${worldName}/datapacks/animeoncraft-compat`),{recursive:true});
writeFileSync(path.join(serverDir,'eula.txt'),'eula=true\n');
writeFileSync(path.join(serverDir,'server.properties'),[
  'server-ip=127.0.0.1','server-port=25566','online-mode=false','enforce-secure-profile=false',
  'white-list=false','enforce-whitelist=false','max-players=15','enable-rcon=false','enable-query=false',
  'view-distance=4','simulation-distance=4','spawn-protection=0',`level-name=${worldName}`,'pvp=false',
].join('\n')+'\n');

const bridgeKey = randomBytes(32).toString('hex');
const secrets = [fixture.access_token,fixture.server_key,bridgeKey];
const redact = value => secrets.reduce((text,secret)=>text.replaceAll(secret,'[REDACTED]'),value);
let mode = 'missing-mod', bridgeRequests = 0;
const bridge = createServer(async(req,res)=>{
  try {
    const supplied = req.headers.authorization ?? '';
    const expected = `Bearer ${bridgeKey}`;
    if (req.method!=='POST' || req.url!=='/ticket' || req.headers.origin || req.headers.cookie || supplied.length!==expected.length || !timingSafeEqual(Buffer.from(supplied),Buffer.from(expected))) {res.writeHead(401).end();return;}
    let text=''; for await(const bytes of req){text+=bytes;assert.ok(text.length<=512);}
    const body=JSON.parse(text); assert.deepEqual(Object.keys(body).sort(),['connection_id','server_id']);
    assert.equal(body.server_id,fixture.server_id);assert.match(body.connection_id,/^[a-f0-9-]{36}$/);assert.ok(++bridgeRequests<=4);
    let ticket='gt_'+'z'.repeat(43);
    if(mode==='valid') {
      const response=await fetch(`http://127.0.0.1:${apiPort}/api/v1/game/tickets`,{method:'POST',headers:{'content-type':'application/json','x-aoc-client':'launcher',authorization:`Bearer ${fixture.access_token}`},body:'{}',signal:AbortSignal.timeout(5000)});
      assert.equal(response.status,200);const issued=await response.json();
      assert.equal(issued.server_id,fixture.server_id);assert.equal(issued.game_name,fixture.game_name);assert.equal(issued.minecraft_uuid,fixture.minecraft_uuid);
      ticket=issued.ticket;secrets.push(ticket);
    }
    res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'}).end(JSON.stringify({ticket}));
  } catch {res.writeHead(503).end();}
});
await new Promise(resolve=>bridge.listen(0,'127.0.0.1',resolve));
const bridgePort=bridge.address().port;
const serverOutput=createWriteStream(path.join(runtime,'server.log'));
const events=new EventEmitter();let serverText='',serverExited=false;
const server=spawn('java',['-Xms512m','-Xmx2048m','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Dterminal.jline=false','-Dterminal.ansi=false',`-Djava.io.tmpdir=${path.join(runtime,'tmp')}`,'@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{
  cwd:serverDir,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,AOC_GAME_REDEEM_URL:`http://127.0.0.1:${apiPort}/api/v1/game/admission/redeem`,AOC_GAME_SERVER_ID:fixture.server_id,AOC_GAME_SERVER_KEY:fixture.server_key},
});
const serverExit=once(server,'exit');
for(const stream of [server.stdout,server.stderr])stream.on('data',bytes=>{const text=redact(bytes.toString());serverText+=text;serverOutput.write(text);events.emit('output');});
server.once('exit',()=>{serverExited=true;events.emit('output');});
server.once('error',()=>{serverExited=true;events.emit('output');});
function wait(pattern,start=0,timeout=180000){return new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>finish(new Error(`Admission smoke timeout: ${pattern}; see server.log`)),timeout);
  function finish(error){clearTimeout(timer);events.off('output',check);if(error)reject(error);else resolve();}
  function check(){if(pattern.test(serverText.slice(start)))finish();else if(serverExited)finish(new Error('Own Minecraft server exited; see server.log'));}
  events.on('output',check);check();
});}
let activeClient;
const clientMod=path.join(clientDir,'mods',path.basename(mod)),disabledMod=path.join(runtime,'disabled',path.basename(mod));
let passed=false,capeRendered=false;
try {
  await wait(/Done \([^\n]*\)! For help/);console.log('Admission fixture: dedicated NeoForge server ready on 127.0.0.1:25566');
  if(fixture.native_launcher){
   const start=serverText.length;server.stdin.write('seed\n');await wait(/Seed: \[-?\d+\]/,start);
   const seed=/Seed: \[(-?\d+)\]/.exec(serverText.slice(start))[1];
   mkdirSync(path.join(serverDir,'config'),{recursive:true});
   writeFileSync(path.join(serverDir,'config/animeoncraft-clan-spawns.json'),JSON.stringify({format_version:1,seed,clans:{YAKUZA:{x:128,z:128},SCAM:{x:256,z:256},NYA:{x:384,z:384},AKUMA:{x:512,z:512},ZXC:{x:640,z:640}},territories:{width_blocks:64,colors:{SCAM:'E84A5F',NYA:'F47DCB',AKUMA:'A970FF',ZXC:'4DA8FF',YAKUZA:'FFB347'}}}));
   const loaded=serverText.length;server.stdin.write('forceload add 112 112 175 175\n');
   for(let i=0;i<120&&!serverText.slice(loaded).includes('AOC fixture chunks ready');i++){
    server.stdin.write('execute if loaded 128 80 128 if loaded 160 80 160 run say AOC fixture chunks ready\n');await new Promise(r=>setTimeout(r,500));
   }
   assert.ok(serverText.slice(loaded).includes('AOC fixture chunks ready'),'Own test chunks did not load');
   server.stdin.write('fill 120 79 120 168 79 168 stone\nfill 120 80 120 168 90 168 air\ngamerule doImmediateRespawn true\ngamerule keepInventory true\n');
   for(const [from,to] of [[120,136],[152,168]])for(const y of [80,160,240])server.stdin.write(`fill ${from} ${y} ${from} ${to} ${y+79} ${to} air\n`);
  }
  for(const current of ['missing-mod','invalid-ticket','valid']) {
    mode=current;
    if(current==='missing-mod') {assert.equal(path.dirname(clientMod),path.join(runtime,'client/mods'));assert.equal(path.dirname(disabledMod),path.join(runtime,'disabled'));renameSync(clientMod,disabledMod);}
    else if(!existsSync(clientMod))renameSync(disabledMod,clientMod);
    const start=serverText.length;
    if(current==='valid'&&fixture.native_launcher){
      const {nativeGame}=await import('./native-game-ui.mjs');
      const native=await nativeGame(fixture);
      try{
        await wait(/aocgameprobe joined the game/,start);
        const until=Date.now()+120000;
        while(!/\[EMI\] Reloaded EMI/.test(readFileSync(path.join(clientDir,'logs/latest.log'),'utf8'))){assert.ok(Date.now()<until,'Native Minecraft EMI timeout');await new Promise(resolve=>setTimeout(resolve,500));}
        await wait(/AOC clan first spawn: aocgameprobe clan=YAKUZA pos=128, 80, 128/,start);
        const skinUntil=Date.now()+30000;
        while(!/AOC skin rendered:.*model=SLIM/.test(readFileSync(path.join(clientDir,'logs/latest.log'),'utf8'))){assert.ok(Date.now()<skinUntil,'Custom skin rendering did not run');await new Promise(r=>setTimeout(r,250));}
        const mapUntil=Date.now()+30000;
        while(true){
          const text=readFileSync(path.join(clientDir,'logs/latest.log'),'utf8');
          const mapped=['SCAM','NYA','AKUMA','ZXC','YAKUZA'].every(clan=>text.includes(`AOC clan territory mapped: clan=${clan} `));
          if(mapped&&/AOC clan map available: count=5 minimap_active=true/.test(text))break;
          assert.ok(Date.now()<mapUntil,'JourneyMap did not register the server territories with an active minimap');await new Promise(r=>setTimeout(r,250));
        }
        await wait(/AOC clan territories sent: player=aocgameprobe count=5/,start);
        console.log('JourneyMap: five server territories registered in the real client API; minimap active, dimension and zoom accepted');
        await wait(/AOC world join confirmed: player=aocgameprobe cape=beta/,start);
        const capeUntil=Date.now()+45000;
        while(true){
          const text=readFileSync(path.join(clientDir,'logs/latest.log'),'utf8');
          if(/AOC beta cape layer rendered:/.test(text)&&/AOC cape screenshot saved/.test(text)&&existsSync(path.join(clientDir,'screenshots/beta-cape-acceptance.png')))break;
          assert.ok(Date.now()<capeUntil,'Beta cape was not rendered and captured by Minecraft');await new Promise(r=>setTimeout(r,250));
        }
        copyFileSync(path.join(clientDir,'screenshots/beta-cape-acceptance.png'),path.join(root,'.tools/beta-cape-minecraft.png'));
        capeRendered=true;await native.checkCape();
        console.log('Beta cape: confirmed actual world join and actual Minecraft cape-layer render with screenshot');
        let respawn=serverText.length;server.stdin.write('kill aocgameprobe\n');await wait(/AOC clan respawn: aocgameprobe pos=128, 80, 128/,respawn);
        respawn=serverText.length;server.stdin.write('spawnpoint aocgameprobe 160 80 160\nkill aocgameprobe\n');await wait(/aocgameprobe was killed/,respawn);await new Promise(r=>setTimeout(r,1500));
        server.stdin.write('data get entity aocgameprobe Pos\n');await wait(/aocgameprobe has the following entity data: \[160\.5d, 80\.0d, 160\.5d\]/,respawn);
        assert.ok(!/AOC clan respawn/.test(serverText.slice(respawn)),'Personal respawn must override clan');
        console.log('Clan/skin: authenticated YAKUZA first spawn, no-bed death returns to clan, personal spawn preserved, uploaded slim PNG rendered by Minecraft');
        await native.screenshot();
        server.stdin.write('kick aocgameprobe Native PLAY smoke finished\n');
        await wait(/aocgameprobe lost connection/,start);
        console.log('Native PLAY: real launcher → Java → native broker → backend → server; entered world and EMI completed');
      }finally{await native.close();}
      continue;
    }
    process.env.AOC_GAME_TARGET_PORT='25566';process.env.AOC_GAME_BRIDGE_PORT=String(bridgePort);process.env.AOC_GAME_BRIDGE_KEY=bridgeKey;
    // No server credential or backend credentials are inherited by the client process.
    assert.equal(process.env.AOC_GAME_SERVER_KEY,undefined);
    process.argv=[process.execPath,path.join(root,'scripts/start-modpack-client-demo.mjs'),'--admission-smoke','--smoke-seconds=180'];
    const {gameProcess}=await import(pathToFileURL(path.join(root,'scripts/start-modpack-client-demo.mjs')).href+`?probe=${current}`);
    activeClient=gameProcess;const clientExit=once(gameProcess,'exit');
    const output=createWriteStream(path.join(runtime,`client-${current}.log`));let clientText='';
    for(const stream of [gameProcess.stdout,gameProcess.stderr])stream.on('data',bytes=>{const text=redact(bytes.toString());clientText+=text;output.write(text);events.emit('output');});
    try {
      if(current==='valid') {
        await wait(/aocgameprobe joined the game/,start);
        await new Promise((resolve,reject)=>{
          const timer=setTimeout(()=>finish(new Error('Client EMI indexing timed out')),120000);
          function finish(error){clearTimeout(timer);events.off('output',check);if(error)reject(error);else resolve();}
          function check(){if(/\[EMI\] Reloaded EMI/.test(clientText))finish();}
          events.on('output',check);check();
        });
        server.stdin.write('kick aocgameprobe Admission smoke finished\n');
        await wait(/aocgameprobe lost connection/,start);
        console.log('Admission fixture: actual account code accepted; client entered world and EMI completed');
      } else {
        await wait(/name=aocgameprobe[^\n]*lost connection|aocgameprobe lost connection/,start);
        assert.ok(!/aocgameprobe joined the game/.test(serverText.slice(start)));
        console.log(`Admission fixture: ${current} rejected before joining world`);
      }
    } finally {gameProcess.kill();await clientExit;activeClient=undefined;output.end();}
  }
  assert.equal(bridgeRequests,fixture.native_launcher?1:2);passed=true;
} finally {
  if(activeClient){const exit=once(activeClient,'exit');activeClient.kill();await exit;}
  if(!existsSync(clientMod)&&existsSync(disabledMod))renameSync(disabledMod,clientMod);
  if(!serverExited){server.stdin.write('stop\n');const timer=setTimeout(()=>server.kill(),30000);await serverExit;clearTimeout(timer);}
  await new Promise(resolve=>bridge.close(resolve));serverOutput.end();
  for(const name of ['AOC_GAME_TARGET_PORT','AOC_GAME_BRIDGE_PORT','AOC_GAME_BRIDGE_KEY'])delete process.env[name];
  writeFileSync(path.join(root,'.tools/game-admission-smoke-result.json'),JSON.stringify({passed,loopback_only:true,missing_client_mod_rejected:passed,invalid_ticket_rejected:passed,account_fixture_entered_world:passed,recipe_indexing_completed:passed,ordinary_play_connected:passed&&!!fixture.native_launcher,clan_map_registered:passed&&!!fixture.native_launcher,beta_cape_rendered:passed&&capeRendered,checked_at:new Date().toISOString()},null,2)+'\n');
  process.exitCode=passed?0:1;
}
