// Local HTTPS entry point for the built admin. Uses a supplied certificate; never modifies OS/browser trust.
import {readFileSync,readdirSync} from 'node:fs';
import {spawn} from 'node:child_process';
import {createServer} from 'node:https';
import {request as upstream} from 'node:http';
import {createServer as netServer} from 'node:net';
import {X509Certificate} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
await import('../apps/backend/dist/environment.js');
const args=process.argv.slice(2);const option=name=>{const index=args.indexOf(name);return index>=0?args[index+1]:undefined;};
const certificatePath=option('--cert'),keyPath=option('--key');const port=Number(option('--port')??3443);
if(!certificatePath||!keyPath||!Number.isInteger(port)||port<1024||port>65535)throw new Error('Usage: node scripts/serve-admin.mjs --cert <localhost certificate.pem> --key <key.pem> [--port 3443]');
const certificate=readFileSync(path.resolve(certificatePath));const key=readFileSync(path.resolve(keyPath));const identity=new X509Certificate(certificate);
if(!identity.checkHost('localhost')||Date.parse(identity.validFrom)>Date.now()||Date.parse(identity.validTo)<=Date.now())throw new Error('A current certificate for localhost is required');
const origin=`https://localhost:${port}`;
async function freePort(){const server=netServer();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});const result=server.address().port;await new Promise(resolve=>server.close(resolve));return result;}
const publicPort=await freePort();let privatePort=await freePort();while(privatePort===publicPort)privatePort=await freePort();
const dist=path.join(root,'apps/admin/dist');const files=new Map();
for(const entry of readdirSync(dist,{recursive:true,withFileTypes:true}))if(entry.isFile()){const file=path.join(entry.parentPath,entry.name);files.set(`/admin/${path.relative(dist,file).replaceAll('\\','/')}`,file);}
if(!files.has('/admin/index.html'))throw new Error('Build admin assets first');
const backend=spawn(process.execPath,[path.join(root,'apps/backend/dist/main.js')],{cwd:path.join(root,'apps/backend'),windowsHide:true,stdio:'ignore',env:{...process.env,NODE_ENV:'development',PUBLIC_HOST:'127.0.0.1',INTEGRATION_HOST:'127.0.0.1',PUBLIC_PORT:String(publicPort),INTEGRATION_PORT:String(privatePort),AOC_AUTH_ENABLED:'true',AOC_LOCAL_BASE_API:'false',AOC_AUTH_ORIGIN:origin}});
let closed=false;const server=createServer({cert:certificate,key,minVersion:'TLSv1.2'},async(request,response)=>{
  try{
    response.setHeader('cache-control','no-store');response.setHeader('x-content-type-options','nosniff');response.setHeader('referrer-policy','no-referrer');
    response.setHeader('content-security-policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'");
    const url=new URL(request.url??'/',origin);
    if(url.pathname==='/'&&request.method==='GET'){response.writeHead(302,{location:'/admin/'});response.end();return;}
    if(/^\/api\/v1\/(?:auth|management|clans|news)(?:\/|$)/.test(url.pathname)){
      const chunks=[];let size=0;for await(const chunk of request){size+=chunk.length;if(size>65536){response.writeHead(413);response.end();return;}chunks.push(chunk);}
      const headers={...request.headers,host:`127.0.0.1:${publicPort}`,connection:'close'};delete headers['transfer-encoding'];delete headers['content-length'];delete headers['proxy-authorization'];delete headers['forwarded'];delete headers['x-forwarded-for'];delete headers['x-forwarded-host'];delete headers['x-forwarded-proto'];
      headers['content-length']=String(size);
      const proxy=upstream({hostname:'127.0.0.1',port:publicPort,path:url.pathname+url.search,method:request.method,headers,timeout:10000},result=>{response.writeHead(result.statusCode??503,result.headers);result.pipe(response);});
      proxy.on('timeout',()=>proxy.destroy());proxy.on('error',()=>{if(!response.headersSent)response.writeHead(503);response.end();});proxy.end(Buffer.concat(chunks));return;
    }
    const file=files.get(url.pathname==='/admin/'?'/admin/index.html':url.pathname);
    if(request.method!=='GET'||!file){response.writeHead(404);response.end();return;}
    response.writeHead(200,{'content-type':file.endsWith('.js')?'text/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'text/html; charset=utf-8'});response.end(readFileSync(file));
  }catch{if(!response.headersSent)response.writeHead(503);response.end();}
});
function stop(){if(closed)return;closed=true;backend.kill();server.closeAllConnections();server.close();if(process.connected)process.disconnect();}
process.on('message',message=>{if(message?.type==='shutdown')stop();});
process.on('SIGINT',stop);process.on('SIGTERM',stop);backend.on('exit',code=>{if(!closed){console.error(`Local backend exited (${code??'signal'}). Check development credentials and storage.`);process.exitCode=1;stop();}});
try{
  let ready=false;for(let attempt=0;attempt<80;attempt++){
    if(closed)break;ready=await new Promise(resolve=>{const probe=upstream({hostname:'127.0.0.1',port:publicPort,path:'/api/v1/health/ready',timeout:1000},result=>{result.resume();resolve(result.statusCode===200);});probe.on('error',()=>resolve(false));probe.on('timeout',()=>probe.destroy());probe.end();});if(ready)break;await new Promise(resolve=>setTimeout(resolve,250));
  }
  if(!ready)throw new Error('Local backend not ready. Check database/Valkey/auth keys; no TLS trust changes were made');
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});console.log(`Local admin: ${origin}/admin/ (development database, Secure cookies, supplied certificate)`);if(process.connected)process.send({type:'ready',publicPort,privatePort,port});
}catch(error){stop();throw error;}
