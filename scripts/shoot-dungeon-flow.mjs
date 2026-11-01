// Rift Gate walkthrough with real in-game screenshots (DungeonFlowProbe): a disposable copy of a stopped Sora-expansion test world
// gets the current layout.json, then a real client (full modpack, singleplayer, no account, no server) builds the gate, runs the
// Sakura Crypt on Hard and stops inside a second run; a second launch of the same copy checks the restart (refund, back at the gate).
//   node scripts/shoot-dungeon-flow.mjs --world=.tools/layout-test-<stamp>/aoc-layout-test-<stamp>
// Screenshots are copied to .tools/dungeons-shots/.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {readFileSync,writeFileSync,appendFileSync,mkdirSync,copyFileSync,cpSync,existsSync,readdirSync} from 'node:fs';
import path from 'node:path';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {applyBalanceConfig} from './game-balance.mjs';
import {applyClientMapDefaults} from './client-map-defaults.mjs';

const root=process.cwd(),directory=path.join(root,'.tools/dungeons-flow-'+Date.now()),shots=path.join(root,'.tools/dungeons-shots');
const worldArgument=process.argv.slice(2).find(a=>a.startsWith('--world='));
assert.ok(worldArgument&&process.argv.length===3,'Provide --world=<stopped disposable layout-test world>');
const source=path.resolve(worldArgument.slice(8));
assert.ok(source.startsWith(path.join(root,'.tools','layout-test-'))&&path.basename(source).startsWith('aoc-layout-test-'),'Only disposable layout-test worlds');
assert.ok(existsSync(path.join(source,'level.dat')),'World level.dat missing');
assert.equal(process.platform,'win32');
const save=path.join(directory,'saves/dungeon-review');
mkdirSync(path.join(directory,'saves'),{recursive:true});cpSync(source,save,{recursive:true});
copyFileSync(path.join(root,'minecraft/server/world-config/worldgen-pack/aoc/layout.json'),path.join(save,'datapacks/animeoncraft-states/aoc/layout.json'));
mkdirSync(path.join(directory,'config'),{recursive:true});
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json')));
mkdirSync(path.join(directory,'mods'),{recursive:true});
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('server')&&!m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/server/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
applyBalanceConfig(directory);
applyClientMapDefaults(directory,profile.client_map_defaults);
writeFileSync(path.join(directory,'options.txt'),'lang:ru_ru\nrenderDistance:10\nsimulationDistance:5\nmaxFps:60\npauseOnLostFocus:false\ntutorialStep:none\nfullscreen:false\nfov:0.15\ngraphicsMode:1\n');
const java=process.env.AOC_CLIENT_PROBE_JAVA??path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const plan=nativeLaunchPlan({serverId:'00000000-0000-4000-8000-000000000001',serverPort:25569,gameDirectory:directory,java});
const substitutions={'%AOC_NAME%':'AocDungeonFlow','%AOC_UUID%':'00000000000030008000000000000005','%AOC_WIDTH%':'1600','%AOC_HEIGHT%':'900','%AOC_RAM%':'6144'};

async function launch(phase,extra){
  const args=['-Danimeoncraft.dungeonFlowProbe=true','-Danimeoncraft.dungeonFlowPhase='+phase,...extra];
  for(let i=0;i<plan.args.length;i++){
    if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}
    let value=plan.args[i];for(const [from,to]of Object.entries(substitutions))value=value.replaceAll(from,to);args.push(value);
  }
  args.push('--quickPlaySingleplayer','dungeon-review');
  const running=path.join(directory,`stdout-phase${phase}.log`);writeFileSync(running,'');
  let log='';const child=spawn(java,args,{cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']});
  for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{log+=chunk.toString();appendFileSync(running,chunk);
    for(const line of chunk.toString().split('\n'))if(/AOC_DUNGEON|AOC_RIFT_GATE|AOC_LAYOUT_(BUILT|FAILED)|Preparing crash report|MixinApplyError/.test(line))process.stdout.write(line.trim().slice(0,220)+'\n');});
  const timeout=setTimeout(()=>child.kill(),1200000);
  try{const [code]=await once(child,'exit');return {code,log,running};}finally{clearTimeout(timeout);}
}
const first=await launch(1,[]);
const phase1=first.log.match(/AOC_DUNGEON_FLOW_PHASE1 (.+) coins=(\d+)/);
assert.ok(phase1,`Phase 1 did not finish (exit ${first.code}); see ${first.running}`);
assert.equal(phase1[1],'ok',`Phase 1 checks failed; see ${first.running}`);
const second=await launch(2,['-Danimeoncraft.dungeonFlowCoins='+phase1[2]]);
assert.match(second.log,/AOC_DUNGEON_FLOW_DONE ok/,`Phase 2 did not pass (exit ${second.code}); see ${second.running}`);
mkdirSync(shots,{recursive:true});
const pictures=readdirSync(path.join(directory,'screenshots')).filter(n=>n.endsWith('.png'));
for(const p of pictures)copyFileSync(path.join(directory,'screenshots',p),path.join(shots,p));
const checks=[...first.log.matchAll(/AOC_DUNGEON_FLOW (PASS|FAIL) ([^\r\n]+)/g),...second.log.matchAll(/AOC_DUNGEON_FLOW (PASS|FAIL) ([^\r\n]+)/g)].map(m=>m[1]+' '+m[2]);
const result={passed:true,at:new Date().toISOString(),directory,screenshots:pictures,checks,account_used:false,vps_touched:false,main_world_touched:false};
writeFileSync(path.join(shots,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({passed:true,screenshots:pictures.length,checks:checks.length}));
