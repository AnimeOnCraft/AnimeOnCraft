import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {readFileSync,writeFileSync,mkdirSync,copyFileSync,cpSync} from 'node:fs';
import path from 'node:path';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {applyBalanceConfig} from './game-balance.mjs';
const root=process.cwd(),directory=path.join(root,'.tools/adventure-client-'+Date.now());
const world=process.argv.find(arg=>arg.startsWith('--world='))?.slice(8);
if(world){assert.ok(path.resolve(world).startsWith(path.join(root,'.tools/territory-probe-')),'Only disposable probe worlds accepted');cpSync(world,path.join(directory,'saves/adventure-review'),{recursive:true});}
const profile=JSON.parse(readFileSync('minecraft/modpack/profile.json'));
mkdirSync(path.join(directory,'mods'),{recursive:true});
for(const mod of profile.mods.filter(x=>x.install_sides.includes('client')))copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name),path.join(directory,'mods',mod.file.name));
applyBalanceConfig(directory);
writeFileSync(path.join(directory,'options.txt'),'lang:en_us\nrenderDistance:4\nsimulationDistance:3\nmaxFps:30\nfullscreen:false\n');
// By default exercise the exact compact runtime shipped in the installer,
// rather than silently masking missing JDK modules with the developer JDK.
const java=process.env.AOC_CLIENT_PROBE_JAVA??path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const plan=nativeLaunchPlan({serverId:'00000000-0000-4000-8000-000000000001',serverPort:25569,gameDirectory:directory,java});
const replacements={'%AOC_NAME%':'AocMenuProbe','%AOC_UUID%':'00000000000030008000000000000001','%AOC_WIDTH%':'960','%AOC_HEIGHT%':'600','%AOC_RAM%':'4096'};
const args=[];
for(let i=0;i<plan.args.length;i++){
 if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}
 let value=plan.args[i];for(const [from,to] of Object.entries(replacements))value=value.replaceAll(from,to);args.push(value);
}
if(world){
 for(const name of ['animeoncraft-admission-0.3.0-friends-dev.jar','animeoncraft-admission-0.3.0-friends-dev-adventure-client-probe.jar'])copyFileSync(path.join(root,'minecraft/integration/neoforge/build/libs',name),path.join(directory,'mods',name));
 args.unshift('-Danimeoncraft.adventureClientProbe=true');args.push('--quickPlaySingleplayer','adventure-review');
}
let log='';const child=spawn(java,args,{cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']});
for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>{log+=chunk.toString();});
const timer=setTimeout(()=>child.kill(),240000);
try{
 await once(child,'exit');
 writeFileSync(path.join(root,'.tools/adventure-client-smoke.log'),log);
 const latest=readFileSync(path.join(directory,'logs/latest.log'),'utf8');
 writeFileSync(path.join(root,'.tools/adventure-client-latest.log'),latest);
 assert.ok(!/ModLoadingException|Loading errors encountered|Failed to create mod instance/.test(log+latest),'Client mod loading failed; inspect adventure-client-smoke.log');
 assert.ok(/Created:.*textures\/atlas|OpenAL initialized/.test(latest),'Client renderer/audio initialization not reached');
 if(world){assert.ok(/AOC_JER_JEI_PROBE_PASS/.test(log),'JER categories not available through JEI');assert.ok(/AOC_PERSONAL_QUEST_CLIENT_PASS/.test(log),'Personal quest book not opened');}
 if(world&&profile.mods.some(m=>m.mod_id==='xaeroworldmap')){
  assert.match(log,/AOC_XAERO_NATIVE_PROBE_PASS world_map=true minimap=true markers_only=true/);
  assert.match(log,/AOC_XAERO_RENDER_PASS/);
 }
 const result={passed:true,directory,java_executable:java,java_image_sha256:createHash('sha256').update(readFileSync(path.resolve(path.dirname(java),'../lib/modules'))).digest('hex'),client_mods:profile.mods.filter(x=>x.install_sides.includes('client')).length,real_client_started:true,account_used:false,joined_server:false,integrated_world_requested:!!world,gameplay_acceptance:false};
 writeFileSync(path.join(root,'.tools/adventure-client-result.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify(result));
}finally{clearTimeout(timer);}
