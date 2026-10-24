// Real in-game screenshots of the Adventurers' Guild: a disposable copy of a stopped Sora-expansion test world is
// opened in a real client (full client modpack, singleplayer, no account, no server) and GuildShotProbe takes
// fixed camera shots, then quits.   node scripts/shoot-guild-client.mjs --world=.tools/layout-test-<stamp>/aoc-layout-test-<stamp>
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {readFileSync,writeFileSync,appendFileSync,mkdirSync,copyFileSync,cpSync,existsSync,readdirSync} from 'node:fs';
import path from 'node:path';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {applyBalanceConfig} from './game-balance.mjs';
import {applyClientMapDefaults} from './client-map-defaults.mjs';

const root=process.cwd(),directory=path.join(root,'.tools/guild-shots-'+Date.now());
const worldArgument=process.argv.slice(2).find(a=>a.startsWith('--world='));
assert.ok(worldArgument&&process.argv.length===3,'Provide --world=<stopped disposable layout-test world>');
const source=path.resolve(worldArgument.slice(8));
assert.ok(source.startsWith(path.join(root,'.tools','layout-test-'))&&path.basename(source).startsWith('aoc-layout-test-'),'Only disposable layout-test worlds');
assert.ok(existsSync(path.join(source,'level.dat')),'World level.dat missing');
assert.equal(process.platform,'win32');
mkdirSync(path.join(directory,'saves'),{recursive:true});
cpSync(source,path.join(directory,'saves/guild-review'),{recursive:true});
mkdirSync(path.join(directory,'config'),{recursive:true});
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json')));
mkdirSync(path.join(directory,'mods'),{recursive:true});
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('server')&&!m.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/server/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
copyFileSync(jar,path.join(directory,'mods',path.basename(jar)));
applyBalanceConfig(directory);
applyClientMapDefaults(directory,profile.client_map_defaults);
writeFileSync(path.join(directory,'options.txt'),'lang:ru_ru\nrenderDistance:10\nsimulationDistance:5\nmaxFps:60\npauseOnLostFocus:false\nfullscreen:false\nfov:0.15\ngraphicsMode:1\n');
const java=process.env.AOC_CLIENT_PROBE_JAVA??path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const plan=nativeLaunchPlan({serverId:'00000000-0000-4000-8000-000000000001',serverPort:25569,gameDirectory:directory,java});
const substitutions={'%AOC_NAME%':'AocGuildReview','%AOC_UUID%':'00000000000030008000000000000003','%AOC_WIDTH%':'1600','%AOC_HEIGHT%':'900','%AOC_RAM%':'6144'};
const args=['-Danimeoncraft.guildShotProbe=true'];
for(let i=0;i<plan.args.length;i++){
  if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}
  let value=plan.args[i];for(const [from,to]of Object.entries(substitutions))value=value.replaceAll(from,to);args.push(value);
}
args.push('--quickPlaySingleplayer','guild-review');
const running=path.join(directory,'stdout-running.log');writeFileSync(running,'');
let log='';const child=spawn(java,args,{cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']});
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{log+=chunk.toString();appendFileSync(running,chunk);
  for(const line of chunk.toString().split('\n'))if(/AOC_GUILD_SHOT|Preparing crash report|MixinApplyError/.test(line))process.stdout.write(line.trim().slice(0,200)+'\n');});
const timeout=setTimeout(()=>child.kill(),900000);
try{
  const [code]=await once(child,'exit');
  assert.match(log,/AOC_GUILD_SHOTS_DONE/,`Client did not finish (exit ${code}); see ${running}`);
  const shots=readdirSync(path.join(directory,'screenshots')).filter(n=>n.endsWith('.png'));
  console.log(JSON.stringify({passed:true,directory,screenshots:shots,account_used:false,vps_touched:false,main_world_touched:false}));
}finally{clearTimeout(timeout);}
