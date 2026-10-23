import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {jarEntry} from './jar-entry.mjs';

const root=process.cwd(),target=path.join(root,'.tools/social-economy-local-20261023');
const profile=JSON.parse(fs.readFileSync('minecraft/modpack/profile.json'));
assert.equal(profile.pack_version,'0.8.9-social-economy-local');
const proofs=['social-economy-result.json','social-economy-server-result.json','wardrobe-db-result.json'];
const results=proofs.map(file=>JSON.parse(fs.readFileSync('.tools/'+file)));
for(const result of results)assert.equal(result.passed,true);
const own='animeoncraft-admission-0.3.0-friends-dev.jar';
const ownPath='minecraft/integration/neoforge/build/libs/'+own;
const ownBytes=fs.readFileSync(ownPath),sha256=b=>createHash('sha256').update(b).digest('hex');
assert.equal(sha256(ownBytes),results[1].jar_sha256,'Dedicated proof must match the delivered JAR');
assert.equal(sha256(fs.readFileSync('minecraft/modpack/profile.json')),results[1].profile_sha256);

// The native run used this exact tested JAR; only guide wording changed afterwards.
const nativeBytes=fs.readFileSync(path.join(results[0].directory,'host/mods/aoc-probe.jar'));
assert.equal(sha256(nativeBytes),results[0].probe_sha256);
function entries(bytes){
 let end=bytes.length-22;while(bytes.readUInt32LE(end)!==0x06054b50)end--;
 let offset=bytes.readUInt32LE(end+16);const names=[];
 for(let i=0;i<bytes.readUInt16LE(end+10);i++){
  assert.equal(bytes.readUInt32LE(offset),0x02014b50);
  const length=bytes.readUInt16LE(offset+28),extra=bytes.readUInt16LE(offset+30),comment=bytes.readUInt16LE(offset+32);
  names.push(bytes.subarray(offset+46,offset+46+length).toString('utf8'));offset+=46+length+extra+comment;
 }return names;
}
const classes=entries(ownBytes).filter(name=>name.endsWith('.class'));
assert.ok(!classes.some(name=>/SocialEconomyProbe|ServerMenuClientProbe/.test(name)));
const changed=classes.filter(name=>!jarEntry(ownBytes,name).equals(jarEntry(nativeBytes,name)));
assert.deepEqual(changed,['ru/animeoncraft/integration/neoforge/ServerMenuGuide.class']);

const files=[];
function copy(source,relative){const bytes=fs.readFileSync(source),out=path.join(target,relative);fs.mkdirSync(path.dirname(out),{recursive:true});fs.writeFileSync(out,bytes);files.push({path:relative,bytes:bytes.length,sha256:sha256(bytes)});}
copy(ownPath,own);
for(const mod of profile.mods.filter(mod=>['lightmans-currency','secure-trade'].includes(mod.slug))){
 const source='.tools/modpack-mvp/downloads/'+mod.file.name;
 assert.equal(createHash('sha512').update(fs.readFileSync(source)).digest('hex'),mod.file.sha512);
 copy(source,'mods/'+mod.file.name);
}
assert.equal(files.filter(f=>f.path.startsWith('mods/')).length,2);
copy('minecraft/modpack/profile.json','profiles/modpack.json');
copy('minecraft/server/economy-profile.json','profiles/economy.json');
copy('apps/backend/db/migrations/20261023000400_paired_starter_emotes/migration.sql','backend/20261023000400_paired_starter_emotes.sql');
copy('docs/operations/SOCIAL_ECONOMY_LOCAL_2026_10_23.md','README.md');
for(const file of proofs)copy('.tools/'+file,'proofs/'+file);
for(const file of ['emote-catalog','favorites','secure-trade'])copy(path.join(results[0].directory,'host/screenshots',file+'.png'),'screenshots/'+file+'.png');
for(const file of ['high-five','handshake','pair-invitation'])copy(path.join(results[0].directory,'observer/screenshots',file+'.png'),'screenshots/'+file+'.png');
copy(path.join(results[0].directory,'controls-audit.json'),'proofs/controls-audit.json');
fs.writeFileSync(path.join(target,'Start-Review.cmd'),'@echo off\r\npowershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\\..\\scripts\\preview-social-economy.ps1"\r\n');
files.push({path:'Start-Review.cmd',sha256:sha256(fs.readFileSync(path.join(target,'Start-Review.cmd')))});
const manifest={prepared_at:new Date().toISOString(),status:'LOCAL_REVIEW_ONLY',profile:profile.pack_version,public_version:'0.8.8',jar_sha256:sha256(ownBytes),native_classes_compared:classes.length,native_differences:changed,native_difference_reason:'Trade guide explains wallet/payment slots; gameplay classes are byte-for-byte identical',public_server_untouched:true,files};
fs.writeFileSync(path.join(target,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({prepared:true,directory:target,files:files.length,jar_sha256:manifest.jar_sha256,native_classes_compared:classes.length}));
