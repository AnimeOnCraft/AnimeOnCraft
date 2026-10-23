import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

// Only the public marker identity. The legacy digest keeps packets compatible; no terrain image is shipped.
const root = fileURLToPath(new URL('../', import.meta.url));
const source = path.resolve(root, process.argv[2] ?? '.tools/states-pregen/relief');
const meta = JSON.parse(readFileSync(path.join(source, 'relief.json'), 'utf8'));
assert.equal(meta.format_version, 1);
assert.match(meta.world_id, /^[a-z0-9][a-z0-9-]{1,63}$/);
assert.equal(meta.image, 'relief.jpg');
const image = readFileSync(path.join(source, meta.image));
assert.ok(image.length > 0 && image.length <= 3145728);
for (const key of ['x0','z0','width_blocks','height_blocks']) assert.ok(Number.isSafeInteger(meta[key]));
const output = path.join(root, 'minecraft/integration/neoforge/src/main/resources/assets/animeoncraft/atlas');
mkdirSync(output, {recursive:true});
const manifest = {format_version:1,world_id:meta.world_id,sha256:createHash('sha256').update(image).digest('hex'),
  x0:meta.x0,z0:meta.z0,width_blocks:meta.width_blocks,height_blocks:meta.height_blocks};
writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({prepared:true,...manifest,image_included:false}));
