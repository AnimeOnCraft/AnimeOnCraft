// Prepare files locally; this script never uploads them or changes the installed app.
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync,copyFileSync,existsSync,renameSync} from 'node:fs';
import {createHash,randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const workspace=fileURLToPath(new URL('../',import.meta.url)),native=path.join(workspace,'apps/launcher/src-tauri');
const keyArgument=process.argv.indexOf('--key');
assert.ok(keyArgument>=0&&process.argv[keyArgument+1],'Usage: node scripts/prepare-launcher-update.mjs --key <private-key-file>');
const key=path.resolve(process.argv[keyArgument+1]),source=JSON.parse(readFileSync(path.join(native,'simple-update.json'),'utf8'));
assert.ok(source.endpoint&&source.public_key,'Configure HTTPS endpoint and public key in simple-update.json first');
const endpoint=new URL(source.endpoint);assert.equal(endpoint.protocol,'https:');assert.ok(!endpoint.username&&!endpoint.password&&!endpoint.search&&!endpoint.hash&&endpoint.pathname.endsWith('/latest.json'));
assert.equal(readFileSync(key+'.pub','utf8').trim(),source.public_key,'The signing key public file must match the embedded updater public key');
const version=JSON.parse(readFileSync(path.join(native,'tauri.conf.json'),'utf8')).version;assert.match(version,/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/);
const candidate=JSON.parse(readFileSync(path.join(workspace,`.tools/installer-candidate-${version}.json`),'utf8'));
assert.equal(candidate.version,version);
const input=path.join(native,`target/release/bundle/nsis/AnimeOnCraft_${version}_x64-setup.exe`);assert.equal(candidate.installer.path,path.normalize(input));
const bytes=readFileSync(input);assert.ok(bytes.length<=64*1024**2);assert.equal(createHash('sha256').update(bytes).digest('hex'),candidate.installer.sha256,'Rebuild the ordinary installer before signing');
// This key feeds the public updater. A local test build must never reach it.
const bootstrap=JSON.parse(readFileSync(path.join(workspace,'.tools/launcher-bootstrap/bootstrap.json'),'utf8'));
const origin=new URL(bootstrap.public_config.api_origin);assert.equal(origin.protocol,'https:','Public updates require a public HTTPS API build');
assert.equal(origin.origin,bootstrap.public_config.api_origin);
const executable=readFileSync(candidate.payload.source_path);
assert.equal(createHash('sha256').update(executable).digest('hex'),candidate.payload.source_sha256);
assert.ok(executable.includes(Buffer.from('friends-'+createHash('sha256').update(origin.origin).digest('hex'))),'Native API environment does not match the public bootstrap');
const directory=path.join(workspace,'.tools/launcher-releases',version);assert.equal(existsSync(directory),false,'Release output already exists; preserve it and inspect before retrying');
const parent=path.dirname(directory),staging=path.join(parent,`${version}.preparing-${randomUUID()}`);
mkdirSync(parent,{recursive:true});mkdirSync(staging);const filename=`AnimeOnCraft_${version}_x64-setup.exe`,artifact=path.join(staging,filename);copyFileSync(input,artifact);
const cli=path.join(workspace,'apps/launcher/node_modules/@tauri-apps/cli/tauri.js');
const result=spawnSync(process.execPath,[cli,'signer','sign','--private-key-path',key,'--app-version',version,artifact],{cwd:workspace,windowsHide:true,encoding:'utf8',timeout:30000,env:{...process.env,TAURI_SIGNING_PRIVATE_KEY_PASSWORD:process.env.TAURI_SIGNING_PRIVATE_KEY_PASSWORD??''}});
if(result.status!==0){
 const diagnostic=`${result.stdout??''}\n${result.stderr??''}`.toLowerCase();
 const reason=/password|decrypt/.test(diagnostic)?'KEY_PASSWORD_REQUIRED':/decode|secret key|base64/.test(diagnostic)?'KEY_FORMAT_INVALID':result.error?'SIGNER_UNAVAILABLE':'SIGNING_FAILED';
 throw new Error(`${reason}: signer output withheld; use the local secure signing helper for a protected key`);
}
const signature=readFileSync(artifact+'.sig','utf8').trim();
const raw=Buffer.from(signature,'base64').toString('utf8');assert.ok(raw.split('\n').some(line=>line.startsWith('trusted comment:')&&new RegExp(`(?:^|\\s)version:${version.replaceAll('.','\\.')}($|\\s)`).test(line)),'Signature must bind the release version');
const base=new URL('./',endpoint),url=new URL(`${version}/${filename}`,base).href;
writeFileSync(path.join(staging,'latest.json'),JSON.stringify({version,pub_date:new Date().toISOString(),platforms:{'windows-x86_64-nsis':{url,signature}}},null,2));
writeFileSync(path.join(staging,'release.json'),JSON.stringify({version,bytes:bytes.length,sha256:candidate.installer.sha256,endpoint:endpoint.href,artifact_url:url,published:false},null,2));
assert.equal(path.dirname(path.resolve(staging)),path.resolve(workspace,'.tools/launcher-releases'));
assert.equal(path.dirname(path.resolve(directory)),path.dirname(path.resolve(staging)));assert.equal(existsSync(directory),false);
renameSync(staging,directory);
console.log(`Prepared local updater files in ${directory}. Upload latest.json at the configured endpoint and the installer at ${url}. No files were published.`);
