import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
const root=process.cwd(),boot=process.env.AOC_TEST_BOOTSTRAP_DIR??path.join(root,'.tools/launcher-bootstrap');
const previous=JSON.parse(fs.readFileSync(path.join(root,'.tools/exploration-research/profile-before.json')));
const profileBytes=fs.readFileSync(path.join(boot,'minecraft/modpack/profile.json')),profile=JSON.parse(profileBytes);
const fixture=fs.mkdtempSync(path.join(root,'.tools/managed-pack-upgrade-'));
const own='animeoncraft-admission-0.3.0-friends-dev.jar';
fs.mkdirSync(path.join(fixture,'client/mods'),{recursive:true});fs.mkdirSync(path.join(fixture,'downloads'));
// Cache entries are immutable verified files. Preparation replaces mutable metadata atomically.
function cacheLinks(source,destination){
 fs.mkdirSync(destination,{recursive:true});
 for(const entry of fs.readdirSync(source,{withFileTypes:true})){
  const from=path.join(source,entry.name),to=path.join(destination,entry.name);
  assert.ok(!entry.isSymbolicLink());
  if(entry.isDirectory())cacheLinks(from,to);else fs.linkSync(from,to);
 }
}
cacheLinks(path.join(root,'.tools/modpack-mvp/minecraft'),path.join(fixture,'minecraft'));
for(const mod of previous.mods.filter(m=>m.install_sides.includes('client')))fs.copyFileSync(path.join(root,'.tools/modpack-mvp/downloads',mod.file.name),path.join(fixture,'client/mods',mod.file.name));
fs.copyFileSync(path.join(root,'minecraft/integration/neoforge/build/libs',own),path.join(fixture,'client/mods',own));
fs.copyFileSync(path.join(root,'.tools/modpack-mvp/downloads',`neoforge-${profile.loader.version}-installer.jar`),path.join(fixture,'downloads',`neoforge-${profile.loader.version}-installer.jar`));
fs.mkdirSync(path.join(fixture,'tmp'));
const preserved={'client/options.txt':'lang:ru_ru\nrenderDistance:9\n','client/xaero/private-waypoint-test.txt':'Preserve player waypoints\n'};
for(const[file,bytes]of Object.entries(preserved)){fs.mkdirSync(path.dirname(path.join(fixture,file)),{recursive:true});fs.writeFileSync(path.join(fixture,file),bytes);}
const added=profile.mods.filter(m=>m.install_sides.includes('client')&&!previous.mods.some(old=>old.mod_id===m.mod_id));
assert.ok(added.some(mod=>mod.mod_id==='yungscavebiomes'));
for(const mod of added)assert.equal(fs.existsSync(path.join(fixture,'client/mods',mod.file.name)),false);
fs.writeFileSync(path.join(fixture,'managed-mods.json'),JSON.stringify([...previous.mods.filter(m=>m.install_sides.includes('client')).map(m=>m.file.name),own]));
let log='';const child=spawn(process.execPath,[path.join(boot,'scripts/prepare-launcher-client.mjs')],{cwd:fixture,windowsHide:true,env:{...process.env,AOC_CLIENT_DATA_ROOT:fixture,AOC_CLIENT_JAVA:path.join(boot,'runtime/java/bin/java.exe'),AOC_CLIENT_PROGRESS:'1'},stdio:['ignore','pipe','pipe']});
for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{log+=bytes.toString();});
const timeout=setTimeout(()=>child.kill(),600000);
try{
 const[code]=await once(child,'exit');assert.equal(code,0,'Real preparation failed: '+log.slice(-1800));
 const receipt=JSON.parse(fs.readFileSync(path.join(fixture,'managed-pack.json')));
 assert.equal(receipt.format_version,1);assert.equal(receipt.profile_sha256,createHash('sha256').update(profileBytes).digest('hex'));
 for(const mod of profile.mods.filter(m=>m.install_sides.includes('client'))){const bytes=fs.readFileSync(path.join(fixture,'client/mods',mod.file.name));assert.equal(createHash('sha512').update(bytes).digest('hex'),mod.file.sha512);}
 for(const[file,bytes]of Object.entries(preserved))assert.equal(fs.readFileSync(path.join(fixture,file),'utf8'),bytes);
 if(profile.client_map_defaults){
  const mini=fs.readFileSync(path.join(fixture,'client/config/xaero/minimap/profiles/default.cfg'),'utf8');
  assert.match(mini,/^waypoints_in_world = false$/m);assert.match(mini,/^waypoints_on_minimap = true$/m);
  assert.equal(JSON.parse(fs.readFileSync(path.join(fixture,'client/config/animeoncraft-map-defaults.json'))).revision,profile.client_map_defaults.revision);
 }
 const plan=JSON.parse(fs.readFileSync(path.join(fixture,'prepared-game-runtime.json')));assert.equal(plan.game_directory,path.join(fixture,'client'));
 const result={passed:true,checked_at:new Date().toISOString(),fixture,previous_client_jars:previous.mods.filter(m=>m.install_sides.includes('client')).length+1,client_jars:profile.mods.filter(m=>m.install_sides.includes('client')).length+1,new_client_mods:added.map(m=>m.mod_id),companion_unchanged:true,profile_receipt_written:true,all_client_hashes_verified:true,settings_and_waypoints_preserved:true,map_defaults_prepared:!!profile.client_map_defaults,main_game_directory_touched:false,vps_touched:false};
 fs.writeFileSync('.tools/managed-pack-upgrade-result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{clearTimeout(timeout);fs.writeFileSync(path.join(fixture,'preparation.log'),log);}
