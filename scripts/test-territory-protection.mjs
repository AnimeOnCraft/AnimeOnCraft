import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {copyFileSync,cpSync,existsSync,mkdirSync,readdirSync,readFileSync,symlinkSync,writeFileSync} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const base=path.join(root,'.tools/modpack-mvp/server');
const flags=process.argv.slice(2);
assert.ok(flags.every(flag=>['--adventure','--normal-world','--full-modpack','--server-optimizations','--world-boss-only','--world-boss-minimal','--boss-early-stop','--chunk-control','--create-lifecycle','--magic-boundary','--packaged-jar','--controller-restart','--boss-health'].includes(flag))&&new Set(flags).size===flags.length);
const adventure=flags.includes('--adventure');
assert.ok(!adventure||flags.includes('--full-modpack'));
const bossHealth=flags.includes('--boss-health');
assert.ok(!bossHealth||(flags.includes('--world-boss-only')&&!flags.includes('--packaged-jar')),'Boss health uses a separate full-modpack probe JAR');
const controllerRestart=flags.includes('--controller-restart');
const lifecycle=flags.includes('--create-lifecycle');
const magic=flags.includes('--magic-boundary');
const probeLog=bossHealth?'.tools/world-boss-health-probe.log':controllerRestart?'.tools/controller-restart-probe.log':magic?'.tools/magic-boundary-probe.log':lifecycle?'.tools/create-lifecycle-probe.log':flags.some(flag=>flag.startsWith('--world-boss-'))?'.tools/world-boss-probe.log':'.tools/territory-protection-probe.log';
const jar=path.join(root,`minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev${(adventure||bossHealth||controllerRestart||lifecycle||magic)&&!flags.includes('--packaged-jar')?'-lifecycle-probe':''}.jar`);
const worldBossOnly=flags.includes('--world-boss-only')||flags.includes('--world-boss-minimal');
const earlyStop=flags.includes('--boss-early-stop');
const chunkControl=flags.includes('--chunk-control');
assert.ok(!controllerRestart||(!worldBossOnly&&!lifecycle&&!magic&&!flags.includes('--packaged-jar')),'Controller restart is a separate opt-in fixture');
const passMarker=adventure?'AOC_PERSONAL_QUEST_PROBE_PASS':controllerRestart?'AOC_CONTROLLER_RESTART_PROBE_PASS':worldBossOnly?'AOC_WORLD_BOSS_PROBE_PASS':'AOC_TERRITORY_PROBE_PASS';
const restartMarker=controllerRestart?'AOC_CONTROLLER_RESTART_REQUEST':'AOC_WORLD_BOSS_RESTART_REQUEST';
assert.ok(!chunkControl||worldBossOnly);
assert.ok(!earlyStop||worldBossOnly);
assert.ok(!lifecycle||!worldBossOnly,'Create lifecycle uses its own full-modpack fixture');
assert.ok(!magic||!worldBossOnly,'Magic boundary uses its own full-modpack fixture');
const fullModpack=controllerRestart||magic||lifecycle||flags.includes('--world-boss-only')||flags.includes('--full-modpack');
assert.ok(existsSync(jar),'Build the NeoForge mod first');
assert.match(readFileSync(path.join(base,'eula.txt'),'utf8'),/^eula=true\s*$/m,'Existing owner EULA agreement required');
await new Promise((resolve,reject)=>{
  const socket=net.connect(25569,'127.0.0.1');socket.setTimeout(3000);
  socket.once('connect',()=>{socket.destroy();reject(new Error('Port 25569 occupied; existing process preserved'));});
  socket.once('error',error=>error.code==='ECONNREFUSED'?resolve():reject(error));
  socket.once('timeout',()=>{socket.destroy();reject(new Error('Could not check fixture port'));});
});
const stamp=Date.now();
const directory=path.join(root,`.tools/territory-probe-${stamp}`);
mkdirSync(path.join(directory,'mods'),{recursive:true});mkdirSync(path.join(directory,'config'));
symlinkSync(path.join(base,'libraries'),path.join(directory,'libraries'),'junction');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
if(fullModpack){
 applyBalanceConfig(directory);installBalancePack(directory,`aoc-territory-probe-${stamp}`);
 for(const name of readdirSync(path.join(base,'mods')))if(name.endsWith('.jar')&&!name.startsWith('animeoncraft-admission-'))copyFileSync(path.join(base,'mods',name),path.join(directory,'mods',name));
 mkdirSync(path.join(directory,'config/voicechat'),{recursive:true});
 writeFileSync(path.join(directory,'config/voicechat/voicechat-server.properties'),'port=24459\nbind_address=127.0.0.1\n');
 cpSync(path.join(root,'minecraft/modpack/compat-pack'),path.join(directory,`aoc-territory-probe-${stamp}/datapacks/animeoncraft-compat`),{recursive:true});
}
if(flags.includes('--server-optimizations')){
 assert.ok(fullModpack,'Server optimization validation requires the full modpack');
 const optimizations=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
 for(const mod of optimizations.mods){
  assert.equal(path.basename(mod.file.name),mod.file.name);
  const source=path.join(root,'.tools/server-optimization-jars',mod.file.name);
  assert.equal(createHash('sha512').update(readFileSync(source)).digest('hex'),mod.file.sha512);
  copyFileSync(source,path.join(directory,'mods',mod.file.name));
 }
}
writeFileSync(path.join(directory,'eula.txt'),'eula=true\n');
writeFileSync(path.join(directory,'server.properties'),[
  'server-ip=127.0.0.1','server-port=25569','online-mode=false','enforce-secure-profile=false',
  'white-list=false','spawn-protection=0','view-distance=2','simulation-distance=2',`generate-structures=${flags.includes('--normal-world')}`,
  'max-tick-time=-1',
  `level-type=minecraft:${flags.includes('--normal-world')?'normal':'flat'}`,'level-seed=20261016',`level-name=aoc-territory-probe-${stamp}`,
].join('\n')+'\n');
const clans={},colors={};
for(const [index,code] of ['SCAM','NYA','AKUMA','ZXC','YAKUZA'].entries()){
  clans[code]={x:128*(index+1),z:128*(index+1)};colors[code]='A970FF';
}
writeFileSync(path.join(directory,'config/animeoncraft-clan-spawns.json'),JSON.stringify({format_version:1,seed:'20261016',clans,
  common:{x:0,z:0},territories:{width_blocks:128,colors},protection:{spawn_radius_blocks:16}}));
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java.exe'):'java';
const argumentsList=['-Xms256m',adventure?'-Xmx2560m':fullModpack?'-Xmx1536m':'-Xmx1024m','-Dterminal.jline=false','-Dterminal.ansi=false',...(controllerRestart?['-Danimeoncraft.controllerRestartProbe=true']:[]),
  '-Danimeoncraft.territoryProbe=true',...(adventure?['-Danimeoncraft.adventureProbe=true']:[]),...(magic?['-Danimeoncraft.magicBoundaryProbe=true']:[]),...(lifecycle?['-Danimeoncraft.createLifecycleProbe=true']:[]),...(worldBossOnly?['-Danimeoncraft.worldBossProbeOnly=true']:[]),...(earlyStop?['-Danimeoncraft.worldBossEarlyStop=true']:[]),...(chunkControl?['-Danimeoncraft.chunkControl=true']:[]),...(fullModpack?['-Danimeoncraft.balanceProbe=true']:[]),'@libraries/net/neoforged/neoforge/21.1.262/win_args.txt','nogui'];
const childOptions={cwd:directory,windowsHide:true,stdio:['pipe','pipe','pipe'],env:{...process.env,AOC_GAME_SERVER_ID:randomUUID(),AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'}};
let output='',done=false,forcedShutdown,restartRequested=false,restarted=false;
function launch(){
 const child=spawn(java,argumentsList,childOptions),exited=once(child,'exit');
 for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{
  output+=chunk.toString();
  if(output.includes(passMarker)&&!done){done=true;child.stdin.write('stop\n');forcedShutdown=setTimeout(()=>child.kill(),30000);}
  if((worldBossOnly||controllerRestart)&&!restartRequested&&output.includes(restartMarker)){restartRequested=true;child.stdin.write('stop\n');forcedShutdown=setTimeout(()=>child.kill(),30000);}
  if(!forcedShutdown&&(output.includes('AOC_TERRITORY_PROBE_FAILED')||output.includes('AOC_WORLD_BOSS_PROBE_FAILED')||output.includes('AOC_CONTROLLER_RESTART_PROBE_FAILED'))){
    // This is the exact disposable child we spawned, after an explicit fatal
    // fixture failure. Never terminate the owner's main server for a timeout.
    forcedShutdown=setTimeout(()=>child.kill(),15000);
  }
 });
 return {child,exited};
}
let {child,exited}=launch();
const timer=setTimeout(()=>{child.stdin.write('stop\n');if(!forcedShutdown)forcedShutdown=setTimeout(()=>child.kill(),30000);},240000);
try{
  let [code]=await exited;
  if((worldBossOnly||controllerRestart)&&restartRequested&&!done&&code===0){clearTimeout(forcedShutdown);forcedShutdown=undefined;({child,exited}=launch());restarted=true;[code]=await exited;}
  assert.equal(code,0,`Fixture failed; see ${probeLog}`);
  assert.ok(done,`Territory event/action checks failed; see ${probeLog}`);
  if(adventure)assert.ok(output.includes('AOC_ADVENTURE_PROBE_PASS'),'Adventure integrations did not pass');
  if(fullModpack)assert.ok(output.includes('AOC_SPELL_BALANCE_RUNTIME_PASS'),'Spell modifiers were not checked after actual datapack sync');
  if(bossHealth)assert.ok(output.includes('AOC_WORLD_BOSS_HEALTH_PASS'),'Boss health range and NBT reload failed');
  if(controllerRestart){assert.ok(output.includes(passMarker));assert.equal(restarted,true,'Actual controller process restart required');}
  else if(!worldBossOnly){
    if(magic)assert.ok(output.includes('AOC_SPELL_BOUNDARY_PROBE_PASS'));
    if(lifecycle){assert.ok(output.includes('AOC_CREATE_LIFECYCLE_PROBE_PASS'));assert.ok(output.includes('AOC_CREATE_CONTROLLER_PROBE_PASS'));}
    if(fullModpack){assert.ok(output.includes('AOC_CREATE_PROBE_PASS'));assert.ok(output.includes('AOC_CREATE_TRANSFER_PROBE_PASS'));assert.ok(output.includes('AOC_CREATE_WORLD_PROBE_PASS'));assert.ok(output.includes('AOC_BALANCE_PROBE_PASS'));}
    assert.ok(output.includes('AOC_COMBAT_PROBE_PASS'));assert.ok(output.includes('AOC_SPREAD_PROBE_PASS'));
    assert.ok(output.includes('AOC_IGNITION_PROBE_PASS'),'Member ignition, portals and boundary protection not checked');
    assert.ok(output.includes('AOC_CAPTURE_PROBE_PASS'),'Capture reset/persistence probe failed');
    assert.ok(output.includes('AOC_REWARD_PROBE_PASS'),'Cancelled and confirmed boss death rewards probe failed');
  }else{assert.ok(output.includes('AOC_WORLD_BOSS_PROBE_PASS'),'Shared world boss encounter probe failed');if(!chunkControl)assert.equal(restarted,true,'An actual Java process restart is required');}
  const common={passed:true,checked_at:new Date().toISOString(),fixture_directory:directory,main_world_touched:false,
    admission_sha256:createHash('sha256').update(readFileSync(path.join(directory,'mods',path.basename(jar)))).digest('hex'),full_modpack:fullModpack,server_optimizations:flags.includes('--server-optimizations'),normal_world:flags.includes('--normal-world'),early_stop:earlyStop,create_lifecycle:lifecycle,magic_boundary:magic,adventure,
    ...(fullModpack?{runtime_spell_modifiers:111,spell_config_sync_verified:true}:{}),
    ...(output.includes('AOC_WORLD_BOSS_HEALTH_PASS')?{boss_health_15_players:1800,boss_health_30_players:3000,ordinary_wither_health:300,health_nbt_roundtrip:true}:{}),
    ...(magic?{real_spell_firebolt:true,real_spell_fire_field:true,real_spell_summon:true,real_spell_blizzard:true,spell_active_raid_and_friendly_rules:true,helpful_healing_preserved:true}:{}),
    ...(lifecycle?{train_destination_and_multi_carriage:true,controller_destination:true,controller_physical_break_and_tick:true,controller_recovery:true,own_controller_break:true}:{}),
  };
  const result=controllerRestart?{...common,controller_persistence:true,controllers:3,real_ticks:true,process_restart_verified:restarted,normal_shutdown_verified:true}:worldBossOnly?{...common,shared_world_boss:!chunkControl,chunk_control:chunkControl,real_ticks:true,process_restart_verified:restarted,normal_shutdown_verified:true,generation_drained:output.includes('AOC_ARENA_GENERATION_DRAINED')}:{...common,
    create_assembly_and_disassembly:fullModpack,balance_pack_loaded:fullModpack,create_transfers:fullModpack,combat_rules:true,capture_hold_and_reset:true,cancelled_boss_rewards_blocked:true,containers_and_hopper_boundary:true,clans:5,protected_spawns:1,editable_clan_spawns:5,clan_spawn_chest_rights:true,real_break_place_explosion:true,neutral_building:true};
  writeFileSync(path.join(root,bossHealth?'.tools/world-boss-health-result.json':controllerRestart?'.tools/controller-restart-result.json':magic?'.tools/magic-boundary-result.json':lifecycle?'.tools/create-lifecycle-result.json':chunkControl?'.tools/world-boss-chunk-control-result.json':worldBossOnly?(earlyStop?'.tools/world-boss-early-stop-result.json':'.tools/world-boss-result.json'):'.tools/territory-protection-result.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result));
}finally{
  clearTimeout(timer);clearTimeout(forcedShutdown);writeFileSync(path.join(root,probeLog),output);
}
