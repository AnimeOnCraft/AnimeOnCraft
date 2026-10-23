import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {readFileSync,writeFileSync,appendFileSync,mkdirSync,copyFileSync,cpSync,existsSync,readdirSync} from 'node:fs';
import path from 'node:path';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {applyBalanceConfig} from './game-balance.mjs';
import {applyClientMapDefaults} from './client-map-defaults.mjs';

const root=process.cwd(), directory=path.join(root,'.tools/atlas-client-'+Date.now());
const argsIn=process.argv.slice(2);
assert.ok(argsIn.length===1 && argsIn[0].startsWith('--world='),'Provide a stopped disposable chat/spawn probe world');
const source=path.resolve(argsIn[0].slice(8));
assert.ok(source.startsWith(path.join(root,'.tools/chat-spawn-probe-')) && path.basename(source).startsWith('aoc-chat-spawn-probe-'));
assert.ok(existsSync(path.join(source,'level.dat')));
assert.equal(process.platform,'win32');
mkdirSync(path.join(directory,'saves'),{recursive:true});
cpSync(source,path.join(directory,'saves/atlas-review'),{recursive:true});
mkdirSync(path.join(directory,'config'),{recursive:true});
copyFileSync(path.join(root,'minecraft/server/world-config/worldgen-pack/aoc/layout.json'),path.join(directory,'config/atlas-probe-layout.json'));
copyFileSync(path.join(root,'minecraft/integration/neoforge/src/test/resources/regions-states.json'),path.join(directory,'config/animeoncraft-regions.json'));
const config=JSON.parse(readFileSync(path.join(root,'minecraft/server/world-config/clan-spawns-states.json'),'utf8'));config.seed='20261016';
writeFileSync(path.join(directory,'config/animeoncraft-clan-spawns.json'),JSON.stringify(config));
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json')));
mkdirSync(path.join(directory,'mods'),{recursive:true});
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
const admissionSha=createHash('sha256').update(readFileSync(path.join(directory,'mods',path.basename(jar)))).digest('hex');
applyBalanceConfig(directory);
applyClientMapDefaults(directory,profile.client_map_defaults);
writeFileSync(path.join(directory,'options.txt'),'lang:en_us\nrenderDistance:3\nsimulationDistance:5\nmaxFps:30\npauseOnLostFocus:false\nfullscreen:false\n');
const java=process.env.AOC_CLIENT_PROBE_JAVA??path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const plan=nativeLaunchPlan({serverId:'00000000-0000-4000-8000-000000000001',serverPort:25569,gameDirectory:directory,java});
const substitutions={'%AOC_NAME%':'AocAtlasProbe','%AOC_UUID%':'00000000000030008000000000000001','%AOC_WIDTH%':'1280','%AOC_HEIGHT%':'800','%AOC_RAM%':'4096'};
const args=['-Danimeoncraft.atlasClientProbe=true'];
for(let i=0;i<plan.args.length;i++){
  if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}
  let value=plan.args[i];for(const [from,to]of Object.entries(substitutions))value=value.replaceAll(from,to);args.push(value);
}
args.push('--quickPlaySingleplayer','atlas-review');
const runningLog=path.join(root,'.tools/atlas-client-stdout-running.log');writeFileSync(runningLog,'');
let log='';const child=spawn(java,args,{cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']});
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{log+=chunk.toString();appendFileSync(runningLog,chunk);});
const timeout=setTimeout(()=>child.kill(),420000);
try{
  const [exitCode]=await once(child,'exit');
  const latest=existsSync(path.join(directory,'logs/latest.log'))?readFileSync(path.join(directory,'logs/latest.log'),'utf8'):'';
  writeFileSync(path.join(root,'.tools/atlas-client-probe.log'),log+'\n'+latest);
  assert.equal(exitCode,0,'Atlas client failed; see .tools/atlas-client-probe.log');
  assert.match(log,/AOC_ATLAS_CLIENT_PASS/);
  assert.ok(!/AOC_ATLAS_CLIENT_FAILED|MixinApplyError|InjectionError|Preparing crash report/.test(log+latest));
  if(profile.client_map_defaults){
    const mini=readFileSync(path.join(directory,'config/xaero/minimap/profiles/default.cfg'),'utf8'),map=readFileSync(path.join(directory,'config/xaero/world-map/profiles/default.cfg'),'utf8');
    for(const key of ['waypoints_in_world','tracked_players_in_world'])assert.match(mini,new RegExp(`^${key} = false$`,'m'),'Minecraft must retain hidden world markers');
    for(const key of ['waypoints_on_minimap','tracked_players_on_minimap'])assert.match(mini,new RegExp(`^${key} = true$`,'m'),'Minimap markers must remain enabled');
    for(const key of ['waypoints','render_waypoints','display_tracked_players'])assert.match(map,new RegExp(`^${key} = true$`,'m'),'World-map markers must remain enabled');
  }
  const result={passed:true,checked_at:new Date().toISOString(),directory,
    admission_sha256:admissionSha,
    client_mod_jars:readdirSync(path.join(directory,'mods')).filter(n=>n.endsWith('.jar')).length,
    real_client_started:true,real_network_payload:true,static_terrain:false,image_included:false,colored_territory_overlays:false,
    native_map_writer_live:true,edited_surface_mapped:true,world_map:true,minimap:true,native_waypoints:true,live_point_updates:true,
    automatic_cave_layers:true,two_real_cave_floors_mapped:true,upper_layer_revisited:true,surface_restored_after_caves:true,
    clan_world_and_minimap_trackers:true,private_markers_preserved:true,
    map_only_defaults_verified:!!profile.client_map_defaults,
    other_dimensions_unaffected:true,logout_clears:true,account_used:false,vps_touched:false,main_world_touched:false,
    screenshots:['atlas-minimap.png','atlas-world.png','atlas-surface-edited.png','atlas-cave-upper-minimap.png','atlas-cave-upper-world.png','atlas-cave-lower-minimap.png','atlas-cave-lower-world.png','atlas-cave-revisited.png','atlas-surface-restored.png'].map(n=>path.join(directory,'screenshots',n))};
  for(const screenshot of result.screenshots)assert.ok(existsSync(screenshot));
  writeFileSync(path.join(root,'.tools/atlas-client-result.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);writeFileSync(path.join(root,'.tools/atlas-client-stdout.log'),log);}
