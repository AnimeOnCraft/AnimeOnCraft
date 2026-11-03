import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {applyBalanceConfig,installBalancePack} from './game-balance.mjs';
import {jarEntry} from './jar-entry.mjs';

const root=process.cwd(),source=path.join(root,'.tools/chat-spawn-probe-1792698376103/aoc-chat-spawn-probe-1792698376103');
assert.ok(fs.existsSync(path.join(source,'level.dat')),'Stopped disposable review world required');
const directory=path.join(root,'.tools/menu-client-nextchat-'+Date.now()),world='chat-review';
fs.mkdirSync(path.join(directory,'saves'),{recursive:true});fs.cpSync(source,path.join(directory,'saves',world),{recursive:true});
fs.mkdirSync(path.join(directory,'mods'));fs.mkdirSync(path.join(directory,'config'));
const profile=JSON.parse(fs.readFileSync(path.join(root,'minecraft/modpack/profile.json')));
// The integrated review server also loads worldgen registries referenced by the
// balance datapack. Those server-side dependencies are extra in this fixture only.
for(const mod of profile.mods){
 const sources=['client/mods','server/mods','downloads'].map(dir=>path.join(root,'.tools/modpack-mvp',dir,mod.file.name));
 const file=sources.find(file=>fs.existsSync(file)&&createHash('sha512').update(fs.readFileSync(file)).digest('hex')===mod.file.sha512);
 assert.ok(file,'Verified cached review dependency: '+mod.file.name);fs.copyFileSync(file,path.join(directory,'mods',mod.file.name));
}
const probe=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev-next-chat-client-probe.jar');
const mainJar=fs.readFileSync(path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar')),probeJar=fs.readFileSync(probe);
for(const type of ['ClientChat','ChatLog','ChatPayloads','PlayerChat']){const entry='ru/animeoncraft/integration/neoforge/'+type+'.class';assert.deepEqual(jarEntry(mainJar,entry),jarEntry(probeJar,entry),'Packaged production classes match the client probe');}
fs.copyFileSync(probe,path.join(directory,'mods/animeoncraft-admission-0.3.0-friends-dev.jar'));
fs.writeFileSync(path.join(directory,'config/animeoncraft-clan-spawns.json'),JSON.stringify({format_version:1,seed:'20261016',clans:{}}));
fs.writeFileSync(path.join(directory,'options.txt'),'lang:ru_ru\nrenderDistance:3\nsimulationDistance:3\nmaxFps:30\npauseOnLostFocus:false\nfullscreen:false\nguiScale:2\ntutorialStep:none\n');
applyBalanceConfig(directory);installBalancePack(path.join(directory,'saves'),world);
const java=path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
const serverId=randomUUID(),plan=nativeLaunchPlan({serverId,serverPort:25569,gameDirectory:directory,java});
const replacements={'%AOC_NAME%':'NextChatReview','%AOC_UUID%':randomUUID().replaceAll('-',''),'%AOC_WIDTH%':'1280','%AOC_HEIGHT%':'720','%AOC_RAM%':'3072'},args=[];
for(let i=0;i<plan.args.length;i++){if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}let arg=plan.args[i];for(const [k,v]of Object.entries(replacements))arg=arg.replaceAll(k,v);args.push(arg);}
args.push('--quickPlaySingleplayer',world);
const child=spawn(java,args,{cwd:directory,windowsHide:true,env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),AOC_GAME_SERVER_ID:serverId,AOC_GAME_SERVER_KEY:randomBytes(32).toString('hex'),AOC_GAME_REDEEM_URL:'http://127.0.0.1:1/api/v1/game/admission/redeem',AOC_GAME_HEARTBEAT_URL:'http://127.0.0.1:1/integration/v1/servers/heartbeat'},stdio:['ignore','pipe','pipe']});
let log='';for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{log+=bytes;fs.appendFileSync(path.join(directory,'client.log'),bytes);});
const timer=setTimeout(()=>child.kill(),360000);
try{const [code]=await once(child,'exit');assert.equal(code,0,'Client exit; see '+directory);assert.ok(/AOC_NEXT_CHAT_CLIENT_PASS/.test(log),'Review failed; inspect fixture client.log');assert.ok(/AOC_REAL_CHAT_PAYLOAD_PASS/.test(log),'Payload check failed; inspect fixture client.log');assert.ok(!/AOC_NEXT_CHAT_CLIENT_FAILED|MixinApplyError|Preparing crash report/.test(log),'Client error; inspect fixture client.log');
 const screenshots=['next-chat-scale2','next-chat-scale3','next-chat-settings-scale3','next-chat-emoji-scale3'].map(name=>path.join(directory,'screenshots',name+'.png'));for(const file of screenshots)assert.ok(fs.existsSync(file));
 const result={passed:true,directory,native_client:true,full_client_modpack:true,extra_integrated_server_worldgen:true,jar_sha256:createHash('sha256').update(mainJar).digest('hex'),main_classes_match_probe:true,real_chat_payloads:true,real_guidebook_login:true,synthetic_review_messages:true,gui_scales:[2,3],toggle_no_duplicates:true,gear_settings:true,screenshots,public_server_untouched:true};fs.writeFileSync(path.join(root,'.tools/next-chat-client-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{clearTimeout(timer);}
