import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {jarEntry} from './jar-entry.mjs';
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';

const root=fileURLToPath(new URL('../',import.meta.url)),base=path.join(root,'.tools/modpack-mvp/server');
assert.equal(process.platform,'win32');assert.equal(process.argv.length,2);
assert.match(fs.readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
await new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);socket.once('connect',()=>{socket.destroy();reject(Error('Port 25569 occupied; existing process preserved'));});socket.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));socket.once('timeout',()=>{socket.destroy();reject(Error('Cannot check fixture port'));});});
const stamp=Date.now(),world='aoc-clan-market-probe-'+stamp,directory=path.join(root,'.tools/clan-market-probe-'+stamp);
fs.mkdirSync(path.join(directory,'mods'),{recursive:true});fs.mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
fs.symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
const profile=JSON.parse(fs.readFileSync(path.join(root,'minecraft/modpack/profile.json'))),optimizations=JSON.parse(fs.readFileSync(path.join(root,'minecraft/server/optimizations.json')));
for(const mod of [...profile.mods.filter(m=>m.install_sides.includes('server')),...optimizations.mods]){
 assert.equal(path.basename(mod.file.name),mod.file.name);
 const sources=[path.join(base,'mods',mod.file.name),path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(root,'.tools/server-optimization-jars',mod.file.name)];
 const source=sources.find(file=>fs.existsSync(file)&&createHash('sha512').update(fs.readFileSync(file)).digest('hex')===mod.file.sha512);
 assert.ok(source,'Verified cached mod required: '+mod.file.name);fs.copyFileSync(source,path.join(directory,'mods',mod.file.name));
}
const name='animeoncraft-admission-0.3.0-friends-dev',jar=path.join(root,'minecraft/integration/neoforge/build/libs',name+'.jar'),probe=path.join(root,'minecraft/integration/neoforge/build/libs',name+'-clan-market-probe.jar');
const mainBytes=fs.readFileSync(jar),probeBytes=fs.readFileSync(probe);
for(const type of ['RegionPolicy','RegionPolicy$Region','TerritoryProtection','TerritoryPermissions','PublicShopAccess','RegionMapPayload','ClanMarkets','WorldAtlas']){
 const entry='ru/animeoncraft/integration/neoforge/'+type+'.class';assert.deepEqual(jarEntry(mainBytes,entry),jarEntry(probeBytes,entry),'Probe exercises packaged production classes');
}
fs.copyFileSync(probe,path.join(directory,'mods',name+'.jar'));
applyBalanceConfig(directory);installBalancePack(directory,world);
fs.cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(directory,world,'datapacks/animeoncraft-compat'),{recursive:true});
for(const [from,to] of [['minecraft/server/world-config/clan-spawns-states.json','animeoncraft-clan-spawns.json'],['minecraft/server/world-config/worldgen-pack/aoc/layout.json','market-layout.json'],['minecraft/integration/neoforge/src/test/resources/regions-markets.json','animeoncraft-regions.json']])fs.copyFileSync(path.join(root,from),path.join(directory,'config',to));
fs.writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
fs.writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
fs.writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false','white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=false','max-tick-time=-1','level-type=minecraft:flat','level-seed=20261020','level-name='+world].join('\n')+'\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const child=spawn(java,['-Xms256m','-Xmx2048m','-Dterminal.jline=false','-Dterminal.ansi=false','-Danimeoncraft.marketProbe=true','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'}});
let output='',stopping=false,forced;const exit=once(child,'exit');
function stop(){if(stopping)return;stopping=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),30000);}
child.stdin.on('error',()=>{});
for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{output+=bytes.toString();if(/AOC_CLAN_MARKET_PROBE_(?:PASS|FAILED)|Encountered an unexpected exception|Failed to load registries/.test(output))stop();});
const timer=setTimeout(stop,240000);
try{
 const [code]=await exit;assert.equal(code,0,'Disposable server failed; inspect .tools/clan-market-probe.log');assert.match(output,/AOC_CLAN_MARKET_PROBE_PASS/);assert.ok(!/AOC_CLAN_MARKET_PROBE_FAILED|MixinApplyError|InjectionError/.test(output));
 const checks=JSON.parse(fs.readFileSync(path.join(directory,'clan-market-probe-result.json')));assert.equal(checks.passed,true);
 const result={...checks,checked_at:new Date().toISOString(),fixture_directory:directory,jar_sha256:createHash('sha256').update(mainBytes).digest('hex'),main_classes_match_probe:true,full_server_modpack:true,server_optimizations:optimizations.mods.length,mod_jars:fs.readdirSync(path.join(directory,'mods')).filter(n=>n.endsWith('.jar')).length,main_world_touched:false,vps_touched:false};
 fs.writeFileSync(path.join(root,'.tools/clan-market-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{clearTimeout(timer);clearTimeout(forced);fs.writeFileSync(path.join(root,'.tools/clan-market-probe.log'),output);}
