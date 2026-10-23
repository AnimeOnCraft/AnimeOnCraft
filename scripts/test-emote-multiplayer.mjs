import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {randomUUID,createHash} from 'node:crypto';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';

const root=process.cwd(),directory=path.join(root,'.tools/emote-multiplayer-'+Date.now());
const source=path.join(root,'.tools/chat-spawn-probe-1792637733010/aoc-chat-spawn-probe-1792637733010');
assert.ok(fs.existsSync(path.join(source,'level.dat')),'A disposable chat/spawn fixture is required');
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev-emote-multiplayer-probe.jar');
const profile=JSON.parse(fs.readFileSync('minecraft/modpack/profile.json'));
const full=process.argv.includes('--full-modpack'),menuOnly=process.argv.includes('--menu-only');
const mods=profile.mods.filter(m=>m.install_sides.includes('client')&&(full||/^(create-|ferritecore-|xaero|sodium-neoforge)/.test(m.file.name)));
fs.mkdirSync(directory,{recursive:true});
const children=[],exits=[],logs={};let passed=false;
function launch(role){
 const game=path.join(directory,role);fs.mkdirSync(path.join(game,'mods'),{recursive:true});
 if(role==='host'){fs.mkdirSync(path.join(game,'saves'));fs.cpSync(source,path.join(game,'saves/probe'),{recursive:true});}
 for(const m of mods)fs.copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',m.file.name),path.join(game,'mods',m.file.name));
 fs.copyFileSync(jar,path.join(game,'mods/aoc-probe.jar'));
 fs.writeFileSync(path.join(game,'options.txt'),'lang:ru_ru\nrenderDistance:2\nsimulationDistance:5\nmaxFps:25\npauseOnLostFocus:false\nfullscreen:false\nguiScale:2\ntutorialStep:none\n');
 const java=path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
 const plan=nativeLaunchPlan({serverId:randomUUID(),serverPort:25572,gameDirectory:game,java});
 const substitutions={'%AOC_NAME%':role==='host'?'AocWearer':'AocObserver','%AOC_UUID%':randomUUID().replaceAll('-',''),'%AOC_WIDTH%':'960','%AOC_HEIGHT%':'640','%AOC_RAM%':full?'2048':'1536'};
 const args=['-Daoc.emoteProbeRole='+role,'-Daoc.emoteProbeRoot='+directory,'-Daoc.emoteProbePort=25572','-Daoc.emoteMenuOnly='+menuOnly];
 for(let i=0;i<plan.args.length;i++){if(role==='host'&&['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}let a=plan.args[i];for(const [k,v]of Object.entries(substitutions))a=a.replaceAll(k,v);args.push(a);}
 if(role==='host')args.push('--quickPlaySingleplayer','probe');
 const child=spawn(java,args,{cwd:game,windowsHide:true,env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp')},stdio:['ignore','pipe','pipe']});
 logs[role]='';for(const s of [child.stdout,child.stderr])s.on('data',b=>{logs[role]+=b.toString();fs.appendFileSync(path.join(directory,role+'.log'),b);});
 const exit=new Promise(resolve=>child.on('exit',code=>resolve(code)));children.push(child);exits.push(exit);console.log(JSON.stringify({started:role,directory,mod_count:mods.length+1}));
}
const deadline=Date.now()+420000,timer=setInterval(()=>{if(Date.now()>deadline){for(const c of children)c.kill();clearInterval(timer);}},1000);
try{
 launch('host');
 if(!menuOnly){while(!fs.existsSync(path.join(directory,'host-ready.ok'))){assert.ok(children[0].exitCode===null,'Host exited before LAN ready');assert.ok(Date.now()<deadline,'Host startup timed out');await new Promise(r=>setTimeout(r,500));}launch('observer');}
 const codes=await Promise.all(exits);for(const code of codes)assert.equal(code,0,'Native client failed; see '+directory);
 if(menuOnly)assert.match(logs.host,/AOC_EMOTE_MENU_PASS/);else{assert.match(logs.host,/AOC_EMOTE_HOST_PASS/);assert.match(logs.observer,/AOC_EMOTE_OBSERVER_PASS/);}
 const result={passed:true,directory,menu_only:menuOnly,full_modpack:full,checked_at:new Date().toISOString(),real_network_players:menuOnly?1:2,five_starter_emotes:true,real_player_model_mixin:!menuOnly,remote_dance_and_two_poses:!menuOnly,camera_restored:!menuOnly,movement_and_damage_cancel:!menuOnly,public_server_untouched:true,probe_sha256:createHash('sha256').update(fs.readFileSync(jar)).digest('hex')};
 fs.writeFileSync(menuOnly?'.tools/emote-menu-result.json':'.tools/emote-multiplayer-result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));passed=true;
}finally{clearInterval(timer);if(!passed)for(const c of children)if(c.exitCode===null)c.kill();}
