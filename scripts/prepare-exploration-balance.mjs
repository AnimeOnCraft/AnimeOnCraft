import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {jarEntry} from './jar-entry.mjs';
const root=process.cwd(), policy=JSON.parse(fs.readFileSync('minecraft/server/exploration-policy.json'));
const profile=JSON.parse(fs.readFileSync('minecraft/modpack/profile.json'));
const manifestPath='minecraft/server/balance-assets.json',manifest=JSON.parse(fs.readFileSync(manifestPath));
const hash=b=>createHash('sha256').update(b).digest('hex');
assert.equal(policy.format_version,1);assert.equal(policy.minecraft,profile.minecraft);
assert.ok(policy.wda_valuable_pool_chance>0&&policy.wda_valuable_pool_chance<=1);
function names(bytes){
 let end=bytes.length-22;for(;end>=Math.max(0,bytes.length-65557);end--)if(bytes.readUInt32LE(end)===0x06054b50&&end+22+bytes.readUInt16LE(end+20)===bytes.length)break;
 assert.ok(end>=0);const count=bytes.readUInt16LE(end+10);let offset=bytes.readUInt32LE(end+16);const result=[];
 for(let i=0;i<count;i++){assert.equal(bytes.readUInt32LE(offset),0x02014b50);const length=bytes.readUInt16LE(offset+28);result.push(bytes.subarray(offset+46,offset+46+length).toString());offset+=46+length+bytes.readUInt16LE(offset+30)+bytes.readUInt16LE(offset+32);}
 return result;
}
const valuable=new Set(policy.valuable_items);
function valuablePool(value){
 if(!value||typeof value!=='object')return false;
 if(typeof value.name==='string'&&(valuable.has(value.name)||/^minecraft:diamond_(sword|axe|pickaxe|shovel|hoe|helmet|chestplate|leggings|boots)$/.test(value.name)||value.name.startsWith('minecraft:netherite_')))return true;
 return Object.values(value).some(v=>Array.isArray(v)?v.some(valuablePool):valuablePool(v));
}
const report={prepared_at:new Date().toISOString(),structure_sets:[],loot_tables:[],advancement_repairs:[],valuable_pools:0,scope:'local, new chunks only'};
for(const [id,multiplier]of Object.entries(policy.structure_frequency_multipliers)){
 assert.ok(multiplier>0&&multiplier<=1);
 const mod=profile.mods.find(m=>m.mod_id===id);assert.ok(mod,id);
 const source=path.join(root,'.tools/modpack-research-jars',mod.file.name),bytes=fs.readFileSync(source);
 assert.equal(createHash('sha512').update(bytes).digest('hex'),mod.file.sha512);
 for(const name of names(bytes)){
  const structure=name.startsWith('data/'+id+'/worldgen/structure_set/')&&name.endsWith('.json');
  const loot=id==='dungeons_arise'&&name.startsWith('data/'+id+'/loot_table/chests/')&&name.endsWith('.json');
  const advancement=id==='dungeons_arise'&&['data/dungeons_arise/advancement/find_thornborn_towers.json','data/dungeons_arise/advancement/find_fishing_hut.json'].includes(name);
  if(!structure&&!loot&&!advancement)continue;
  const original=jarEntry(bytes,name),data=JSON.parse(original);
  if(structure){
   assert.ok(['minecraft:random_spread','moogs_structures:advanced_random_spread'].includes(data.placement.type),name);
   const before=data.placement.frequency??1;data.placement.frequency=Number((before*multiplier).toFixed(8));
   report.structure_sets.push({id:name.replace(/^data\/([^/]+)\/worldgen\/structure_set\/(.*)\.json$/,'$1:$2'),before,after:data.placement.frequency,spacing:data.placement.spacing,separation:data.placement.separation});
  }else if(loot){
   let changed=0;
   for(const pool of data.pools??[])if(valuablePool(pool)){
    pool.conditions??=[];pool.conditions.push({condition:'minecraft:random_chance',chance:policy.wda_valuable_pool_chance});changed++;
   }
   if(!changed)continue;
   report.valuable_pools+=changed;report.loot_tables.push({id:name.replace(/^data\/([^/]+)\/loot_table\/(.*)\.json$/,'$1:$2'),valuable_pools:changed});
  }else{
   assert.equal(data.parent,'dungeons_arise:find_small_prairie_house');
   data.parent='dungeons_arise:wda_root';
   if(data.display?.icon?.item){data.display.icon.id=data.display.icon.item;delete data.display.icon.item;}
   report.advancement_repairs.push(name.replace(/^data\/([^/]+)\/advancement\/(.*)\.json$/,'$1:$2'));
  }
  const output=Buffer.from(JSON.stringify(data,null,2)+'\n'),target=path.resolve('minecraft/modpack/balance-pack',name);
  assert.ok(target.startsWith(path.resolve('minecraft/modpack/balance-pack')+path.sep));
  fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,output);
  const entry={path:name,source_jar:mod.file.name,source_sha256:hash(original),sha256:hash(output)};
  const index=manifest.files.findIndex(f=>f.path===name);if(index<0)manifest.files.push(entry);else manifest.files[index]=entry;
 }
}
assert.ok(report.structure_sets.some(s=>s.id.startsWith('dungeons_arise:')));assert.ok(report.structure_sets.some(s=>s.id.startsWith('mvs:')));assert.ok(report.valuable_pools>0);
fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2)+'\n');
fs.mkdirSync('.tools/exploration-research',{recursive:true});fs.writeFileSync('.tools/exploration-research/balance-result.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({structure_sets:report.structure_sets.length,loot_tables:report.loot_tables.length,valuable_pools:report.valuable_pools,wda_frequency_multiplier:policy.structure_frequency_multipliers.dungeons_arise,mvs_frequency_multiplier:policy.structure_frequency_multipliers.mvs}));
