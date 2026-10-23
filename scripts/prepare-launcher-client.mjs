import {applyBalanceConfig} from './game-balance.mjs';
import {applyClientMapDefaults} from './client-map-defaults.mjs';
import assert from 'node:assert/strict';
import {readFileSync,mkdirSync,existsSync,renameSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
import {atomicWrite,matches,obtainVerified,preparationProgress} from './managed-client-download.mjs';

const root=fileURLToPath(new URL('../',import.meta.url));
const runtime=process.env.AOC_CLIENT_DATA_ROOT,java=process.env.AOC_CLIENT_JAVA;
assert.ok(path.isAbsolute(runtime)&&path.isAbsolute(java)&&existsSync(java),'Client context missing');
const bundle=JSON.parse(readFileSync(path.join(root,'bootstrap.json'),'utf8'));
const profileBytes=readFileSync(path.join(root,'minecraft/modpack/profile.json'));
const profile=JSON.parse(profileBytes);
mkdirSync(path.join(runtime,'client/mods'),{recursive:true});mkdirSync(path.join(runtime,'tmp'),{recursive:true});
preparationProgress('MODS',0,profile.mods.length);
let completed=0;
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client'))){
 const f=mod.file;assert.equal(path.basename(f.name),f.name);
 await obtainVerified({url:f.url,target:path.join(runtime,'client/mods',f.name),algorithm:'sha512',hash:f.sha512,size:f.size});
 preparationProgress('MODS',++completed,profile.mods.filter(m=>m.install_sides.includes('client')).length);
}
await obtainVerified({url:profile.loader.installer_url,target:path.join(runtime,'downloads',`neoforge-${profile.loader.version}-installer.jar`),algorithm:'sha256',hash:profile.loader.installer_sha256});
const own='animeoncraft-admission-0.3.0-friends-dev.jar';
const source=path.join(root,'minecraft/integration/neoforge/build/libs',own),target=path.join(runtime,'client/mods',own);
assert.ok(matches(source,'sha256',bundle.admission_sha256),'Bundled integration checksum mismatch');
if(!matches(target,'sha256',bundle.admission_sha256))atomicWrite(target,readFileSync(source));
const options=path.join(runtime,'client/options.txt');if(!existsSync(options))atomicWrite(options,'lang:ru_ru\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nfullscreen:false\n');
applyBalanceConfig(path.join(runtime,'client'));
applyClientMapDefaults(path.join(runtime,'client'),profile.client_map_defaults);
await import('./prepare-minecraft-client.mjs');
preparationProgress('VERIFYING',0,1);
const c=bundle.public_config;
const plan=nativeLaunchPlan({serverId:c.server_id,serverHost:c.server_host,serverPort:c.server_port,java,gameDirectory:path.join(runtime,'client'),runtimeDirectory:runtime});
const managedFile=path.join(runtime,'managed-mods.json');
const managed=[...profile.mods.filter(m=>m.install_sides.includes('client')).map(m=>m.file.name),own];
if(existsSync(managedFile)){
 const previous=JSON.parse(readFileSync(managedFile,'utf8'));assert.ok(Array.isArray(previous)&&previous.every(name=>typeof name==='string'&&name===path.basename(name)&&name.endsWith('.jar')),'Invalid managed mod inventory');
 for(const name of previous.filter(name=>!managed.includes(name))){
  const retired=path.join(runtime,'client/mods',name);if(existsSync(retired)){
   const archive=path.join(runtime,'retired-mods');mkdirSync(archive,{recursive:true});renameSync(retired,path.join(archive,`${Date.now()}-${name}`));
  }
 }
}
atomicWrite(managedFile,JSON.stringify(managed,null,2)+'\n');
atomicWrite(path.join(runtime,'prepared-game-runtime.json'),JSON.stringify(plan,null,2)+'\n');
// Only attest the complete pack after verified downloads, plan creation and retirement succeed.
atomicWrite(path.join(runtime,'managed-pack.json'),JSON.stringify({format_version:1,profile_sha256:createHash('sha256').update(profileBytes).digest('hex')},null,2)+'\n');
preparationProgress('DONE',1,1);
