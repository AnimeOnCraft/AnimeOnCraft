import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {existsSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';

// Download the reviewed server-only overlay without modifying a live server.
const root=path.resolve(import.meta.dirname,'..');
const manifest=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
const profile=JSON.parse(readFileSync(path.join(root,'minecraft/modpack/profile.json'),'utf8'));
assert.equal(manifest.minecraft,profile.minecraft);
assert.equal(manifest.neoforge,profile.loader.version);
const directory=path.join(root,'.tools/server-optimization-jars');
mkdirSync(directory,{recursive:true});
for(const mod of manifest.mods){
 assert.deepEqual(mod.install_sides,['server']);
 assert.equal(path.basename(mod.file.name),mod.file.name);
 assert.equal(new URL(mod.file.url).origin,'https://cdn.modrinth.com');
 const target=path.join(directory,mod.file.name);
 const digest=bytes=>createHash('sha512').update(bytes).digest('hex');
 if(existsSync(target)&&digest(readFileSync(target))===mod.file.sha512){console.log(mod.slug+': verified cached JAR');continue;}
 const response=await fetch(mod.file.url,{signal:AbortSignal.timeout(120000)});
 assert.equal(response.status,200,mod.slug);
 const bytes=Buffer.from(await response.arrayBuffer());
 assert.equal(digest(bytes),mod.file.sha512,mod.slug+': download hash mismatch');
 assert.equal(createHash('sha256').update(bytes).digest('hex'),mod.file.sha256);
 writeFileSync(target,bytes);
 console.log(mod.slug+': original JAR downloaded and verified');
}
console.log('Prepared server-only overlay; no live files changed.');
