import assert from 'node:assert/strict';
import { createServer as createNetServer } from 'node:net';
import { createServer } from 'node:https';
import { createServer as createHttpServer } from 'node:http';
import { mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import type { NestFastifyApplication } from '@nestjs/platform-fastify';
import type { FastifyReply } from 'fastify';
import type { AuthHttp } from '../src/modules/auth/auth-http.js';
import type { TokenPair } from '../src/modules/auth/auth.service.js';
export async function probePort():Promise<number>{const server=createNetServer();await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));const address=server.address();assert.ok(address&&typeof address!=='string');await new Promise<void>(resolve=>server.close(()=>resolve()));return address.port;}
// Explicit test-only browser fixture; never imported by backend src/main or exposed on a shared database.
export async function browserProbe(app:NestFastifyApplication,http:AuthHttp,pair:TokenPair,backend:string,temporary:string,port:number){
  assert.equal(process.env.NODE_ENV,'test');assert.equal(process.env.AOC_CLAN_UI_PROBE,'true');
  const certificate=path.join(temporary,'mail-ui');mkdirSync(certificate);
  const key=path.join(certificate,'key.pem');const cert=path.join(certificate,'certificate.pem');
  const generated=process.platform==='win32'?spawnSync(process.env.AOC_TEST_PWSH??'pwsh.exe',['-NoProfile','-NonInteractive','-File',path.resolve(backend,'../../scripts/test-certificate.ps1'),'-OutputDirectory',certificate],{encoding:'utf8',timeout:15000}):spawnSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',cert,'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],{encoding:'utf8',timeout:15000});
  assert.equal(generated.status,0,'Ephemeral browser certificate generation');
  const dist=path.resolve(backend,'../admin/dist');const files=new Map<string,string>();
  for(const entry of readdirSync(dist,{recursive:true,withFileTypes:true}))if(entry.isFile()){const file=path.join(entry.parentPath,entry.name);files.set(`/admin/${path.relative(dist,file).replaceAll('\\','/')}`,file);}
  let done:()=>void=()=>{};const complete=new Promise<void>(resolve=>{done=resolve;});
  const server=createServer({key:readFileSync(key),cert:readFileSync(cert),minVersion:'TLSv1.2'},async(request,response)=>{
    try{
      const url=new URL(request.url??'/',`https://localhost:${port}`);
      if(url.pathname==='/__test__/session'){
        let cookies:string[]=[];http.issue(pair,'WEB_ADMIN',{header:(name:string,value:string[])=>{if(name==='set-cookie')cookies=value;}} as unknown as FastifyReply);
        response.writeHead(302,{'set-cookie':cookies,location:'/admin/','cache-control':'no-store'});response.end();return;
      }
      if(url.pathname==='/__test__/complete'&&request.method==='POST'){response.writeHead(204);response.end();done();return;}
      if(url.pathname.startsWith('/api/v1/')){
        const chunks:Buffer[]=[];let size=0;for await(const chunk of request){const data=Buffer.from(chunk);size+=data.length;if(size>65536){response.writeHead(413);response.end();return;}chunks.push(data);}
        const result=await app.inject({method:request.method as 'GET'|'POST'|'PATCH'|'PUT'|'DELETE',url:request.url!,headers:request.headers,...size?{payload:Buffer.concat(chunks)}:{}});
        response.writeHead(result.statusCode,result.headers);response.end(result.rawPayload);return;
      }
      const file=files.get(url.pathname==='/admin/'?'/admin/index.html':url.pathname);
      if(!file){response.writeHead(404);response.end();return;}
      response.writeHead(200,{'content-type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html','cache-control':'no-store'});response.end(readFileSync(file));
    }catch{response.writeHead(500);response.end();}
  });
  await new Promise<void>(resolve=>server.listen(port,'127.0.0.1',resolve));
  console.log(`Test-only HTTPS browser fixture: https://localhost:${port}/__test__/session`);
  // Read-only companion lets an untrusted-certificate browser inspect the public UI without weakening TLS/auth.
  const publicServer=createHttpServer(async(request,response)=>{
    const url=new URL(request.url??'/','http://localhost');
    if(url.pathname==='/__test__/complete'&&request.method==='POST'){response.writeHead(204);response.end();done();return;}
    if(request.method==='GET'&&url.pathname==='/api/v1/clans'){
      const result=await app.inject({method:'GET',url:request.url!});response.writeHead(result.statusCode,{'content-type':'application/json'});response.end(result.rawPayload);return;
    }
    const file=files.get(url.pathname==='/admin/'?'/admin/index.html':url.pathname);
    if(request.method==='GET'&&file){response.writeHead(200,{'content-type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html','cache-control':'no-store'});response.end(readFileSync(file));return;}
    response.writeHead(401,{'content-type':'application/json'});response.end(JSON.stringify({error:{code:'AUTHENTICATION_REQUIRED'}}));
  });
  await new Promise<void>(resolve=>publicServer.listen(0,'127.0.0.1',resolve));const publicAddress=publicServer.address();assert.ok(publicAddress&&typeof publicAddress!=='string');
  console.log(`Read-only public browser fixture: http://localhost:${publicAddress.port}/admin/`);
  const timer=setTimeout(done,300000);
  try{await complete;}finally{clearTimeout(timer);server.closeAllConnections();publicServer.closeAllConnections();await Promise.all([new Promise<void>(resolve=>server.close(()=>resolve())),new Promise<void>(resolve=>publicServer.close(()=>resolve()))]);}
}
