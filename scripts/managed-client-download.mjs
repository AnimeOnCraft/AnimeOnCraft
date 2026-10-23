import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {existsSync,mkdirSync,readFileSync,renameSync,rmSync,writeFileSync} from 'node:fs';
import path from 'node:path';

export const transferStats={downloaded:0,repaired:0};
export function preparationProgress(stage,completed,total){
 if(process.env.AOC_CLIENT_PROGRESS==='1')console.log('AOC_PREPARE '+JSON.stringify({stage,completed,total,...transferStats}));
}
export function containedPath(root,relative){
 assert.ok(typeof relative==='string'&&!path.isAbsolute(relative)&&!relative.includes('\\')&&!relative.split('/').some(p=>p==='..'||p==='.'||p===''),'Invalid managed path');
 const result=path.resolve(root,relative);assert.ok(result.startsWith(path.resolve(root)+path.sep),'Invalid managed path');return result;
}
export function matches(file,algorithm,expected){return existsSync(file)&&createHash(algorithm).update(readFileSync(file)).digest('hex')===expected;}
export function atomicWrite(target,bytes){
 mkdirSync(path.dirname(target),{recursive:true});const temporary=target+'.aoc-'+randomUUID()+'.tmp';
 try{writeFileSync(temporary,bytes,{flag:'wx'});renameSync(temporary,target);}finally{rmSync(temporary,{force:true});}
}
export async function obtainVerified({url,target,algorithm,hash,size,fetcher=fetch}){
 assert.ok(['sha1','sha256','sha512'].includes(algorithm)&&typeof hash==='string'&&new RegExp(`^[a-f0-9]{${{sha1:40,sha256:64,sha512:128}[algorithm]}}$`).test(hash),'Invalid managed hash');
 if(matches(target,algorithm,hash))return readFileSync(target);
 assert.equal(new URL(url).protocol,'https:','Managed downloads require HTTPS');
 const damaged=existsSync(target);let failure;
 for(let attempt=0;attempt<3;attempt++)try{
  const response=await fetcher(url,{signal:AbortSignal.timeout(120000)});assert.equal(response.status,200,'Download failed');
  const declared=Number(response.headers.get('content-length'));assert.ok(!declared||declared<=256*1024*1024,'Download too large');
  const bytes=Buffer.from(await response.arrayBuffer());assert.ok(bytes.length<=256*1024*1024,'Download too large');
  assert.equal(createHash(algorithm).update(bytes).digest('hex'),hash,'Download checksum mismatch');
  if(size)assert.equal(bytes.length,size,'Download size mismatch');
  atomicWrite(target,bytes);transferStats.downloaded++;if(damaged)transferStats.repaired++;return bytes;
 }catch(error){failure=error;}
 throw failure;
}
