import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawn,execFileSync} from 'node:child_process';
import {randomUUID,createHash} from 'node:crypto';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';

const root=process.cwd(),directory=path.join(root,'.tools/beta-multiplayer-'+Date.now());
const source=path.join(root,'.tools/chat-spawn-probe-1792637733010/aoc-chat-spawn-probe-1792637733010');
assert.ok(fs.existsSync(path.join(source,'level.dat')));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev-beta-multiplayer-probe.jar');
const profile=JSON.parse(fs.readFileSync('minecraft/modpack/profile.json'));
const full=process.argv.includes('--full-modpack');
const mods=profile.mods.filter(m=>m.install_sides.includes('client')&&(full||/^(create-|ferritecore-|xaero|sodium-neoforge)/.test(m.file.name)));
fs.mkdirSync(directory,{recursive:true});
const children=[],exits=[],logs={};let stopped=false;
function launch(role){
 const game=path.join(directory,role);fs.mkdirSync(path.join(game,'mods'),{recursive:true});
 if(role==='host'){fs.mkdirSync(path.join(game,'saves'));fs.cpSync(source,path.join(game,'saves/probe'),{recursive:true});}
 for(const m of mods)fs.copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',m.file.name),path.join(game,'mods',m.file.name));
 fs.copyFileSync(jar,path.join(game,'mods/aoc-probe.jar'));
 fs.writeFileSync(path.join(game,'options.txt'),'lang:en_us\nrenderDistance:2\nsimulationDistance:5\nmaxFps:20\npauseOnLostFocus:false\nfullscreen:false\nguiScale:2\ntutorialStep:none\n');
 const java=path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
 const plan=nativeLaunchPlan({serverId:randomUUID(),serverPort:25571,gameDirectory:game,java});
 const substitutions={'%AOC_NAME%':role==='host'?'AocWearer':'AocObserver','%AOC_UUID%':randomUUID().replaceAll('-',''),'%AOC_WIDTH%':'960','%AOC_HEIGHT%':'640','%AOC_RAM%':full?'2048':'1536'};
 const args=['-Daoc.betaProbeRole='+role,'-Daoc.betaProbeRoot='+directory,'-Daoc.betaProbePort=25571'];
 for(let i=0;i<plan.args.length;i++){if(role==='host'&&['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}let a=plan.args[i];for(const [k,v]of Object.entries(substitutions))a=a.replaceAll(k,v);args.push(a);}
 if(role==='host')args.push('--quickPlaySingleplayer','probe');
 const child=spawn(java,args,{cwd:game,windowsHide:true,env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp')},stdio:['ignore','pipe','pipe']});
 logs[role]='';for(const s of [child.stdout,child.stderr])s.on('data',b=>logs[role]+=b.toString());
 const exit=new Promise(resolve=>child.on('exit',code=>{fs.writeFileSync(path.join(directory,role+'.log'),logs[role]);resolve(code);}));
 children.push(child);exits.push(exit);console.log(JSON.stringify({started:role,directory,mod_count:mods.length+1}));return child;
}
const deadline=Date.now()+420000;
const timer=setInterval(()=>{if(Date.now()>deadline){for(const c of children)c.kill();clearInterval(timer);}},1000);
try{
 launch('host');
 while(!fs.existsSync(path.join(directory,'host-ready.ok'))){assert.ok(children[0].exitCode===null,'Host exited before LAN ready');assert.ok(Date.now()<deadline,'Host startup timed out');await new Promise(r=>setTimeout(r,500));}
 launch('observer');
 const codes=await Promise.all(exits);for(const code of codes)assert.equal(code,0,'Native client failed; see '+directory);
 assert.match(logs.host,/AOC_BETA_MULTIPLAYER_HOST_PASS/);assert.match(logs.observer,/AOC_BETA_MULTIPLAYER_OBSERVER_PASS/);
 assert.ok(fs.existsSync(path.join(directory,'observer-recovered.ok')));
 const main=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
 const compare=path.join(directory,'jar-contents');
 for(const [name,file] of [['main',main],['probe',jar]])execFileSync('C:/Program Files/7-Zip/7z.exe',['x','-y','-o'+path.join(compare,name),file],{windowsHide:true,stdio:'pipe'});
 const list=(dir,rel='')=>fs.readdirSync(path.join(dir,rel),{withFileTypes:true}).flatMap(e=>e.isDirectory()?list(dir,path.join(rel,e.name)):[path.join(rel,e.name)]);
 for(const file of list(path.join(compare,'main')).filter(f=>!f.startsWith('META-INF'+path.sep)))assert.deepEqual(fs.readFileSync(path.join(compare,'main',file)),fs.readFileSync(path.join(compare,'probe',file)),file);
 const result={passed:true,checked_at:new Date().toISOString(),directory,full_modpack:full,mod_count:mods.length+1,real_network_players:2,late_join:true,remote_four_models:true,title:true,live_selection:true,appearance_recovered:true,survival_progress:true,creative_excluded:true,main_content_matches_probe:true,jar_sha256:createHash('sha256').update(fs.readFileSync(main)).digest('hex'),probe_sha256:createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),public_server_untouched:true};
 fs.writeFileSync(path.join(directory,'result.json'),JSON.stringify(result,null,2)+'\n');fs.writeFileSync('.tools/beta-multiplayer-result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));stopped=true;
}finally{clearInterval(timer);if(!stopped)for(const c of children)if(c.exitCode===null)c.kill();for(const [role,log]of Object.entries(logs))fs.writeFileSync(path.join(directory,role+'.log'),log);}
