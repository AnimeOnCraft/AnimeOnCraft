// Isolated real Minecraft review; never touches installed players, worlds or public servers.
import assert from 'node:assert/strict';
import {existsSync,mkdirSync,readFileSync,writeFileSync,copyFileSync,openSync,closeSync} from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {createHash} from 'node:crypto';
import {applyLoadingTheme,LOADING_JAR} from './client-loading-theme.mjs';
import {prepareTitleMedia,titleMediaPath} from './client-title-media.mjs';
import {fileURLToPath} from 'node:url';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const review=process.argv.includes('--review');
const directory=path.join(root,'.tools',`title-menu-client-${Date.now()}`);
mkdirSync(path.join(directory,'mods'),{recursive:true});
const titleMedia=JSON.parse(readFileSync(path.join(root,'minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/textures/title/media.json'),'utf8'));
const mediaPath=titleMediaPath(directory,titleMedia);mkdirSync(path.dirname(mediaPath),{recursive:true});
copyFileSync(path.join(root,'.tools/title-media',titleMedia.sha256+'.zip'),mediaPath);
await prepareTitleMedia(directory,titleMedia,{fetcher:()=>{throw new Error('Native preview must use verified local media');}});
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json'),'utf8'));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client'))){
 const source=path.join(root,'.tools/modpack-mvp/client/mods',mod.file.name);
 assert.ok(existsSync(source),`Missing full-pack mod: ${mod.file.name}`);
 copyFileSync(source,path.join(directory,'mods',mod.file.name));
}
const probe=path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev-title-menu-probe.jar');
assert.ok(existsSync(probe),'Build :neoforge:titleMenuProbeJar first');
copyFileSync(probe,path.join(directory,'mods/animeoncraft-admission-0.3.0-friends-dev.jar'));
writeFileSync(path.join(directory,'options.txt'),'lang:ru_ru\nguiScale:2\nmaxFps:60\npauseOnLostFocus:false\nonboardAccessibility:false\n');
const loadingSource=path.join(root,'minecraft/integration/loading-window/build/libs',LOADING_JAR);
assert.ok(existsSync(loadingSource),'Build :loading-window:jar first');
applyLoadingTheme(directory,{source:loadingSource,sha256:createHash('sha256').update(readFileSync(loadingSource)).digest('hex')});
const java=path.join(root,'.tools/launcher-bootstrap/runtime/java/bin/java.exe');
// Avoid another developer's local server: choose a free port and close it before probing refusal.
const reservation=createServer();
await new Promise((resolve,reject)=>{reservation.once('error',reject);reservation.listen(0,'127.0.0.1',resolve);});
const serverPort=reservation.address().port;
await new Promise((resolve,reject)=>reservation.close(error=>error?reject(error):resolve()));
const plan=nativeLaunchPlan({serverId:'00000000-0000-4000-8000-000000000001',serverPort,gameDirectory:directory,java});
const args=[];
for(let i=0;i<plan.args.length;i++){
 if(['--quickPlayMultiplayer','--quickPlayPath'].includes(plan.args[i])){i++;continue;}
 args.push(plan.args[i].replaceAll('%AOC_NAME%','MenuReview').replaceAll('%AOC_UUID%','00000000000030008000000000000001')
  .replaceAll('%AOC_RAM%','4096').replaceAll('%AOC_WIDTH%','1280').replaceAll('%AOC_HEIGHT%','720'));
}
if(review)args.unshift('-Danimeoncraft.titleMenuReview=true');
args.unshift('-Danimeoncraft.loadingProbe=true');
const log=path.join(directory,'client.log'),out=openSync(log,'w');
const environment=Object.fromEntries(['SystemRoot','WINDIR','PATH','USERPROFILE','APPDATA','LOCALAPPDATA']
 .filter(key=>process.env[key]!==undefined).map(key=>[key,process.env[key]]));
Object.assign(environment,{TEMP:path.join(root,'.tools/work-temp'),TMP:path.join(root,'.tools/work-temp'),AOC_GAME_TARGET_HOST:'127.0.0.1',AOC_GAME_TARGET_PORT:String(serverPort)});
const child=spawn(java,args,{cwd:directory,windowsHide:true,env:environment,stdio:['ignore',out,out]});
closeSync(out);
writeFileSync(path.join(directory,'review.json'),JSON.stringify({pid:child.pid,review,serverPort,public_server_untouched:true,log},null,2));
console.log(`Title menu ${review?'review':'probe'}: ${directory}`);
const timeout=setTimeout(()=>{child.kill();console.error('Title client timeout');},review?3600000:300000);
child.on('exit',code=>{
 clearTimeout(timeout);
 const text=readFileSync(log,'utf8');
 const loadingPassed=text.includes('AOC_LOADING_THEME_READY')&&text.includes('AOC_LOADING_THEME_CLOSED')
  &&['01-early-startup','02-mod-progress','03-minecraft-loading'].every(phase=>existsSync(path.join(directory,'loading-screenshots',phase+'.png')))
  &&!text.includes('Custom loading theme failed');
 const passed=code===0&&(review||(text.includes('AOC_TITLE_MENU_PASS')&&loadingPassed));
 writeFileSync(path.join(directory,'result.json'),JSON.stringify({passed,code,review,loadingPassed,public_server_untouched:true},null,2));
 console.log(JSON.stringify({directory,passed,code}));process.exitCode=passed?0:1;
});
