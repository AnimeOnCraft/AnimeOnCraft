import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {copyFileSync,existsSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const read=file=>readFileSync(path.isAbsolute(file)?file:path.join(root,file));
const json=file=>JSON.parse(read(file));
const hash=file=>createHash('sha256').update(read(file)).digest('hex');
assert.ok(process.argv.length<=3,'Usage: node scripts/verify-gameplay-candidate.mjs [version]');
const version=process.argv[2]??'0.7.26';assert.match(version,/^\d+\.\d+\.\d+$/);
const report=json(`.tools/installer-candidate-${version}.json`);
assert.equal(report.version,version);
assert.equal(report.release_eligible,false);
assert.equal(hash(report.installer.path),report.installer.sha256,'Installer changed after packaging');
assert.equal(hash(report.payload.source_path),report.payload.source_sha256,'EXE changed after packaging');
const companion='minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar';
const expected=hash(companion);
for(const file of [
  version==='0.7.26'?'.tools/modpack-mvp/client/mods/animeoncraft-admission-0.3.0-friends-dev.jar':`.tools/local-review-${version}/client/mods/animeoncraft-admission-0.3.0-friends-dev.jar`,
  '.tools/modpack-mvp/server/mods/animeoncraft-admission-0.3.0-friends-dev.jar',
  '.tools/launcher-bootstrap/'+companion,
  'apps/launcher/src-tauri/target/release/bootstrap/'+companion,
])assert.equal(hash(file),expected,`Inconsistent companion: ${file}`);
for(const prefix of ['.tools/launcher-bootstrap/','apps/launcher/src-tauri/target/release/bootstrap/']){
  const bootstrap=json(prefix+'bootstrap.json');
  assert.equal(bootstrap.admission_sha256,expected);
  assert.equal(bootstrap.public_config.api_origin,'http://127.0.0.1:3000');
  assert.equal(bootstrap.public_config.server_host,'127.0.0.1');
  assert.equal(hash(prefix+'scripts/game-balance.mjs'),hash('scripts/game-balance.mjs'));
  assert.equal(hash(prefix+'minecraft/server/balance-profile.json'),hash('minecraft/server/balance-profile.json'));
}
const server=json(version==='0.7.26'?'.tools/territory-protection-result.json':'.tools/magic-boundary-result.json');
assert.equal(server.passed,true);assert.equal(server.full_modpack,true);
if(version!=='0.7.26'){
  for(const field of ['magic_boundary','create_lifecycle','real_spell_summon','spell_active_raid_and_friendly_rules','helpful_healing_preserved','controller_physical_break_and_tick','controller_recovery','train_destination_and_multi_carriage'])assert.equal(server[field],true,`Missing candidate verification: ${field}`);
}
assert.equal(server.cancelled_boss_rewards_blocked,true);
const worldBoss=json('.tools/world-boss-early-stop-result.json');
assert.equal(worldBoss.passed,true);assert.equal(worldBoss.shared_world_boss,true);assert.equal(worldBoss.normal_shutdown_verified,true);
assert.equal(worldBoss.process_restart_verified,true,'Actual process restart was not checked');
assert.equal(worldBoss.full_modpack,true);assert.equal(worldBoss.early_stop,true);
assert.equal(worldBoss.admission_sha256,expected,'Shared boss evidence belongs to another companion build');
if(version==='0.7.28'){
  assert.equal(worldBoss.boss_health_15_players,1800);
  assert.equal(worldBoss.boss_health_30_players,3000);
  assert.equal(worldBoss.ordinary_wither_health,300);
  assert.equal(worldBoss.health_nbt_roundtrip,true);
  for(const evidence of [server,worldBoss]){
    assert.equal(evidence.runtime_spell_modifiers,111);
    assert.equal(evidence.spell_config_sync_verified,true);
  }
}
assert.equal(server.admission_sha256,expected,'Server gameplay evidence belongs to another companion build');
const preparation=json('.tools/client-preparation-result.json');
assert.equal(preparation.passed,true);assert.equal(preparation.human_runtime_preserved,true);
const payload=json(version==='0.7.26'?'.tools/gameplay-installer-payload-result.json':`.tools/gameplay-installer-payload-${version}-result.json`);
assert.equal(payload.passed,true);
assert.equal(payload.installer_sha256,report.installer.sha256,'Installer extraction evidence is stale');
const alias='apps/launcher/src-tauri/target/release/AnimeOnCraft-gameplay-local.exe';
const aliasCurrent=existsSync(path.join(root,alias))&&hash(alias)===report.payload.source_sha256;
if(!aliasCurrent){
  try{copyFileSync(report.payload.source_path,path.join(root,alias));}
  catch(error){
    if(error.code!=='EBUSY')throw error;
    console.log('The preview alias is running and was preserved. The verified primary executable remains available; close the preview and rerun to refresh its alias.');
  }
}
const manifest={version:report.version,verified_at:new Date().toISOString(),local_only:true,published:false,
  installer:report.installer,executable:report.payload.source_path,executable_sha256:report.payload.source_sha256,
  admission_sha256:expected,preview_alias_current:hash(alias)===report.payload.source_sha256,
  server_fixture:server,world_boss_fixture:worldBoss,client_preparation:preparation,installer_payload:payload,
  remaining:['Two actual clients: shared boss/war/capture/map/voice acceptance','PvP balance playtest','Extended mod interaction audit']};
writeFileSync(path.join(root,`.tools/gameplay-candidate-${version}.json`),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({verified:true,local_only:true,installer_bytes:report.installer.bytes,admission_sha256:expected}));
