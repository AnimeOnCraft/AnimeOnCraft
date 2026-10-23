import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtempSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
assert.ok(process.argv.length<=3,'Usage: node scripts/test-gameplay-installer-payload.mjs [version]');
const version=process.argv[2]??'0.7.26';assert.match(version,/^\d+\.\d+\.\d+$/);
const manifest=JSON.parse(readFileSync(path.join(root,`.tools/installer-candidate-${version}.json`),'utf8'));
assert.equal(manifest.version,version);
const fixture=mkdtempSync(path.join(root,'.tools/gameplay-installer-payload-'));
const sevenZip=process.env.AOC_SEVENZIP??'C:\\Program Files\\7-Zip\\7z.exe';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
assert.equal(hash(readFileSync(manifest.installer.path)),manifest.installer.sha256);
// Extraction exercises the exact shipped payload without running the installer or touching registration.
execFileSync(sevenZip,['x','-y',`-o${fixture}`,manifest.installer.path],{windowsHide:true,stdio:'pipe',timeout:60000});
assert.equal(hash(readFileSync(path.join(fixture,'animeoncraft-launcher.exe'))),manifest.payload.sha256,'Embedded NSIS executable differs from the packaged GUI application');
const source=path.join(root,'.tools/launcher-bootstrap');
const files=['bootstrap.json','minecraft/modpack/profile.json','minecraft/server/balance-profile.json','minecraft/server/balance-assets.json',
  'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar',
  'scripts/prepare-launcher-client.mjs','scripts/game-balance.mjs','runtime/node/node.exe','runtime/java/bin/java.exe'];
if(JSON.parse(readFileSync(path.join(source,'minecraft/modpack/profile.json'))).mods.some(mod=>mod.mod_id==='ftbquests')){
  files.push('minecraft/server/quest-assets.json');
  const quests=JSON.parse(readFileSync(path.join(source,'minecraft/server/quest-assets.json')));
  files.push(...quests.files.map(file=>quests.source_directory+'/'+file.path));
}
for(const file of files)assert.equal(hash(readFileSync(path.join(fixture,'bootstrap',file))),hash(readFileSync(path.join(source,file))),`Embedded file mismatch: ${file}`);
const config=JSON.parse(readFileSync(path.join(fixture,'bootstrap/bootstrap.json'),'utf8'));
assert.equal(config.public_config.api_origin,'http://127.0.0.1:3000');
assert.equal(config.public_config.server_host,'127.0.0.1');
const result={passed:true,checked_at:new Date().toISOString(),version,fixture_directory:fixture,
  installer_sha256:manifest.installer.sha256,embedded_executable_sha256:manifest.payload.sha256,
  compared_files:files.length,registry_changed:false,installed_launcher_changed:false,actual_installation_test:false};
writeFileSync(path.join(root,version==='0.7.26'?'.tools/gameplay-installer-payload-result.json':`.tools/gameplay-installer-payload-${version}-result.json`),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
