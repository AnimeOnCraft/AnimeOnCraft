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
const stamp=Date.now(),world='aoc-beta-levels-probe-'+stamp,directory=path.join(root,'.tools/beta-levels-probe-'+stamp);
fs.mkdirSync(path.join(directory,'mods'),{recursive:true});fs.mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
fs.symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
const profile=JSON.parse(fs.readFileSync(path.join(root,'minecraft/modpack/profile.json')));
const optimizations=JSON.parse(fs.readFileSync(path.join(root,'minecraft/server/optimizations.json')));
for(const mod of [...profile.mods.filter(m=>m.install_sides.includes('server')),...optimizations.mods]){
 assert.equal(path.basename(mod.file.name),mod.file.name);
 const sources=[path.join(base,'mods',mod.file.name),path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(root,'.tools/server-optimization-jars',mod.file.name)];
 const source=sources.find(file=>fs.existsSync(file)&&createHash('sha512').update(fs.readFileSync(file)).digest('hex')===mod.file.sha512);
 assert.ok(source,'Verified mod must already be installed or cached: '+mod.file.name);
 fs.copyFileSync(source,path.join(directory,'mods',mod.file.name));
}
const name='animeoncraft-admission-0.3.0-friends-dev',jar=path.join(root,'minecraft/integration/neoforge/build/libs',name+'.jar'),probe=path.join(root,'minecraft/integration/neoforge/build/libs',name+'-beta-pass-command-probe.jar');
const mainBytes=fs.readFileSync(jar),probeBytes=fs.readFileSync(probe);
for(const type of ['BetaPassCommands','BetaPassStore','BetaPassStore$Entry','BetaPassServer']){const entry='ru/animeoncraft/integration/neoforge/'+type+'.class';assert.deepEqual(jarEntry(mainBytes,entry),jarEntry(probeBytes,entry),'Probe must exercise packaged production code');}
fs.copyFileSync(probe,path.join(directory,'mods',name+'.jar'));
applyBalanceConfig(directory);installBalancePack(directory,world);
fs.cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(directory,world,'datapacks/animeoncraft-compat'),{recursive:true});
fs.writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
fs.writeFileSync(path.join(directory,'config/animeoncraft-clan-spawns.json'),JSON.stringify({format_version:1,seed:'20261016',clans:{SCAM:{x:512,z:512},NYA:{x:1024,z:1024}},common:{x:0,z:0},territories:{width_blocks:128,colors:{SCAM:'FF0000',NYA:'FF00FF'}},protection:{spawn_radius_blocks:16}}));
fs.writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
fs.writeFileSync(path.join(directory,'server.properties'),['server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false','white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2','generate-structures=false','max-tick-time=-1','level-type=minecraft:flat','level-seed=20261016','level-name='+world].join('\n')+'\n');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const child=spawn(java,['-Xms256m','-Xmx2048m','-Dterminal.jline=false','-Dterminal.ansi=false','-Danimeoncraft.betaPassCommandProbe=true','@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'],{cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem'}});
let output='',stopping=false,forced;const exit=once(child,'exit');
function stop(){if(stopping)return;stopping=true;child.stdin.write('stop\n');forced=setTimeout(()=>child.kill(),30000);}
child.stdin.on('error',()=>{});
for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{output+=bytes.toString();if(/AOC_BETA_PASS_COMMAND_PROBE_(?:PASS|FAILED)/.test(output))stop();});
const timer=setTimeout(stop,240000);
try{
 const [code]=await exit;assert.equal(code,0,'Disposable server failed; inspect .tools/beta-pass-command-probe.log');assert.match(output,/AOC_BETA_PASS_COMMAND_PROBE_PASS/);assert.ok(!/AOC_BETA_PASS_COMMAND_PROBE_FAILED|MixinApplyError|InjectionError/.test(output));
 const checks=JSON.parse(fs.readFileSync(path.join(directory,'beta-pass-command-probe-result.json')));assert.equal(checks.passed,true);
 const result={...checks,checked_at:new Date().toISOString(),fixture_directory:directory,jar_sha256:createHash('sha256').update(mainBytes).digest('hex'),main_classes_match_probe:true,full_server_modpack:true,server_optimizations:optimizations.mods.length,mod_jars:fs.readdirSync(path.join(directory,'mods')).filter(n=>n.endsWith('.jar')).length,main_world_touched:false,vps_touched:false};
 fs.writeFileSync(path.join(root,'.tools/beta-pass-command-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{clearTimeout(timer);clearTimeout(forced);fs.writeFileSync(path.join(root,'.tools/beta-pass-command-probe.log'),output);}
