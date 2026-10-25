import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync,copyFileSync} from 'node:fs';
import path from 'node:path';
import {installEcology} from './game-ecology.mjs';

const root=path.resolve(import.meta.dirname,'..'),work=path.join(root,'.tools/ecology-20261025');
const patch=JSON.parse(readFileSync(path.join(work,'patch.json'),'utf8'));
const checks=JSON.parse(readFileSync(path.join(work,'result.json'),'utf8'));
const optional=JSON.parse(readFileSync(path.join(work,'optional-result.json'),'utf8'));
assert.equal(checks.passed,true);assert.equal(checks.admission_sha256,patch.probe.sha256);
assert.equal(optional.passed,true);assert.equal(optional.server_sha256,patch.server.sha256);
const directory=path.join(work,'payload'),files=installEcology(directory);
const bytes=readFileSync(patch.server.path);
assert.equal(createHash('sha256').update(bytes).digest('hex'),patch.server.sha256);
const own=path.join(directory,'mods',path.basename(patch.server.path));copyFileSync(patch.server.path,own);
files.push({path:own,sha256:patch.server.sha256,bytes:bytes.length});
const result={local_only:true,installed:false,public_baseline_sha256:patch.public_baseline_sha256,
 files:files.map(file=>({...file,path:path.relative(directory,file.path).replaceAll('\\','/')}))};
writeFileSync(path.join(work,'payload-manifest.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
