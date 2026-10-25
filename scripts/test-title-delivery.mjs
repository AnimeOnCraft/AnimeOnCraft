// Exercise the real archive through the production downloader without publishing or changing a player install.
import assert from 'node:assert/strict';
import {createReadStream} from 'node:fs';
import {mkdtempSync,mkdirSync,readFileSync,writeFileSync,statSync} from 'node:fs';
import path from 'node:path';
import {Readable} from 'node:stream';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {prepareTitleMedia} from './client-title-media.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const media=JSON.parse(readFileSync(path.join(root,'minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/textures/title/media.json')));
const archive=path.join(root,'.tools/title-media',media.sha256+'.zip');
assert.equal(createHash('sha256').update(readFileSync(archive)).digest('hex'),media.sha256);
const jar=readFileSync(path.join(root,'minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar'));
assert.ok(jar.length<16*1024**2);assert.ok(!jar.includes(Buffer.from('frame_0001.jpg')));assert.ok(jar.includes(Buffer.from('poster.jpg')));
const base=path.join(root,'.tools/title-delivery-20261025');mkdirSync(base,{recursive:true});
const directory=mkdtempSync(path.join(base,'client-'));let calls=0;
const fetcher=async(url)=>{assert.equal(url,media.url);calls++;return new Response(Readable.toWeb(createReadStream(archive)),{headers:{'content-length':String(media.size)}});};
const target=await prepareTitleMedia(directory,media,{fetcher});assert.equal(calls,1);
await prepareTitleMedia(directory,media,{fetcher:()=>{throw Error('NETWORK_DISABLED');}});
writeFileSync(target,'damaged');await prepareTitleMedia(directory,media,{fetcher});assert.equal(calls,2);assert.equal(statSync(target).size,media.size);
const result={passed:true,local_only:true,published:false,archive_sha256:media.sha256,archive_bytes:media.size,mod_bytes:jar.length,
 real_archive_download_and_repair:true,offline_cache:true,external_media_not_in_mod:true,streamed_bytes_bounded:true,directory};
writeFileSync(path.join(base,'media-test.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
