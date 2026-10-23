import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {once} from 'node:events';
import {mkdirSync,mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {createServer} from 'node:net';
import {request} from 'node:https';
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
await import('../apps/backend/dist/environment.js');
const root=fileURLToPath(new URL('../',import.meta.url)),tools=path.join(root,'apps/backend/.tools');mkdirSync(tools,{recursive:true});const temporary=mkdtempSync(path.join(tools,'mail-admin-https-'));
assert.ok(path.resolve(temporary).startsWith(path.resolve(tools)+path.sep));
const cert=path.join(temporary,'certificate.pem'),key=path.join(temporary,'key.pem');const namespace=`aoc:admin-https:test:${randomUUID()}`;
const redisUrl=new URL(process.env.VALKEY_URL);assert.equal(redisUrl.hostname,'127.0.0.1');assert.equal(redisUrl.port,'56379');
const require=createRequire(path.join(root,'apps/backend/package.json'));const {Redis}=require('ioredis');const redis=new Redis(redisUrl.toString(),{lazyConnect:true,enableOfflineQueue:false,maxRetriesPerRequest:0,retryStrategy:()=>null});redis.on('error',()=>{});
const socket=createServer();await new Promise(resolve=>socket.listen(0,'127.0.0.1',resolve));const port=socket.address().port;await new Promise(resolve=>socket.close(resolve));
let child;let ports=[];
try{
  const generated=process.platform==='win32'?spawnSync(process.env.AOC_TEST_PWSH??'pwsh.exe',['-NoProfile','-NonInteractive','-File',path.join(root,'scripts/test-certificate.ps1'),'-OutputDirectory',temporary],{windowsHide:true,encoding:'utf8',timeout:15000}):spawnSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',cert,'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],{encoding:'utf8',timeout:15000});assert.equal(generated.status,0,'Own ephemeral certificate generation');
  child=spawn(process.execPath,[path.join(root,'scripts/serve-admin.mjs'),'--cert',cert,'--key',key,'--port',String(port)],{cwd:root,windowsHide:true,env:{...process.env,AOC_AUTH_RATE_NAMESPACE:namespace},stdio:['ignore','pipe','pipe','ipc']});
  let output='';child.stdout.on('data',data=>{output+=data;});child.stderr.on('data',data=>{output+=data;});
  const ready=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('HTTPS development entry point startup timeout')),30000);child.on('message',message=>{if(message.type==='ready'){clearTimeout(timer);resolve(message);}});child.once('exit',()=>{clearTimeout(timer);reject(new Error('HTTPS development entry point exited'));});});ports=[ready.port,ready.publicPort,ready.privatePort];
  const ca=readFileSync(cert);async function get(route,options={}){return new Promise((resolve,reject)=>{const probe=request({hostname:'127.0.0.1',servername:'localhost',port,path:route,ca,minVersion:'TLSv1.2',timeout:10000,...options},response=>{const chunks=[];response.on('data',chunk=>chunks.push(chunk));response.on('end',()=>resolve({status:response.statusCode,headers:response.headers,body:Buffer.concat(chunks).toString()}));});probe.on('error',reject);probe.on('timeout',()=>probe.destroy(new Error('HTTPS request timeout')));probe.end(options.body);});}
  const page=await get('/admin/');assert.equal(page.status,200);assert.match(page.headers['content-security-policy'],/frame-ancestors 'none'/);assert.match(page.body,/assets\/index/);
  assert.equal((await get('/.env')).status,404);assert.equal((await get('/admin/%2e%2e/.env')).status,404);assert.equal((await get('/development/v1/clans')).status,404);assert.equal((await get('/integration/v1/health/live')).status,404);
  const csrf=await get('/api/v1/auth/csrf',{headers:{'x-aoc-client':'web-admin'}});assert.equal(csrf.status,200);assert.match(csrf.headers['set-cookie'][0],/Secure; HttpOnly; SameSite=Strict/);assert.ok(JSON.parse(csrf.body).csrf_token);
  assert.equal((await get('/api/v1/management/users',{headers:{'x-aoc-client':'web-admin'}})).status,401);
  const payload=JSON.stringify({login:'https-fixture',password:'HTTPS-Fixture-Password!'});
  assert.equal((await get('/api/v1/auth/login',{method:'POST',headers:{'x-aoc-client':'web-admin',origin:'https://attacker.example','content-type':'application/json'},body:payload})).status,403);
  assert.equal(output.includes(payload),false);assert.equal(output.includes(JSON.parse(csrf.body).csrf_token),false);
  console.log('Compiled local admin HTTPS: actual supplied-CA verification, assets/CSP, Secure cookie/CSRF, anonymous 401, invalid Origin 403, no private/development/path escape, no secret logs: passed (no browser trust changes)');
}finally{
  if(child&&child.exitCode===null){const exited=once(child,'exit');child.send({type:'shutdown'});await exited;}
  for(const number of ports){const server=createServer();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(number,'127.0.0.1',resolve);});await new Promise(resolve=>server.close(resolve));}
  try{await redis.connect();let cursor='0';do{const result=await redis.scan(cursor,'MATCH',`${namespace}:*`,'COUNT',100);cursor=result[0];if(result[1].length)await redis.del(...result[1]);}while(cursor!=='0');}finally{redis.disconnect();}
  assert.ok(path.resolve(temporary).startsWith(path.resolve(tools)+path.sep));rmSync(temporary,{recursive:true,force:true});
}
