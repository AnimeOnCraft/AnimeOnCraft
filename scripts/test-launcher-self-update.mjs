import assert from 'node:assert/strict';
import {createServer as httpsServer} from 'node:https';
import {createServer as netServer} from 'node:net';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,realpath,unlink} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const workspace=fileURLToPath(new URL('../',import.meta.url)).replace(/[\\/]$/,'');
assert.equal(process.platform,'win32');
const probe=netServer();
await new Promise((resolve,reject)=>{probe.once('error',reject);probe.listen(39443,'127.0.0.1',resolve);});
await new Promise(resolve=>probe.close(resolve));
const directory=path.join(workspace,'.tools',`self-update-acceptance-${randomUUID().replaceAll('-','')}`);
await mkdir(directory);assert.equal(await realpath(directory),directory);
const cargo=path.join(process.env.USERPROFILE,'.cargo/bin/cargo.exe');
const run=async(command,args)=>{
 const child=spawn(command,args,{cwd:workspace,env:{...process.env,AOC_SELF_UPDATE_ACCEPTANCE_DIRECTORY:directory},windowsHide:true,stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',d=>process.stdout.write(d));child.stderr.on('data',d=>process.stderr.write(d));
 const timer=setTimeout(()=>child.kill(),300000);
 try{const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});assert.equal(code,0,`Failed ${path.basename(command)}`);}finally{clearTimeout(timer);}
};
const manifest='apps/launcher/src-tauri/Cargo.toml';
await run(cargo,['test','--locked','--manifest-path',manifest,'self_update']);
await run(cargo,['test','--locked','--manifest-path',manifest,'pack::actor::tests']);
await run(cargo,['test','--locked','--manifest-path',manifest,'export_self_update_fixtures','--','--ignored','--test-threads=1']);
await run('pwsh.exe',['-NoProfile','-NonInteractive','-File','scripts/test-pack-certificate.ps1','-OutputDirectory',directory]);
const fixture=JSON.parse(await readFile(path.join(directory,'fixtures.json'),'utf8'));
const requests=[];let rollbackRequests=0,slowStarted=false,abortStarted=false;
const sockets=new Set();
const server=httpsServer({key:await readFile(path.join(directory,'key.pem')),cert:await readFile(path.join(directory,'certificate.pem')),minVersion:'TLSv1.2'},(request,response)=>{
 requests.push({url:request.url,method:request.method,encoding:request.headers['accept-encoding'],auth:!!request.headers.authorization,cookie:!!request.headers.cookie});
 const match=/^\/aoc\/launcher\/([a-z_]+)\/(latest\.json|0\.7\.(18|19)\/AnimeOnCraft_0\.7\.(18|19)_x64-setup\.exe)$/.exec(request.url??'');
 if(request.method!=='GET'||!match){response.writeHead(404);response.end();return;}
 const mode=match[1],latest=match[2]==='latest.json';
 if(latest&&mode==='oversize'){response.writeHead(200,{'content-length':32769});response.end();return;}
 if(latest&&mode==='stream_oversize'){response.writeHead(200);response.end('x'.repeat(32769));return;}
 if(latest&&mode==='encoding'){response.writeHead(200,{'content-encoding':'gzip'});response.end('{}');return;}
 if(latest&&mode==='duplicate'){response.writeHead(200,['Content-Encoding','identity','Content-Encoding','identity']);response.end('{}');return;}
 if(latest&&mode==='redirect'){response.writeHead(302,{location:'https://localhost:39443/aoc/launcher/good/latest.json'});response.end();return;}
 if(latest&&mode==='missing'){response.writeHead(404);response.end();return;}
 let bytes;
 if(latest){let envelope=fixture.envelopes[mode]??fixture.envelopes.good;
  if(mode==='rollback'&&rollbackRequests++>0)envelope=fixture.envelopes.good;
  bytes=Buffer.from(envelope);
 }else{
  bytes=Buffer.from(fixture.artifact);
  if(mode==='artifact_tamper')bytes[0]^=1;
  if(mode==='short')bytes=bytes.subarray(0,1000);
  if(mode==='slow'||(mode==='abort'&&!abortStarted)){
   if(mode==='abort')abortStarted=true;
   response.writeHead(200,{'content-length':bytes.length});response.write(bytes.subarray(0,128));slowStarted=true;
   writeFile(path.join(directory,`${mode}-started`),'yes').catch(error=>response.destroy(error));return;
  }
 }
 response.writeHead(200,{'content-length':bytes.length});response.end(bytes);
});
server.on('tlsClientError',()=>{});server.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));});
try{
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(39443,'127.0.0.1',resolve);});
 await run(cargo,['test','--locked','--manifest-path',manifest,'self_update_https_signature_cancellation_and_consent_acceptance','--','--ignored','--test-threads=1']);
 assert.equal(JSON.parse(await readFile(path.join(directory,'rust-result.json'),'utf8')).passed,true);
 assert.equal(slowStarted,true);assert.ok(requests.some(r=>r.url?.endsWith('.exe')));
 assert.equal(abortStarted,true);assert.equal(requests.filter(r=>r.url?.startsWith('/aoc/launcher/rollback/')&&r.url.endsWith('.exe')).length,1);
 assert.ok(requests.every(r=>r.method==='GET'&&r.encoding==='identity'&&!r.auth&&!r.cookie));
 assert.equal(requests.filter(r=>r.url==='/aoc/launcher/good/latest.json').length,2,'Redirect/unconfigured/repeated pending preparation fetched unexpectedly');
 await writeFile(path.join(directory,'requests.json'),JSON.stringify(requests,null,2));
 const result={passed:true,directory,httpsRequests:requests.length,syntheticArtifact:true,installerExecuted:false,normalSourceConfigured:false,scenarios:['untrusted TLS','wrong hostname','metadata tamper','expiry/platform','installer signature/tamper','body limits/status/encoding/redirect/length','socket cancellation','future drop/retry','concurrent updater refusal','reprepare/discard','single-use consent','pack exclusion','process rollback floor']};
 await writeFile(path.join(workspace,'.tools/self-update-acceptance-result.json'),JSON.stringify(result,null,2));
 console.log(`PASS: signed self-update preparation and actual HTTPS refusal/cancellation. Evidence: ${directory}`);
}finally{
 for(const socket of sockets)socket.destroy();
 if(server.listening)await new Promise(resolve=>server.close(resolve));
 await unlink(path.join(directory,'key.pem'));
}
