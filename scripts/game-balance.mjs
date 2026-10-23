import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {copyFileSync,existsSync,mkdirSync,readFileSync,renameSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {applyEconomyConfig,installEconomyServerConfig} from './game-economy.mjs';

const root=fileURLToPath(new URL('../',import.meta.url));
export const balanceProfile=JSON.parse(readFileSync(path.join(root,'minecraft/server/balance-profile.json'),'utf8'));
const assets=JSON.parse(readFileSync(path.join(root,'minecraft/server/balance-assets.json'),'utf8'));
const quests=JSON.parse(readFileSync(path.join(root,'minecraft/server/quest-assets.json'),'utf8'));

export function patchToml(source,sections){
  const lines=source.replaceAll('\r\n','\n').split('\n');
  for(const [section,values] of Object.entries(sections)){
    let start=lines.findIndex(line=>line.trim()===`[${section}]`);
    if(start<0){lines.push('',`[${section}]`);start=lines.length-1;}
    let end=lines.findIndex((line,index)=>index>start&&line.trim().startsWith('['));
    if(end<0)end=lines.length;
    for(const [key,value] of Object.entries(values)){
      assert.match(key,/^[A-Za-z_][A-Za-z0-9_]*$/);
      const index=lines.findIndex((line,index)=>index>start&&index<end&&new RegExp(`^\\s*${key}\\s*=`).test(line));
      const assignment=`\t${key} = ${JSON.stringify(value)}`;
      if(index>=0)lines[index]=assignment;
      else{lines.splice(end,0,assignment);end++;}
    }
  }
  return lines.join('\n').replace(/\n*$/,'\n');
}

function destination(base,relative){
  const resolved=path.resolve(base,relative);
  assert.ok(resolved.startsWith(path.resolve(base)+path.sep),'Balance file must stay in its target directory');
  return resolved;
}
function save(target,bytes){
  mkdirSync(path.dirname(target),{recursive:true});
  if(existsSync(target)){
    const old=readFileSync(target);if(old.equals(Buffer.from(bytes)))return;
    const hash=createHash('sha256').update(old).digest('hex').slice(0,12);
    copyFileSync(target,`${target}.before-aoc-balance-${hash}`);
  }
  writeFileSync(target,bytes);
}
export function applyBalanceConfig(gameDirectory){
  applyEconomyConfig(gameDirectory,patchToml);
  const config=path.join(gameDirectory,'config');
  for(const [relative,sections] of Object.entries(balanceProfile.toml)){
    const target=destination(config,relative);
    save(target,patchToml(existsSync(target)?readFileSync(target,'utf8'):'',sections));
  }
  for(const [relative,values] of Object.entries(balanceProfile.json)){
    const target=destination(config,relative);
    const old=existsSync(target)?JSON.parse(readFileSync(target,'utf8')):{};
    save(target,JSON.stringify({...old,...values},null,2)+'\n');
  }
  for(const file of quests.files){
    const bytes=readFileSync(destination(path.join(root,quests.source_directory),file.path));
    assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256,'Quest asset differs from its manifest');
    save(destination(path.join(config,'ftbquests/quests'),file.path),bytes);
  }
}
export function installBalancePack(serverDirectory,worldName){
  assert.ok(worldName&&!['.','..'].includes(worldName)&&path.basename(worldName)===worldName);
  installEconomyServerConfig(serverDirectory,worldName,patchToml);
  // NeoForge Type.SERVER reads world/serverconfig, independently of common config/ files.
  for(const [relative,sections] of Object.entries(balanceProfile.server_toml??{})){
    const target=destination(path.join(serverDirectory,worldName,'serverconfig'),relative);
    save(target,patchToml(existsSync(target)?readFileSync(target,'utf8'):'',sections));
  }
  const target=path.join(serverDirectory,worldName,'datapacks/animeoncraft-balance');
  const source=path.join(root,'minecraft/modpack/balance-pack');
  // Retire only exact shipped overrides. Preserve unknown local edits for review.
  for(const retired of assets.retired_files??[]){
    assert.ok(!assets.files.some(file=>file.path===retired.path),'Retired balance file still active');
    const file=destination(target,retired.path);
    if(!existsSync(file))continue;
    assert.equal(createHash('sha256').update(readFileSync(file)).digest('hex'),retired.sha256,`Retired balance override was edited; preserve and review: ${file}`);
    const archive=destination(path.join(serverDirectory,worldName,'aoc-retired-balance'),retired.path);
    mkdirSync(path.dirname(archive),{recursive:true});
    const backup=`${archive}.${retired.sha256}.disabled`;
    if(existsSync(backup))assert.equal(createHash('sha256').update(readFileSync(backup)).digest('hex'),retired.sha256,'Retired balance backup changed');
    renameSync(file,backup);
  }
  for(const relative of ['pack.mcmeta',...assets.files.map(item=>item.path)]){
    const bytes=readFileSync(destination(source,relative));
    const asset=assets.files.find(item=>item.path===relative);
    if(asset)assert.equal(createHash('sha256').update(bytes).digest('hex'),asset.sha256,'Balance asset differs from its reviewed manifest');
    save(destination(target,relative),bytes);
  }
}
