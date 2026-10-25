import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {prepareTitleMedia,titleMediaPath} from './client-title-media.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
function fixture(){
 const base=path.join(root,'.tools/title-media-tests');mkdirSync(base,{recursive:true});
 const dir=mkdtempSync(path.join(base,'client-')),bytes=Buffer.from('pinned media fixture');
 const sha256=createHash('sha256').update(bytes).digest('hex');
 const media={format_version:1,sha256,size:bytes.length,url:`https://charizma1337.github.io/animeoncraft-updates/assets/title/${sha256}.zip`,width:1920,height:1080,fps:24,frames:238};
 return {dir,bytes,media};
}
test('fresh download, verified offline cache, repair of damaged media',async()=>{
 const f=fixture();let calls=0;const fetcher=async()=>{calls++;return new Response(f.bytes);};
 const file=await prepareTitleMedia(f.dir,f.media,{fetcher});assert.equal(calls,1);assert.deepEqual(readFileSync(file),f.bytes);
 await prepareTitleMedia(f.dir,f.media,{fetcher:()=>{throw Error('offline');}});
 writeFileSync(file,'damage');await prepareTitleMedia(f.dir,f.media,{fetcher});assert.equal(calls,2);assert.deepEqual(readFileSync(file),f.bytes);
});
test('invalid or interrupted download never becomes an installed archive',async()=>{
 const f=fixture(),file=titleMediaPath(f.dir,f.media);
 await assert.rejects(prepareTitleMedia(f.dir,f.media,{fetcher:async()=>new Response('truncated')}));assert.equal(existsSync(file),false);
 await assert.rejects(prepareTitleMedia(f.dir,f.media,{fetcher:async()=>new Response(Buffer.alloc(f.bytes.length+1))}));assert.equal(existsSync(file),false);
 await assert.rejects(prepareTitleMedia(f.dir,f.media,{fetcher:async()=>{throw Error('connection lost');}}));assert.equal(existsSync(file),false);
});
test('rejects unexpected host, traversal hash, unbounded size and extra metadata',async()=>{
 const f=fixture();for(const patch of [{url:'https://example.test/media.zip'},{sha256:'../../bad'},{size:256*1024**2},{surprise:true}])
  await assert.rejects(prepareTitleMedia(f.dir,{...f.media,...patch}));
});
