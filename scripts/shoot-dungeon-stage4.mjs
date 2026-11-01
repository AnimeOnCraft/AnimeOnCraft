// Rift Gate stage 4 in a real client (DungeonStage4Probe): catalog override and reload, Mythic screen/clock/watcher, the four weekly
// affixes in real runs, a guild DUNGEON quest from the clear to the coins, the economy journal. Disposable singleplayer copy on F:.
//   node scripts/shoot-dungeon-stage4.mjs --world=.tools/layout-test-<stamp>/aoc-layout-test-<stamp>
// Screenshots and result.json go to .tools/dungeons-shots/stage4/.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {readFileSync,writeFileSync,appendFileSync,mkdirSync,copyFileSync,cpSync,existsSync,readdirSync} from 'node:fs';
import path from 'node:path';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {applyBalanceConfig} from './game-balance.mjs';
import {applyClientMapDefaults} from './client-map-defaults.mjs';

const root=process.cwd(),directory=path.join(root,'.tools/dungeons-stage4-'+Date.now()),shots=path.join(root,'.tools/dungeons-shots/stage4');
const worldArgument=process.argv.slice(2).find(a=>a.startsWith('--world='));
assert.ok(worldArgument&&process.argv.length===3,'Provide --world=<stopped disposable layout-test world>');
const source=path.resolve(worldArgument.slice(8));
assert.ok(source.startsWith(path.join(root,'.tools','layout-test-'))&&path.basename(source).startsWith('aoc-layout-test-'),'Only disposable layout-test worlds');
assert.ok(existsSync(path.join(source,'level.dat')),'World level.dat missing');
const save=path.join(directory,'saves/stage4-review');
mkdirSync(path.join(directory,'saves'),{recursive:true});cpSync(source,save,{recursive:true});
copyFileSync(path.join(root,'minecraft/server/world-config/worldgen-pack/aoc/layout.json'),path.join(save,'datapacks/animeoncraft-states/aoc/layout.json'));
mkdirSync(path.join(directory,'config'),{recursive:true});mkdirSync(path.join(directory,'mods'),{recursive:true});
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json')));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('server')&&!m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/server/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
applyBalanceConfig(directory);applyClientMapDefaults(directory,profile.client_map_defaults);
writeFileSync(path.join(directory,'options.txt'),'lang:ru_ru\nrenderDistance:10\nsimulationDistance:5\nmaxFps:60\npauseOnLostFocus:false\ntutorialStep:none\nfullscreen:false\nfov:0.15\ngraphicsMode:1\n');
const java=process.env.AOC_CLIENT_PROBE_JAVA??path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const plan=nativeLaunchPlan({serverId:'00000000-0000-4000-8000-000000000001',serverPort:25569,gameDirectory:directory,java});
const substitutions={'%AOC_NAME%':'AocStage4','%AOC_UUID%':'00000000000030008000000000000006','%AOC_WIDTH%':'1600','%AOC_HEIGHT%':'900','%AOC_RAM%':process.env.AOC_PROBE_RAM??'5120'};
const args=['-Danimeoncraft.dungeonStage4Probe=true'];
for(let i=0;i<plan.args.length;i++){if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}let v=plan.args[i];for(const [a,b] of Object.entries(substitutions))v=v.replaceAll(a,b);args.push(v);}
args.push('--quickPlaySingleplayer','stage4-review');
const running=path.join(directory,'stdout.log');writeFileSync(running,'');
let log='';const child=spawn(java,args,{cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']});
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{log+=chunk.toString();appendFileSync(running,chunk);
  for(const line of chunk.toString().split('\n'))if(/AOC_DUNGEON_STAGE4|AOC_DUNGEON_(START|VICTORY|FAILED)|Preparing crash report|MixinApplyError/.test(line))process.stdout.write(line.trim().slice(0,220)+'\n');});
const timeout=setTimeout(()=>child.kill(),1500000);
try{
  const [code]=await once(child,'exit');
  assert.match(log,/AOC_DUNGEON_STAGE4_DONE ok/,`Stage 4 checks failed (exit ${code}); see ${running}`);
  mkdirSync(shots,{recursive:true});const pictures=readdirSync(path.join(directory,'screenshots')).filter(n=>n.endsWith('.png'));
  for(const p of pictures)copyFileSync(path.join(directory,'screenshots',p),path.join(shots,p));
  const checks=[...log.matchAll(/AOC_DUNGEON_STAGE4 (PASS|FAIL) ([^\r\n]+)/g)].map(m=>m[1]+' '+m[2]);
  const economy=[...log.matchAll(/AOC_DUNGEON_STAGE4_ECONOMY ([^\r\n]+)/g)].map(m=>m[1]);
  writeFileSync(path.join(shots,'result.json'),JSON.stringify({passed:true,at:new Date().toISOString(),directory,screenshots:pictures,checks,economy,account_used:false,vps_touched:false,main_world_touched:false},null,2)+'\n');
  console.log(JSON.stringify({passed:true,screenshots:pictures.length,checks:checks.length}));
}finally{clearTimeout(timeout);}
