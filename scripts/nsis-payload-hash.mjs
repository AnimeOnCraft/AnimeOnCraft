// Unsigned CLI2.12.1 NSIS packaging replaces one fixed bundle marker, then restores source.
import assert from 'node:assert/strict';
import {readFile,realpath} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const workspace=await realpath(fileURLToPath(new URL('../',import.meta.url)));
const input=path.resolve(process.argv[2]);
assert.equal(input,path.join(workspace,'apps/launcher/src-tauri/target/release/animeoncraft-launcher.exe'));
const cli=JSON.parse(await readFile(path.join(workspace,'apps/launcher/node_modules/@tauri-apps/cli/package.json'),'utf8'));
assert.equal(cli.version,'2.12.1','Review NSIS payload transformation after changing the CLI');
const source=await readFile(input);assert.ok(source.length>0&&source.length<64*1024**2);
// A console-subsystem executable opens cmd/conhost and dies when that window
// closes. Keep this packaging check so later releases cannot reintroduce it.
assert.equal(source.subarray(0,2).toString(),'MZ');
const pe=source.readUInt32LE(0x3c);
assert.equal(source.subarray(pe,pe+4).toString('hex'),'50450000');
assert.equal(source.readUInt16LE(pe+24+68),2,'Launcher must use Windows GUI subsystem, not a console');
const token=Buffer.from('__TAURI_BUNDLE_TYPE_VAR_UNK'),replacement=Buffer.from('__TAURI_BUNDLE_TYPE_VAR_NSS');
assert.equal(token.length,replacement.length);
const offset=source.indexOf(token);assert.ok(offset>=0,'Original bundle marker missing');assert.equal(source.indexOf(token,offset+1),-1,'Ambiguous bundle markers');
const payload=Buffer.from(source);replacement.copy(payload,offset);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
console.log(JSON.stringify({bytes:source.length,source_sha256:hash(source),payload_sha256:hash(payload),bundle_type:'nsis',cli_version:cli.version}));
