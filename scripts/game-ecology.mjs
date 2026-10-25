import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {copyFileSync,existsSync,mkdirSync,readFileSync,renameSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {patchToml} from './game-balance.mjs';

const root=fileURLToPath(new URL('../',import.meta.url));
export const ecologyProfile=JSON.parse(readFileSync(path.join(root,'minecraft/server/ecology-profile.json'),'utf8'));
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');

/** Stage only into a stopped server directory. Unknown InControl rules require review. */
export function installEcology(directory){
  assert.ok(path.isAbsolute(directory),'An absolute server directory is required');
  const config=path.join(directory,'config'),plans=[];
  const plan=(relative,bytes)=>plans.push([path.join(config,relative),Buffer.from(bytes)]);
  const spawn=path.join(config,'incontrol/spawn.json');
  const receiptPath=path.join(config,'animeoncraft-ecology-receipt.json');
  const receipt=existsSync(receiptPath)?JSON.parse(readFileSync(receiptPath,'utf8')):{};
  const rules=JSON.stringify(ecologyProfile.spawn_rules,null,2)+'\n';
  if(existsSync(spawn)){
    const old=readFileSync(spawn);
    assert.ok(JSON.stringify(JSON.parse(old))==='[]'||digest(old)===digest(rules)||digest(old)===receipt.spawn_sha256,
      'Unknown InControl spawn rules: preserve and review before applying ecology');
  }
  plan('incontrol/spawn.json',rules);
  plan('animeoncraft-ecology.json',JSON.stringify(ecologyProfile.night,null,2)+'\n');
  for(const [relative,sections] of Object.entries(ecologyProfile.toml)){
    assert.equal(path.basename(relative),relative,'TOML filename must be local');
    const target=path.join(config,'cleanmaid',relative);
    plan('cleanmaid/'+relative,patchToml(existsSync(target)?readFileSync(target,'utf8'):'',sections));
  }
  const overlay=JSON.parse(readFileSync(path.join(root,'minecraft/server/optimizations.json'),'utf8'));
  for(const slug of ecologyProfile.required_mods){
    const mod=overlay.mods.find(mod=>mod.slug===slug);assert.ok(mod,'Missing pinned ecology mod: '+slug);
    assert.equal(path.basename(mod.file.name),mod.file.name);
    const bytes=readFileSync(path.join(root,'.tools/server-optimization-jars',mod.file.name));
    assert.equal(digest(bytes),mod.file.sha256,'Ecology JAR hash mismatch');
    plans.push([path.join(directory,'mods',mod.file.name),bytes]);
  }
  plan('animeoncraft-ecology-receipt.json',JSON.stringify({schema_version:1,spawn_sha256:digest(rules)},null,2)+'\n');
  // Validate everything above before writing. Preserve previous bytes for rollback.
  for(const [target,bytes] of plans){
    mkdirSync(path.dirname(target),{recursive:true});
    if(existsSync(target)){
      const old=readFileSync(target);if(old.equals(bytes))continue;
      const backup=target+'.before-aoc-ecology-'+digest(old).slice(0,12);
      if(existsSync(backup))assert.equal(digest(readFileSync(backup)),digest(old));
      else copyFileSync(target,backup);
    }
    writeFileSync(target+'.aoc-ecology.tmp',bytes);renameSync(target+'.aoc-ecology.tmp',target);
  }
  return plans.map(([target,bytes])=>({path:target,sha256:digest(bytes),bytes:bytes.length}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  assert.equal(process.argv.length,3,'Usage: node scripts/game-ecology.mjs ABSOLUTE_STOPPED_SERVER_DIRECTORY');
  console.log(JSON.stringify(installEcology(path.resolve(process.argv[2])),null,2));
}
