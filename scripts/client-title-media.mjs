import assert from 'node:assert/strict';
import path from 'node:path';
import {obtainVerified,preparationProgress} from './managed-client-download.mjs';

export function validateTitleMedia(media){
 assert.equal(Object.keys(media).sort().join(','),'format_version,fps,frames,height,sha256,size,url,width');
 assert.equal(media.format_version,1);assert.match(media.sha256,/^[a-f0-9]{64}$/);
 assert.ok(Number.isSafeInteger(media.size)&&media.size>0&&media.size<=128*1024**2);
 assert.equal(media.width,1920);assert.equal(media.height,1080);assert.equal(media.fps,24);assert.equal(media.frames,238);
 assert.equal(media.url,`https://charizma1337.github.io/animeoncraft-updates/assets/title/${media.sha256}.zip`);
 return media;
}
export function titleMediaPath(gameDirectory,media){
 validateTitleMedia(media);assert.ok(path.isAbsolute(gameDirectory));
 return path.join(gameDirectory,'aoc-assets/title',media.sha256+'.zip');
}
export async function prepareTitleMedia(gameDirectory,media,{fetcher=fetch}={}){
 const target=titleMediaPath(gameDirectory,media);
 preparationProgress('FILES',0,media.size);
 const boundedFetch=async(url,options)=>{
  const response=await fetcher(url,options);
  assert.equal(response.status,200,'Title media download failed');
  const declared=Number(response.headers.get('content-length'));
  assert.ok(!declared||declared<=media.size,'Title media download exceeds pinned size');
  let received=0,last=0;
  const stream=response.body?.pipeThrough(new TransformStream({transform(chunk,controller){
   received+=chunk.byteLength;assert.ok(received<=media.size,'Title media download exceeds pinned size');
   if(received-last>=1024**2){preparationProgress('FILES',received,media.size);last=received;}
   controller.enqueue(chunk);
  }}));
  assert.ok(stream,'Empty title media response');
  return new Response(stream,{status:response.status,headers:response.headers});
 };
 await obtainVerified({url:media.url,target,algorithm:'sha256',hash:media.sha256,size:media.size,fetcher:boundedFetch});
 preparationProgress('FILES',media.size,media.size);
 return target;
}
