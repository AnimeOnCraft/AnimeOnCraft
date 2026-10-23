import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {copyFileSync,existsSync,lstatSync,mkdirSync,readFileSync,readdirSync} from 'node:fs';
import path from 'node:path';
import {atomicWrite} from './managed-client-download.mjs';

const legacyRevision='maps-only-v1',caveRevision='surface-caves-v2';
const minimap={waypoints_in_world:false,tracked_players_in_world:false,waypoints_on_minimap:true,tracked_players_on_minimap:true};
const worldMap={waypoints:true,render_waypoints:true,display_tracked_players:true};
const minimapCaves={minimap_cave_mode_allowed:true,minimap_auto_cave_mode:2,minimap_manual_cave_mode_start:2147483647,minimap_legible_cave_maps:true};
const worldMapCaves={cave_mode_allowed:true,auto_cave_mode:-1,default_cave_mode_type:1,legible_cave_maps:true};

function plainFile(file){
 if(existsSync(file))assert.ok(lstatSync(file).isFile()&&!lstatSync(file).isSymbolicLink(),'Map defaults require regular configuration files');
}
function patch(source,values){
 const eol=source.includes('\r\n')?'\r\n':'\n',lines=source.replaceAll('\r\n','\n').split('\n');
 for(const[key,value]of Object.entries(values)){
  const pattern=new RegExp(`^\\s*${key}\\s*=`);let found=false;
  // Update all occurrences: an old duplicate must not override the new default.
  for(let i=0;i<lines.length;i++)if(pattern.test(lines[i])){lines[i]=`${key} = ${value}`;found=true;}
  if(!found)lines.push(`${key} = ${value}`);
 }
 return lines.join(eol).replace(/(?:\r?\n)*$/,eol);
}
function configure(file,values,revision){
 plainFile(file);const before=existsSync(file)?readFileSync(file):null,after=Buffer.from(patch(before?.toString('utf8')??'',values));
 if(before?.equals(after))return;
 if(before){
  const backup=file+`.before-aoc-${revision}-${createHash('sha256').update(before).digest('hex')}.bak`;
  plainFile(backup);if(existsSync(backup))assert.ok(readFileSync(backup).equals(before));else copyFileSync(file,backup);
 }
 atomicWrite(file,after);
}
function profiles(config,kind){
 const directory=path.join(config,'xaero',kind,'profiles');
 if(existsSync(directory))assert.ok(lstatSync(directory).isDirectory()&&!lstatSync(directory).isSymbolicLink(),'Map profiles must be a real directory');
 mkdirSync(directory,{recursive:true});
 const names=new Set(['default.cfg',...readdirSync(directory,{withFileTypes:true}).filter(e=>e.name.endsWith('.cfg')&&(e.isFile()||e.isSymbolicLink())).map(e=>e.name)]);
 return [...names].map(name=>path.join(directory,name));
}
/** One migration per installation; later manual changes survive repair/preparation. */
export function applyClientMapDefaults(gameDirectory,policy){
 if(!policy)return {applied:false};
 const revision=policy.revision;
 assert.ok([legacyRevision,caveRevision].includes(revision),'Unsupported client map defaults revision');
 const config=path.join(gameDirectory,'config'),marker=path.join(config,'animeoncraft-map-defaults.json');
 plainFile(marker);
 let previous;
 if(existsSync(marker)){
  const value=JSON.parse(readFileSync(marker,'utf8'));
  assert.equal(value.format_version,1,'Invalid map defaults receipt');
  previous=value.revision;
  if(previous===revision || previous===caveRevision && revision===legacyRevision)return {applied:false,revision:previous};
 }
 const files=[];
 const preserveMarkerChoices=previous===legacyRevision;
 const miniValues={...(!preserveMarkerChoices?minimap:{}),...(revision===caveRevision?minimapCaves:{})};
 const mapValues={...(!preserveMarkerChoices?worldMap:{}),...(revision===caveRevision?worldMapCaves:{})};
 for(const[kind,values]of [['minimap',miniValues],['world-map',mapValues]])for(const file of profiles(config,kind)){configure(file,values,revision);files.push(path.relative(gameDirectory,file));}
 if(revision===caveRevision){
  const primary=path.join(config,'xaero/world-map/client.cfg');
  configure(primary,{cave_mode_start_y:2147483647},revision);files.push(path.relative(gameDirectory,primary));
 }
 atomicWrite(marker,JSON.stringify({format_version:1,revision},null,2)+'\n');
 return {applied:true,revision,files};
}
