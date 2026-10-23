import assert from 'node:assert/strict';
import {createServer as httpsServer} from 'node:https';
import {createServer as httpServer} from 'node:http';
import {createServer as netServer} from 'node:net';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,readdir,realpath,unlink} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const workspace=fileURLToPath(new URL('../',import.meta.url)).replace(/[\\/]$/,'');
assert.equal(process.platform,'win32');
for(const port of [39441,39442,19223]){const probe=netServer();await new Promise((resolve,reject)=>{probe.once('error',reject);probe.listen(port,'127.0.0.1',resolve);});await new Promise(resolve=>probe.close(resolve));}
const directory=path.join(workspace,'.tools',`pack-acceptance-${randomUUID().replaceAll('-','')}`);
await mkdir(directory);assert.equal(await realpath(directory),directory);
await mkdir(path.join(directory,'local'));
const run=async(command,args,env={})=>{
 const child=spawn(command,args,{cwd:workspace,env:{...process.env,...env},windowsHide:true,stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',d=>process.stdout.write(d));child.stderr.on('data',d=>process.stderr.write(d));
 const timer=setTimeout(()=>child.kill(),300000);
 try{const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});assert.equal(code,0,`Fixture command failed: ${path.basename(command)}`);}finally{clearTimeout(timer);}
};
const cargo=path.join(process.env.USERPROFILE,'.cargo/bin/cargo.exe');
await run(cargo,['test','--locked','--manifest-path','apps/launcher/src-tauri/Cargo.toml','export_pack_acceptance_repository','--','--ignored','--test-threads=1'],{AOC_PACK_ACCEPTANCE_DIRECTORY:directory});
await run('pwsh.exe',['-NoProfile','-NonInteractive','-File','scripts/test-pack-certificate.ps1','-OutputDirectory',directory]);
const releases=new Map();for(const seq of [1,2]){const values=JSON.parse(await readFile(path.join(directory,`release${seq}.json`),'utf8'));releases.set(seq,new Map(Object.entries(values).map(([key,value])=>[key,Buffer.from(value)])));}
const requests=[];let mode='good',release=1,slowStarted=false;
const tls=httpsServer({key:await readFile(path.join(directory,'key.pem')),cert:await readFile(path.join(directory,'certificate.pem')),minVersion:'TLSv1.2'},(request,response)=>{
 requests.push({url:request.url,method:request.method,encoding:request.headers['accept-encoding'],auth:!!request.headers.authorization,cookie:!!request.headers.cookie});
 if(request.method!=='GET'||!request.url?.startsWith('/aoc/v1/')){response.writeHead(404);response.end();return;}
 const key=request.url.slice('/aoc/v1'.length);
 if(key==='/metadata/client-valid.json'){response.writeHead(200);response.end('{"ok":true}');return;}
 if(key==='/metadata/client-oversize.json'){response.writeHead(200,{'content-length':2*1024**2+1});response.end();return;}
 if(key==='/metadata/client-duplicate.json'){response.writeHead(200);response.end('{"x":1,"x":2}');return;}
 if(key==='/metadata/client-encoding.json'){response.writeHead(200,{'content-encoding':'gzip'});response.end('{}');return;}
 if(key==='/metadata/client-redirect.json'){response.writeHead(302,{location:'https://localhost:39441/aoc/v1/metadata/client-valid.json'});response.end();return;}
 if(key==='/metadata/99.root.json'){response.writeHead(500);response.end();return;}
 if(mode==='slow'&&key.endsWith('.snapshot.json')){slowStarted=true;response.writeHead(200);response.flushHeaders();return;}
 let bytes=releases.get(release).get(key);
 if(mode==='tamper'&&key==='/metadata/timestamp.json'){const value=JSON.parse(bytes);value.signed.version=99;bytes=Buffer.from(JSON.stringify(value));}
 if(!bytes){response.writeHead(404);response.end();return;}
 response.writeHead(200,{'content-length':bytes.length,'content-type':key.startsWith('/artifacts/')?'application/octet-stream':'application/json'});response.end(bytes);
});
tls.on('tlsClientError',()=>{});
const controlToken=randomBytes(32).toString('hex');
const control=httpServer(async(request,response)=>{
 if(request.method!=='POST'||!['/mode','/state'].includes(request.url)||request.headers.authorization!==`Bearer ${controlToken}`){response.writeHead(404);response.end();return;}
 if(request.url==='/state'){response.writeHead(200,{'content-type':'application/json'});response.end(JSON.stringify({slowStarted}));return;}
 const chunks=[];let size=0;for await(const chunk of request){size+=chunk.length;if(size>128){response.writeHead(413);response.end();return;}chunks.push(chunk);}
 try{const choice=JSON.parse(Buffer.concat(chunks));assert.deepEqual(Object.keys(choice),['mode']);assert.ok(['good','slow','tamper'].includes(choice.mode));mode=choice.mode;if(mode==='slow')slowStarted=false;response.writeHead(204);response.end();}catch{response.writeHead(400);response.end();}
});
const listen=(server,port)=>new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
const game=path.join(directory,'local','AnimeOnCraft.InstallEngineDevelopment.v1','install','game-instance');
const jar=path.join(game,'mods/fixture.jar'),world=path.join(game,'saves/world.dat');
const bytesFor=seq=>Buffer.from(`signed native fixture release ${seq}`);
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const metadataSnapshot=async()=>{const out={};async function walk(base,current){for(const item of await readdir(current,{withFileTypes:true})){const next=path.join(current,item.name);if(item.isDirectory())await walk(base,next);else out[path.relative(base,next)]=hash(await readFile(next));}}await walk(path.join(directory,'local'),path.join(directory,'local'));return out;};
const launch=async(ui,sequence)=>{await writeFile(path.join(directory,'choice.json'),JSON.stringify({sequence}));await run('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File','scripts/native-pack-launch.ps1','-FixtureDirectory',directory,'-Mode',ui],{AOC_PACK_CONTROL_TOKEN:controlToken});};
try{
 await listen(tls,39441);await listen(control,39442);
 await run(cargo,['test','--locked','--manifest-path','apps/launcher/src-tauri/Cargo.toml','repository_tls_verification_and_bounds','--','--ignored','--test-threads=1'],{AOC_PACK_ACCEPTANCE_DIRECTORY:directory});
 await launch('first',1);assert.deepEqual(await readFile(jar),bytesFor(1));
 await mkdir(path.dirname(world));await writeFile(world,'synthetic preserved world');
 release=2;await launch('update',2);assert.deepEqual(await readFile(jar),bytesFor(2));
 await writeFile(jar,'user edited fixture');await launch('repair',2);assert.deepEqual(await readFile(jar),bytesFor(2));
 assert.equal(await readFile(world,'utf8'),'synthetic preserved world');
 const backup=path.join(directory,'local','AnimeOnCraft.InstallEngineDevelopment.v1','install','install-control','transactions','tx-00003','backup','00000.old');
 assert.equal(await readFile(backup,'utf8'),'user edited fixture');
 const before=await metadataSnapshot();const requestCount=requests.length;
 await launch('restart',2);assert.deepEqual(await metadataSnapshot(),before);assert.equal(requests.length,requestCount,'Read-only restart unexpectedly fetched');
 await writeFile(path.join(game,'mods/foreign.jar'),'foreign preserved file');const foreign=await metadataSnapshot();
 await launch('foreign',2);assert.deepEqual(await metadataSnapshot(),foreign,'Blocked foreign state was modified');
 assert.ok(requests.some(r=>r.url.startsWith('/aoc/v1/artifacts/')),'No HTTPS artifact request');
 assert.ok(requests.every(r=>r.method==='GET'&&r.encoding==='identity'&&!r.auth&&!r.cookie),'Unexpected credential/encoding/method');
 await writeFile(path.join(workspace,'.tools','native-pack-acceptance-result.json'),JSON.stringify({passed:true,directory,httpsRequests:requests.length,scenarios:['TLS trust/hostname/bounds','metadata cancel','tampered signature/retry','install','update','repair/backup','world preservation','read-only restart','foreign-file refusal'],version:(await readFile(path.join(workspace,'apps/launcher/src-tauri/Cargo.toml'),'utf8')).match(/^version\s*=\s*"([^"]+)"/m)[1]},null,2));
 console.log('PASS: actual HTTPS metadata/artifacts and packaged WebView2 install/update/repair/restart/refusal on isolated files.');
 console.log(`Fixture evidence retained: ${directory}`);
}finally{
 tls.closeAllConnections();control.closeAllConnections();await Promise.all([new Promise(resolve=>tls.close(resolve)),new Promise(resolve=>control.close(resolve))]);
 await unlink(path.join(directory,'key.pem'));
}
