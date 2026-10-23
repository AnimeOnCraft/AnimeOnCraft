import assert from 'node:assert/strict';
import {createServer} from 'node:https';
import {readFile,writeFile,realpath} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(fileURLToPath(new URL('../',import.meta.url))),directory=process.argv[2];
assert.equal(path.dirname(directory),path.join(root,'.tools'));assert.match(path.basename(directory),/^installer-acceptance-[a-f0-9]{32}$/);assert.equal(await realpath(directory),directory);
const artifact=await readFile(path.join(directory,'signed-installer.exe'));
assert.ok(artifact.length>=512&&artifact.length<=64*1024**2);
const requests=[];
const server=createServer({key:await readFile(path.join(directory,'key.pem')),cert:await readFile(path.join(directory,'certificate.pem')),minVersion:'TLSv1.2'},async(request,response)=>{
 requests.push({url:request.url,method:request.method,encoding:request.headers['accept-encoding'],auth:!!request.headers.authorization,cookie:!!request.headers.cookie});
 try{
  assert.equal(request.method,'GET');assert.equal(request.headers.authorization,undefined);assert.equal(request.headers.cookie,undefined);assert.equal(request.headers['accept-encoding'],'identity');
  const choice=JSON.parse(await readFile(path.join(directory,'choice.json'),'utf8'));assert.ok(['good','wrong'].includes(choice.mode));
  let bytes;
  if(request.url==='/aoc/launcher/latest.json')bytes=await readFile(path.join(directory,choice.mode==='wrong'?'wrong-version.json':'latest.json'));
  else if(['/aoc/launcher/0.7.19/AnimeOnCraft_0.7.19_x64-setup.exe','/aoc/launcher/0.7.99/AnimeOnCraft_0.7.99_x64-setup.exe'].includes(request.url))bytes=artifact;
  else{response.writeHead(404);response.end();return;}
  await writeFile(path.join(directory,'requests.json'),JSON.stringify(requests,null,2));
  response.writeHead(200,{'content-length':bytes.length});response.end(bytes);
 }catch(error){console.error(error);response.writeHead(500);response.end();}
});
server.on('tlsClientError',()=>{});
await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(39445,'127.0.0.1',resolve);});
await writeFile(path.join(directory,'server-ready.json'),'true');
console.log('Installed self-update fixture TLS listening on own loopback39445.');
