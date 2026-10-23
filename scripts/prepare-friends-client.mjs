import {applyBalanceConfig} from './game-balance.mjs';
import assert from 'node:assert/strict';
import {copyFileSync,existsSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {nativeLaunchPlan} from './minecraft-launch-plan.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
assert.ok(process.argv.length===2||(process.argv.length===3&&process.argv[2]==='--prepare-only'));
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json'),'utf8'));
const config=JSON.parse(readFileSync(path.join(root,'server-public.json'),'utf8'));
const runtime=path.join(root,'.tools/modpack-mvp'),mods=path.join(runtime,'client/mods');
mkdirSync(mods,{recursive:true});mkdirSync(path.join(runtime,'tmp'),{recursive:true});
async function obtain(name,url,algorithm,expected,size){
 const file=path.join(runtime,'downloads',name);mkdirSync(path.dirname(file),{recursive:true});
 if(!existsSync(file)){
  const response=await fetch(url,{signal:AbortSignal.timeout(180000)});assert.equal(response.status,200);
  const bytes=Buffer.from(await response.arrayBuffer());assert.equal(createHash(algorithm).update(bytes).digest('hex'),expected);
  if(size)assert.equal(bytes.length,size);writeFileSync(file,bytes);
 }
 assert.equal(createHash(algorithm).update(readFileSync(file)).digest('hex'),expected,`Checksum changed: ${name}`);return file;
}
for(const mod of profile.mods.filter(m=>m.install_sides.includes('client'))){const f=mod.file;copyFileSync(await obtain(f.name,f.url,'sha512',f.sha512,f.size),path.join(mods,f.name));}
await obtain(`neoforge-${profile.loader.version}-installer.jar`,profile.loader.installer_url,'sha256',profile.loader.installer_sha256);
const own='animeoncraft-admission-0.3.0-friends-dev.jar';
copyFileSync(path.join(root,'minecraft/integration/neoforge/build/libs',own),path.join(mods,own));
const options=path.join(runtime,'client/options.txt');
if(!existsSync(options))writeFileSync(options,'lang:ru_ru\nrenderDistance:8\nsimulationDistance:5\nmaxFps:60\nfullscreen:false\n');
applyBalanceConfig(path.join(runtime,'client'));
await import('./prepare-minecraft-client.mjs');
const plan=nativeLaunchPlan({serverId:config.server_id,serverPort:config.server_port,serverHost:config.server_host,java:path.join(root,'runtime/java/bin/java.exe'),gameDirectory:path.join(runtime,'client')});
const prepared=path.join(root,'.tools/client-game-runtime.json');writeFileSync(prepared,JSON.stringify(plan,null,2)+'\n');
if(process.argv[2]!=='--prepare-only'){
 const directory=path.join(process.env.USERPROFILE,'.animeoncraft/launcher');mkdirSync(directory,{recursive:true});const target=path.join(directory,'game-runtime.json');
 if(existsSync(target))copyFileSync(target,`${target}.before-client-kit-${Date.now()}.bak`);
 writeFileSync(target,JSON.stringify(plan,null,2)+'\n');
}
console.log(JSON.stringify({prepared:true,client_only:true,runtime:prepared,home_profile_changed:process.argv[2]!=='--prepare-only',server_host:config.server_host,server_port:config.server_port}));
