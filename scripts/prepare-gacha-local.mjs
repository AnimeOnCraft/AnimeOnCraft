// Local gacha review: a separate copy of the local server (clan world copy, profile mods, current admission JAR)
// and a separate client game directory, so the public client in .tools/modpack-mvp/client is never changed.
//   node scripts/prepare-gacha-local.mjs
import assert from 'node:assert/strict';
import {copyFileSync,cpSync,existsSync,mkdirSync,readdirSync,readFileSync,statSync} from 'node:fs';
import path from 'node:path';
import {applyBalanceConfig} from './game-balance.mjs';

const root=process.cwd(),base=path.join(root,'.tools/gacha-local'),source=path.join(root,'.tools/modpack-mvp');
const server=path.join(base,'server'),client=path.join(base,'client'),jarName='animeoncraft-admission-0.3.0-friends-dev.jar';
const jar=path.join(root,'minecraft/integration/neoforge/build/libs',jarName);
assert.ok(existsSync(jar),'Build the mod first');assert.ok(!existsSync(server)&&!existsSync(client),'gacha-local already prepared; delete its server/client folders to rebuild');
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json'),'utf8'));

// Server: small files and config copied, the clan world copied, mods rebuilt from the profile.
mkdirSync(server,{recursive:true});
for(const name of readdirSync(path.join(source,'server'))){
 if(['mods','logs','crash-reports','world'].includes(name)||name.endsWith('.bak'))continue;
 cpSync(path.join(source,'server',name),path.join(server,name),{recursive:true});
}
mkdirSync(path.join(server,'mods'));
for(const mod of profile.mods.filter(m=>m.install_sides.includes('server'))){
 const from=path.join(source,mod.install_sides.includes('client')?'client':'server','mods',mod.file.name);
 assert.ok(existsSync(from),'Missing mod '+mod.file.name);copyFileSync(from,path.join(server,'mods',mod.file.name));
}
copyFileSync(jar,path.join(server,'mods',jarName));
applyBalanceConfig(server);

// Client: a real copy without saves/logs/map caches (~450 MB); shared assets/libraries stay in .tools/modpack-mvp/minecraft.
mkdirSync(client,{recursive:true});
for(const name of readdirSync(path.join(source,'client'))){
 if(['saves','logs','crash-reports','screenshots','journeymap','%SystemDrive%'].includes(name))continue;
 cpSync(path.join(source,'client',name),path.join(client,name),{recursive:true});
}
copyFileSync(jar,path.join(client,'mods',jarName));
// The launcher checks the pack receipt next to the game directory.
for(const name of ['managed-pack.json'])if(existsSync(path.join(source,name)))copyFileSync(path.join(source,name),path.join(base,name));
console.log(JSON.stringify({server,client,mods:readdirSync(path.join(server,'mods')).length,jar_bytes:statSync(jar).size}));
