import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createHash,randomUUID,randomBytes} from 'node:crypto';
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
const root=process.cwd(),base=path.join(root,'.tools/modpack-mvp/server');
assert.match(fs.readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m);
await new Promise((resolve,reject)=>{const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);socket.once('connect',()=>{socket.destroy();reject(Error('Probe port occupied; existing process preserved'));});socket.once('error',e=>e.code==='ECONNREFUSED'?resolve():reject(e));socket.once('timeout',()=>{socket.destroy();reject(Error('Port check timed out'));});});
const stamp=Date.now(),world='aoc-exploration-review-'+stamp,directory=path.join(root,'.tools/exploration-review-'+stamp);
fs.mkdirSync(path.join(directory,'mods'),{recursive:true});fs.mkdirSync(path.join(directory,'config'));
fs.symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
const profile=JSON.parse(fs.readFileSync('minecraft/modpack/profile.json'));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('server'))){
 const bytes=fs.readFileSync(path.join(base,'mods',mod.file.name));assert.equal(createHash('sha512').update(bytes).digest('hex'),mod.file.sha512);
 fs.writeFileSync(path.join(directory,'mods',mod.file.name),bytes);
}
for(const name of ['animeoncraft-admission-0.3.0-friends-dev.jar','animeoncraft-admission-0.3.0-friends-dev-exploration-probe.jar'])fs.copyFileSync(path.join(root,'minecraft/integration/neoforge/build/libs',name),path.join(directory,'mods',name));
const optimizations=JSON.parse(fs.readFileSync('minecraft/server/optimizations.json'));
for(const mod of optimizations.mods){const bytes=fs.readFileSync(path.join(root,'.tools/server-optimization-jars',mod.file.name));assert.equal(createHash('sha512').update(bytes).digest('hex'),mod.file.sha512);fs.writeFileSync(path.join(directory,'mods',mod.file.name),bytes);}
applyBalanceConfig(directory);installBalancePack(directory,world);
for(const [source,target]of [['minecraft/server/world-config/worldgen-pack','animeoncraft-states'],['minecraft/modpack/compat-pack','animeoncraft-compat']])fs.cpSync(source,path.join(directory,world,'datapacks',target),{recursive:true});
// A new test world has no built capital platforms. Territory rights have their own full-modpack probe.
fs.copyFileSync('minecraft/integration/neoforge/src/test/resources/regions-states.json',path.join(directory,'config/animeoncraft-regions.json'));
const balance=JSON.parse(fs.readFileSync('.tools/exploration-research/balance-result.json'));
fs.writeFileSync(path.join(directory,'config/aoc-exploration-probe.json'),JSON.stringify({mods:profile.mods.filter(m=>m.install_sides.includes('server')).map(m=>m.mod_id),structure_sets:balance.structure_sets,loot_tables:balance.loot_tables,advancement_repairs:balance.advancement_repairs}));
fs.mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});fs.writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
fs.writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
fs.writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false','white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=true','max-tick-time=-1','level-type=minecraft:normal','level-seed=20261020','level-name='+world].join('\n')+'\n');
const java=path.join(process.env.JAVA_HOME,'bin/java.exe');
const child=spawn(java,['-Xms512m','-Xmx6g','-Dterminal.jline=false','-Dterminal.ansi=false','-Danimeoncraft.explorationProbe=true','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'}});
const exited=once(child,'exit');let output='',stopping=false,forced;
const stop=()=>{if(stopping)return;stopping=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),60000);};
for(const stream of[child.stdout,child.stderr])stream.on('data',b=>{output+=b.toString();for(const line of b.toString().split('\n'))if(/AOC_EXPLORATION|Failed to start|ERROR/.test(line))process.stdout.write(line.slice(0,350)+'\n');if(output.includes('AOC_EXPLORATION_PROBE_PASS')||output.includes('AOC_EXPLORATION_PROBE_FAILED'))stop();});
child.stdin.on('error',()=>{});const timeout=setTimeout(stop,900000);
try{
 const [code]=await exited;fs.writeFileSync('.tools/exploration-research/server-probe.log',output);
 assert.equal(code,0,'Probe server failed');assert.ok(output.includes('AOC_EXPLORATION_PROBE_PASS'),'Runtime exploration checks did not pass');
 const result=JSON.parse(fs.readFileSync(path.join(directory,'exploration-result.json')));
 Object.assign(result,{checked_at:new Date().toISOString(),directory,world,server_mod_jars:fs.readdirSync(path.join(directory,'mods')).filter(n=>n.endsWith('.jar')).length,diagnostic_mods:1,main_world_touched:false,vps_touched:false,profile_sha256:createHash('sha256').update(fs.readFileSync('minecraft/modpack/profile.json')).digest('hex')});
 fs.writeFileSync('.tools/exploration-research/server-result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);clearTimeout(forced);fs.writeFileSync('.tools/exploration-research/server-probe.log',output);}
