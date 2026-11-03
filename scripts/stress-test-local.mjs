// Local stress test: disposable server on 127.0.0.1:25571 with the full cached server modpack and the stress probe.
// Never targets the live server or the main world. Usage: node scripts/stress-test-local.mjs [--bots 30] [--minutes 5] [--flat]
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';

const args=process.argv.slice(2);
function option(name,fallback,min,max){const i=args.indexOf('--'+name);if(i<0)return fallback;const v=Number(args[i+1]);assert.ok(Number.isInteger(v)&&v>=min&&v<=max,`--${name} must be ${min}..${max}`);return v;}
const bots=option('bots',30,1,200),minutes=option('minutes',5,1,60),flat=args.includes('--flat');
const root=fileURLToPath(new URL('../',import.meta.url)),base=path.join(root,'.tools/modpack-mvp/server'),port=25571;
assert.equal(process.platform,'win32');
assert.match(fs.readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
await new Promise((resolve,reject)=>{const socket=net.connect(port,'127.0.0.1');socket.setTimeout(3000);socket.once('connect',()=>{socket.destroy();reject(Error(`Port ${port} occupied; existing process preserved`));});socket.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));socket.once('timeout',()=>{socket.destroy();reject(Error('Cannot check fixture port'));});});
const stamp=Date.now(),world='aoc-stress-'+stamp,directory=path.join(root,'.tools/stress-probe-'+stamp);
fs.mkdirSync(path.join(directory,'mods'),{recursive:true});fs.mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
fs.symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
const profile=JSON.parse(fs.readFileSync(path.join(root,'minecraft/modpack/profile.json'))),optimizations=JSON.parse(fs.readFileSync(path.join(root,'minecraft/server/optimizations.json')));
for(const mod of [...profile.mods.filter(m=>m.install_sides.includes('server')),...optimizations.mods]){
 assert.equal(path.basename(mod.file.name),mod.file.name);
 const sources=[path.join(base,'mods',mod.file.name),path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(root,'.tools/server-optimization-jars',mod.file.name)];
 const source=sources.find(file=>fs.existsSync(file)&&createHash('sha512').update(fs.readFileSync(file)).digest('hex')===mod.file.sha512);
 assert.ok(source,'Verified cached mod required: '+mod.file.name);fs.copyFileSync(source,path.join(directory,'mods',mod.file.name));
}
const name='animeoncraft-admission-0.3.0-friends-dev',probe=path.join(root,'minecraft/integration/neoforge/build/libs',name+'-stress-probe.jar');
assert.ok(fs.existsSync(probe),'Build it first: gradle :neoforge:stressProbeJar');
fs.copyFileSync(probe,path.join(directory,'mods',name+'.jar'));
applyBalanceConfig(directory);installBalancePack(directory,world);
fs.cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(directory,world,'datapacks/animeoncraft-compat'),{recursive:true});
// New terrain has none of the live world's capitals. Avoid validating live spawn
// coordinates against unrelated terrain; the production config is never edited.
fs.writeFileSync(path.join(directory,'config/animeoncraft-clan-spawns.json'),JSON.stringify({format_version:1,seed:'20261020',clans:{}}));
fs.writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24461\nbind_address=127.0.0.1\n');
fs.writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
// Same view/simulation distance as production so chunk load per "player" is comparable.
fs.writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port='+port,'online-mode=false','enforce-secure-profile=false','white-list=false','spawn-protection=0','view-distance=6','simulation-distance=4','max-tick-time=-1',flat?'level-type=minecraft:flat':'level-type=minecraft:normal','level-seed=20261020','level-name='+world].join('\n')+'\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const seconds=minutes*60;
const child=spawn(java,['-Xms2g','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Daoc.stress.bots='+bots,'-Daoc.stress.seconds='+seconds,'@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'}});
let output='',stopping=false,forced;const exit=once(child,'exit');
function stop(){if(stopping)return;stopping=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),60000);}
child.stdin.on('error',()=>{});
for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{const text=bytes.toString();output+=text;for(const line of text.split(/\r?\n/))if(/AOC_STRESS_(START|SAMPLE|DONE|FAILED)/.test(line))console.log(line.trim());if(/AOC_STRESS_FAILED|Encountered an unexpected exception|Failed to load registries/.test(text))stop();});
// World generation on first start can take minutes; allow startup + run + shutdown.
const timer=setTimeout(stop,(seconds+900)*1000);
try{
 await exit;
 assert.ok(/AOC_STRESS_DONE/.test(output),'Stress run did not finish; inspect .tools/stress-probe.log');
 const measured=JSON.parse(fs.readFileSync(path.join(directory,'stress-result.json')));
 const result={...measured,checked_at:new Date().toISOString(),fixture_directory:directory,requested_bots:bots,minutes,world_type:flat?'flat':'normal',full_server_modpack:true,mod_jars:fs.readdirSync(path.join(directory,'mods')).filter(n=>n.endsWith('.jar')).length,note:'Ticket bots load chunks and spawn mobs but send no packets: a lower bound for real players.',main_world_touched:false,vps_touched:false};
 fs.writeFileSync(path.join(root,'.tools/stress-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
}finally{clearTimeout(timer);clearTimeout(forced);fs.writeFileSync(path.join(root,'.tools/stress-probe.log'),output);}
