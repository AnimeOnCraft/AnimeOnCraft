// Rift Gate team run with two real clients (DungeonTeamProbe): the host opens a disposable copy of a stopped Sora-expansion test world to LAN
// and plays the leader, the guest is a second client on that LAN port. No account, no dedicated server, no VPS.
//   node scripts/shoot-dungeon-team.mjs --world=.tools/layout-test-<stamp>/aoc-layout-test-<stamp>
// Full modpack (the host also gets the server-only mods the world's datapacks need). --light (Lightman's Currency, Curios, Sodium,
// FerriteCore) only works for a world without modded datapacks; a layout-test world refuses to load with it. Screenshots: .tools/dungeons-shots/team/.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';

const root=process.cwd(),directory=path.join(root,'.tools/dungeons-team-'+Date.now()),shots=path.join(root,'.tools/dungeons-shots/team'),port=25573;
const worldArgument=process.argv.slice(2).find(a=>a.startsWith('--world='));
assert.ok(worldArgument,'Provide --world=<stopped disposable layout-test world>');
const source=path.resolve(worldArgument.slice(8)),full=!process.argv.includes('--light');
assert.ok(source.startsWith(path.join(root,'.tools','layout-test-'))&&path.basename(source).startsWith('aoc-layout-test-'),'Only disposable layout-test worlds');
assert.ok(fs.existsSync(path.join(source,'level.dat')),'World level.dat missing');
const profile=JSON.parse(fs.readFileSync('minecraft/modpack/profile.json'));
const light=/^(lightmanscurrency-|curios-|ferritecore-|sodium-neoforge-)/;
const mods=profile.mods.filter(m=>m.install_sides.includes('client')&&(full||light.test(m.file.name)));
const jar=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar');
fs.mkdirSync(directory,{recursive:true});
const children=[],logs={};let passed=false;
function launch(role){
  const game=path.join(directory,role);fs.mkdirSync(path.join(game,'mods'),{recursive:true});
  if(role==='host'){const save=path.join(game,'saves/team-review');fs.mkdirSync(path.dirname(save),{recursive:true});fs.cpSync(source,save,{recursive:true});
    fs.copyFileSync(path.join(root,'minecraft/server/world-config/worldgen-pack/aoc/layout.json'),path.join(save,'datapacks/animeoncraft-states/aoc/layout.json'));}
  for(const m of mods)fs.copyFileSync(path.join(root,'.tools/modpack-mvp/client/mods',m.file.name),path.join(game,'mods',m.file.name));
  // The host runs the integrated server: it also needs the server-only mods the world's datapacks refer to (structure libraries).
  if(role==='host'&&full)for(const m of profile.mods.filter(m=>m.install_sides.includes('server')&&!m.install_sides.includes('client')))fs.copyFileSync(path.join(root,'.tools/modpack-mvp/server/mods',m.file.name),path.join(game,'mods',m.file.name));
  fs.copyFileSync(jar,path.join(game,'mods',path.basename(jar)));
  fs.writeFileSync(path.join(game,'options.txt'),'lang:ru_ru\nrenderDistance:6\nsimulationDistance:5\nmaxFps:30\npauseOnLostFocus:false\nfullscreen:false\ntutorialStep:none\nfov:0.15\n');
  const java=process.env.AOC_CLIENT_PROBE_JAVA??path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
  const plan=nativeLaunchPlan({serverId:randomUUID(),serverPort:port,gameDirectory:game,java});
  const substitutions={'%AOC_NAME%':role==='host'?'AocLeader':'AocGuest','%AOC_UUID%':randomUUID().replaceAll('-',''),'%AOC_WIDTH%':'1280','%AOC_HEIGHT%':'720','%AOC_RAM%':full?(role==='host'?'3584':'2816'):'2048'};
  const args=['-Daoc.dungeonTeamRole='+role,'-Daoc.dungeonTeamRoot='+directory,'-Daoc.dungeonTeamPort='+port];
  for(let i=0;i<plan.args.length;i++){if(role==='host'&&['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}let a=plan.args[i];for(const [k,v]of Object.entries(substitutions))a=a.replaceAll(k,v);args.push(a);}
  if(role==='host')args.push('--quickPlaySingleplayer','team-review');
  const child=spawn(java,args,{cwd:game,windowsHide:true,env:{...process.env,TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp')},stdio:['ignore','pipe','pipe']});
  logs[role]='';for(const s of [child.stdout,child.stderr])s.on('data',b=>{const text=b.toString();logs[role]+=text;fs.appendFileSync(path.join(directory,role+'.log'),b);
    for(const line of text.split('\n'))if(/AOC_DUNGEON_TEAM|AOC_DUNGEON_(START|BEGIN|VICTORY|END|GONE|DEATH|FAILED)|Preparing crash report/.test(line))process.stdout.write(role+': '+line.trim().slice(0,220)+'\n');});
  children.push(new Promise(resolve=>child.on('exit',code=>resolve(code))));children[role]=child;
}
const deadline=Date.now()+2_400_000,timer=setInterval(()=>{if(Date.now()>deadline)for(const r of ['host','guest'])children[r]?.kill();},1000);
try{
  launch('host');
  while(!fs.existsSync(path.join(directory,'host-ready.ok'))){assert.ok(children.host.exitCode===null,'Host exited before LAN ready; see '+directory);assert.ok(Date.now()<deadline,'Host startup timed out');await new Promise(r=>setTimeout(r,1000));}
  launch('guest');
  await Promise.all(children);
  assert.match(logs.host,/AOC_DUNGEON_TEAM_DONE ok/,'Team checks failed; see '+directory);
  fs.mkdirSync(shots,{recursive:true});const pictures=[];
  for(const role of ['host','guest']){const dir=path.join(directory,role,'screenshots');if(fs.existsSync(dir))for(const p of fs.readdirSync(dir).filter(n=>n.endsWith('.png'))){fs.copyFileSync(path.join(dir,p),path.join(shots,p));pictures.push(p);}}
  const checks=[...logs.host.matchAll(/AOC_DUNGEON_TEAM (PASS|FAIL) ([^\r\n]+)/g)].map(m=>m[1]+' '+m[2]);
  const result={passed:true,at:new Date().toISOString(),directory,real_clients:2,full_modpack:full,screenshots:pictures,checks,account_used:false,vps_touched:false,main_world_touched:false};
  fs.writeFileSync(path.join(shots,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({passed:true,screenshots:pictures.length,checks:checks.length}));passed=true;
}finally{clearInterval(timer);if(!passed)for(const r of ['host','guest'])if(children[r]?.exitCode===null)children[r].kill();}
