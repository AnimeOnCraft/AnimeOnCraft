import assert from 'node:assert/strict';
import {cpSync,existsSync,mkdirSync,readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import path from 'node:path';
const root=process.cwd(),bundle=path.join(root,'.tools/launcher-bootstrap');
const directory=path.join(root,`.tools/client-preparation-probe-${Date.now()}`);
const originalRuntime=path.join(process.env.USERPROFILE,'.animeoncraft/launcher/game-runtime.json');
const hash=file=>createHash('sha256').update(readFileSync(file)).digest('hex');
const before=hash(originalRuntime),profile=JSON.parse(readFileSync(path.join(bundle,'minecraft/modpack/profile.json')));
mkdirSync(directory,{recursive:true});
for(const folder of ['minecraft','client/mods','downloads'])cpSync(path.join(root,'.tools/modpack-mvp',folder),path.join(directory,folder),{recursive:true});
// Simulate an upgrade from the former managed Overgeared installation.
const retiredName='overgeared-neoforge-1.21.1-1.6.19.jar';
const retirementExpected=!profile.mods.some(m=>m.file.name===retiredName);
if(retirementExpected){
 writeFileSync(path.join(directory,'client/mods',retiredName),'previous managed mod sentinel');
 writeFileSync(path.join(directory,'managed-mods.json'),JSON.stringify([retiredName]));
 writeFileSync(path.join(directory,'client/mods/personal-extra.jar'),'unmanaged mod sentinel');
}
const options=path.join(directory,'client/options.txt'),save=path.join(directory,'client/saves/my-world/keep.txt');
writeFileSync(options,'personal-options-sentinel\n');mkdirSync(path.dirname(save),{recursive:true});writeFileSync(save,'personal-world-sentinel\n');
const version=JSON.parse(readFileSync(path.join(directory,'minecraft/versions/1.21.1/1.21.1.json')));
const assets=JSON.parse(readFileSync(path.join(directory,`minecraft/assets/indexes/${version.assetIndex.id}.json`)));
const assetHash=Object.values(assets.objects)[0].hash,asset=path.join(directory,'minecraft/assets/objects',assetHash.slice(0,2),assetHash);
const mod=profile.mods.find(m=>m.install_sides.includes('client')).file;
const modTarget=path.join(directory,'client/mods',mod.name);
writeFileSync(asset,'damaged asset');writeFileSync(modTarget,'damaged mod');
const loader=JSON.parse(readFileSync(path.join(directory,'minecraft/versions/neoforge-21.1.262/neoforge-21.1.262.json')));
const library=loader.libraries[0].downloads.artifact,libraryTarget=path.join(directory,'minecraft/libraries',library.path);writeFileSync(libraryTarget,'damaged library');
const generated=JSON.parse(readFileSync(path.join(bundle,'minecraft/modpack/generated-client-files.json')));
writeFileSync(path.join(directory,'minecraft/libraries',generated.at(-1).path),'damaged generated NeoForge client');
const java=path.join(bundle,'runtime/java/bin/java.exe');
async function run(offline){
 const args=offline?['--import','data:text/javascript,globalThis.fetch=()=>{throw new Error("NETWORK_DISABLED")}']:[];
 args.push(path.join(bundle,'scripts/prepare-launcher-client.mjs'));
 const child=spawn(path.join(bundle,'runtime/node/node.exe'),args,{cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe'],env:{...process.env,AOC_CLIENT_DATA_ROOT:directory,AOC_CLIENT_JAVA:java,AOC_CLIENT_PROGRESS:'1'}});
 let output='';for(const stream of [child.stdout,child.stderr])stream.on('data',chunk=>output+=chunk.toString());
 const [code]=await once(child,'exit');writeFileSync(path.join(root,`.tools/client-preparation-${offline?'offline':'repair'}.log`),output);
 assert.equal(code,0,'Preparation failed; see its log');
 const done=output.split(/\r?\n/).filter(line=>line.startsWith('AOC_PREPARE ')).map(line=>JSON.parse(line.slice(12))).findLast(item=>item.stage==='DONE');assert.ok(done);return done;
}
const first=await run(false);assert.ok(first.repaired>=3);assert.ok(first.downloaded>=2);
if(retirementExpected){
 assert.equal(existsSync(path.join(directory,'client/mods',retiredName)),false);
 const archived=readdirSync(path.join(directory,'retired-mods')).find(n=>n.endsWith(retiredName));assert.ok(archived);
 assert.equal(readFileSync(path.join(directory,'retired-mods',archived),'utf8'),'previous managed mod sentinel');
 assert.equal(readFileSync(path.join(directory,'client/mods/personal-extra.jar'),'utf8'),'unmanaged mod sentinel');
}
assert.equal(createHash('sha1').update(readFileSync(asset)).digest('hex'),assetHash);
assert.equal(createHash('sha512').update(readFileSync(modTarget)).digest('hex'),mod.sha512);
assert.equal(createHash('sha1').update(readFileSync(libraryTarget)).digest('hex'),library.sha1);
const second=await run(true);assert.equal(second.downloaded,0);assert.equal(second.repaired,0);
assert.equal(readFileSync(options,'utf8'),'personal-options-sentinel\n');assert.equal(readFileSync(save,'utf8'),'personal-world-sentinel\n');assert.equal(hash(originalRuntime),before);
const prepared=JSON.parse(readFileSync(path.join(directory,'prepared-game-runtime.json')));assert.equal(prepared.game_directory,path.join(directory,'client'));assert.equal(prepared.java,java);
const result={passed:true,directory,repaired_mod_asset_loader_library:true,regenerated_patched_neoforge_client:true,repaired_files:first.repaired,offline_second_pass:true,unchanged_files_downloaded_again:0,user_options_and_saves_preserved:true,human_runtime_preserved:true};
writeFileSync(path.join(root,'.tools/client-preparation-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
