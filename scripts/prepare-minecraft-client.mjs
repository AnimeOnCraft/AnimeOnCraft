import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { release } from 'node:os';
import {obtainVerified,containedPath,preparationProgress,atomicWrite,transferStats} from './managed-client-download.mjs';
import {jarEntry} from './jar-entry.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const profile = JSON.parse(readFileSync(path.join(root, 'minecraft/modpack/profile.json'), 'utf8'));
const runtime = process.env.AOC_CLIENT_DATA_ROOT || path.join(root, '.tools/modpack-mvp');
const minecraft = path.join(runtime, 'minecraft');

async function download(url, target, hash) {
  return obtainVerified({url,target,algorithm:'sha1',hash});
}

let entry;
const pinned=path.join(root,'minecraft/modpack/minecraft-metadata.json');
if(existsSync(pinned))entry=JSON.parse(readFileSync(pinned,'utf8'));
else {
 const manifestResponse = await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', { signal: AbortSignal.timeout(30_000) });
 assert.equal(manifestResponse.status, 200);
 const manifest = await manifestResponse.json();entry = manifest.versions.find(version => version.id === profile.minecraft);
}
assert.ok(entry, 'Minecraft version absent from Mojang metadata');
assert.equal(entry.id,profile.minecraft);preparationProgress('MINECRAFT',0,1);
const versionDirectory = path.join(minecraft, 'versions', profile.minecraft);
const version = JSON.parse(await download(entry.url, path.join(versionDirectory, profile.minecraft + '.json'), entry.sha1));
assert.equal(version.javaVersion.majorVersion, profile.java_major);
console.log(`Preparing Minecraft ${version.id} client in ${minecraft}`);
await download(version.downloads.client.url, path.join(versionDirectory, profile.minecraft + '.jar'), version.downloads.client.sha1);

const launcherProfiles = path.join(minecraft, 'launcher_profiles.json');
if (!existsSync(launcherProfiles)) writeFileSync(launcherProfiles, '{"profiles":{}}\n');
const loaderJson = path.join(minecraft, 'versions', `neoforge-${profile.loader.version}`, `neoforge-${profile.loader.version}.json`);
const installer = path.join(runtime, 'downloads', `neoforge-${profile.loader.version}-installer.jar`);
const installerBytes=readFileSync(installer);
assert.equal(createHash('sha256').update(installerBytes).digest('hex'),profile.loader.installer_sha256);
let loader=JSON.parse(jarEntry(installerBytes,'version.json'));
assert.equal(loader.id,`neoforge-${profile.loader.version}`);
const installerProfile=JSON.parse(jarEntry(installerBytes,'install_profile.json'));
const generatedMetadata=path.join(root,'minecraft/modpack/generated-client-files.json');
let generated=existsSync(generatedMetadata)?JSON.parse(readFileSync(generatedMetadata,'utf8')):[];
if(generated.length===0){
 for(const key of ['MC_EXTRA','MC_SLIM','MC_SRG','PATCHED']){
  const coordinate=installerProfile.data[key].client.slice(1,-1).split(':'),[group,name,version,classifier]=coordinate;
  generated.push({path:`${group.replaceAll('.','/')}/${name}/${version}/${name}-${version}-${classifier}.jar`});
 }
}
let installNeeded=false;const damagedGenerated=[];
for(const file of generated){const target=containedPath(path.join(minecraft,'libraries'),file.path);
 if(!existsSync(target))installNeeded=true;
 else if(file.sha256&&createHash('sha256').update(readFileSync(target)).digest('hex')!==file.sha256){installNeeded=true;damagedGenerated.push(target);}
}
if (installNeeded) {
  preparationProgress('LOADER',0,1);
  assert.equal(createHash('sha256').update(readFileSync(installer)).digest('hex'), profile.loader.installer_sha256);
  console.log('Installing NeoForge client');
  for(const file of damagedGenerated){renameSync(file,file+`.before-aoc-repair-${Date.now()}.bak`);transferStats.repaired++;}
  const child = spawn(process.env.AOC_CLIENT_JAVA || 'java', [`-Djava.io.tmpdir=${path.join(runtime, 'tmp')}`, '-jar', installer, '--installClient', minecraft], { cwd: runtime, stdio: 'inherit', windowsHide: true });
  await new Promise((resolve, reject) => { child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error('NeoForge client installer exit ' + code))); });
}
for(const file of generated){const target=containedPath(path.join(minecraft,'libraries'),file.path);assert.ok(existsSync(target),'NeoForge generated library missing');
 if(file.sha256)assert.equal(createHash('sha256').update(readFileSync(target)).digest('hex'),file.sha256,'NeoForge generated library checksum mismatch');
}
// Restore loader arguments and library metadata from the verified installer, not mutable cache metadata.
atomicWrite(loaderJson,JSON.stringify(loader,null,2)+'\n');

// Windows is the only supported launcher platform in the current project.
assert.equal(process.platform, 'win32');
function allowed(rules) {
  if (!rules) return true;
  let result = false;
  for (const rule of rules) {
    if (rule.os?.name && rule.os.name !== 'windows') continue;
    if (rule.os?.arch && rule.os.arch !== process.arch && !(rule.os.arch === 'x86_64' && process.arch === 'x64')) continue;
    if (rule.os?.version && !new RegExp(rule.os.version).test(release())) continue;
    result = rule.action === 'allow';
  }
  return result;
}
const queue = [...new Map([...version.libraries,...loader.libraries,...installerProfile.libraries].filter(library => allowed(library.rules)&&library.downloads?.artifact?.url).map(library => {
  const file = library.downloads.artifact;
  const target=containedPath(path.join(minecraft, 'libraries'), file.path);
  return [target,{ url: file.url, target, hash: file.sha1 }];
})).values()];
const assets = JSON.parse(await download(version.assetIndex.url, path.join(minecraft, 'assets/indexes', version.assetIndex.id + '.json'), version.assetIndex.sha1));
for (const hash of new Set(Object.values(assets.objects).map(object => object.hash))) {assert.match(hash,/^[a-f0-9]{40}$/);queue.push({
  url: `https://resources.download.minecraft.net/${hash.slice(0, 2)}/${hash}`,
  target: path.join(minecraft, 'assets/objects', hash.slice(0, 2), hash), hash,
});}
console.log(`Verifying/downloading ${queue.length} Minecraft libraries and asset objects`);
let cursor = 0;
let completed = 0;
preparationProgress('FILES',0,queue.length);
await Promise.all(Array.from({ length: 12 }, async () => {
  while (cursor < queue.length) {
    const item = queue[cursor++];
    await download(item.url, item.target, item.hash);
    completed++;
    if(completed%25===0||completed===queue.length)preparationProgress('FILES',completed,queue.length);
    if (completed % 250 === 0 || completed === queue.length) console.log(`Minecraft files: ${completed}/${queue.length}`);
  }
}));
writeFileSync(path.join(runtime, 'client-prepared.json'), JSON.stringify({ minecraft: version.id, loader: profile.loader.version, minecraft_directory: minecraft, game_directory: path.join(runtime, 'client'), asset_files_and_libraries: queue.length, checked_at: new Date().toISOString(), authenticated: false }, null, 2) + '\n');
console.log('Client runtime prepared; no Microsoft credentials read or changed.');
