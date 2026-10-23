import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {existsSync,mkdirSync,mkdtempSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {patchToml,applyBalanceConfig,installBalancePack} from './game-balance.mjs';

const root=fileURLToPath(new URL('../',import.meta.url));
// A working local datapack is insufficient if Git silently excludes its source resources.
const assets=JSON.parse(readFileSync(path.join(root,'minecraft/server/balance-assets.json'),'utf8'));
const sourceFiles=new Set(execFileSync('git',['ls-files','--cached','--others','--exclude-standard','--','minecraft/modpack/balance-pack'],{cwd:root,encoding:'utf8',windowsHide:true}).trim().split(/\r?\n/));
for(const asset of assets.files)assert.ok(sourceFiles.has('minecraft/modpack/balance-pack/'+asset.path),`Balance source excluded from repository: ${asset.path}`);
const directory=mkdtempSync(path.join(root,'.tools/balance-test-'));
const original='# preserve custom settings\n[Misc]\n\tspellGriefing = true\n\tcustom_setting = 7\n[Other]\n\tspellGriefing = true\n';
const patched=patchToml(original,{Misc:{spellGriefing:false,manaRegenMultiplier:0.9}});
assert.match(patched,/custom_setting = 7/);assert.match(patched,/\[Other\]\n\tspellGriefing = true/);
assert.equal(patchToml(patched,{Misc:{spellGriefing:false,manaRegenMultiplier:0.9}}),patched);
applyBalanceConfig(directory);
const config=path.join(directory,'config/irons_spellbooks-server.toml');
writeFileSync(config,readFileSync(config,'utf8')+'\n[Custom]\nkeep_me = 42\n');
applyBalanceConfig(directory);assert.match(readFileSync(config,'utf8'),/keep_me = 42/);
installBalancePack(directory,'fixture-world');
assert.throws(()=>installBalancePack(directory,'..'));
const pack=path.join(directory,'fixture-world/datapacks/animeoncraft-balance');
for(const retired of assets.retired_files??[]){
  const file=path.join(pack,retired.path);mkdirSync(path.dirname(file),{recursive:true});
  writeFileSync(file,JSON.stringify({'neoforge:conditions':[{type:'neoforge:false'}]},null,2)+'\n');
}
installBalancePack(directory,'fixture-world');
for(const retired of assets.retired_files??[]){
  assert.equal(existsSync(path.join(pack,retired.path)),false,'Retired override still disables vanilla crafting');
  assert.ok(existsSync(path.join(directory,'fixture-world/aoc-retired-balance',retired.path+'.'+retired.sha256+'.disabled')));
}
installBalancePack(directory,'fixture-world');
if(assets.retired_files?.length){
  const file=path.join(pack,assets.retired_files[0].path);writeFileSync(file,'{"custom":true}');
  assert.throws(()=>installBalancePack(directory,'fixture-world'),/was edited/);
  assert.equal(readFileSync(file,'utf8'),'{"custom":true}');
}
const recipe=JSON.parse(readFileSync(path.join(pack,'data/create/recipe/sequenced_assembly/precision_mechanism.json'),'utf8'));
const total=recipe.results.reduce((sum,result)=>sum+(result.chance??1),0);
assert.equal(recipe.results[0].chance/total,0.9);assert.equal(recipe.loops,5);
console.log(JSON.stringify({passed:true,fixture_directory:directory,main_world_touched:false,precision_mechanism_success:0.9,config_preserved:true}));
