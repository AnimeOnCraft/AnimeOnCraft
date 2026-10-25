import assert from 'node:assert/strict';
import {existsSync,readFileSync,mkdirSync,statSync} from 'node:fs';
import path from 'node:path';
import {atomicWrite,matches} from './managed-client-download.mjs';

export const LOADING_JAR='animeoncraft-loading-1.0.0.jar';

function tomlHeader(text,values){
 const lines=text.split(/\r?\n/),section=lines.findIndex(line=>/^\s*\[/.test(line));
 const header=section<0?lines:lines.slice(0,section),tail=section<0?[]:lines.slice(section);
 for(const [key,value] of Object.entries(values)){
  const pattern=new RegExp(`^\\s*${key}\\s*=`);
  const found=header.map((line,index)=>pattern.test(line)?index:-1).filter(index=>index>=0);
  assert.ok(found.length<=1,`Duplicate loading setting: ${key}`);
  if(found.length)header[found[0]]=`${key} = ${value}`;
  else header.push(`${key} = ${value}`);
 }
 return [...header,...tail].join('\n');
}

/** Verify the service JAR first; activate its provider only after all files are safely in place. */
export function applyLoadingTheme(gameDirectory,{source,sha256}){
 assert.ok(path.isAbsolute(gameDirectory)&&path.isAbsolute(source),'Absolute loading paths required');
 assert.match(sha256,/^[a-f0-9]{64}$/);
 assert.ok(existsSync(source)&&statSync(source).size<8*1024*1024&&matches(source,'sha256',sha256),'Bundled loading theme checksum mismatch');
 const configDir=path.join(gameDirectory,'config'),fml=path.join(configDir,'fml.toml');
 const before=existsSync(fml)?readFileSync(fml,'utf8'):'';
 const after=tomlHeader(before,{earlyWindowProvider:'"animeoncraft"',earlyWindowControl:'true',earlyWindowFBScale:'2'});
 const optionsFile=path.join(gameDirectory,'options.txt');
 const options=existsSync(optionsFile)?readFileSync(optionsFile,'utf8'):'';
 const dark=options.split(/\r?\n/).filter(line=>!line.startsWith('darkMojangStudiosBackground:'));
 while(dark.at(-1)==='')dark.pop();
 dark.push('darkMojangStudiosBackground:true','');
 const mods=path.join(gameDirectory,'mods');mkdirSync(mods,{recursive:true});mkdirSync(configDir,{recursive:true});
 const target=path.join(mods,LOADING_JAR);
 if(!matches(target,'sha256',sha256))atomicWrite(target,readFileSync(source));
 if(before&&before!==after&&!existsSync(fml+'.before-animeoncraft'))atomicWrite(fml+'.before-animeoncraft',before);
 if(dark.join('\n')!==options)atomicWrite(optionsFile,dark.join('\n'));
 if(after!==before)atomicWrite(fml,after);
 return {file:target,sha256,provider:'animeoncraft'};
}
