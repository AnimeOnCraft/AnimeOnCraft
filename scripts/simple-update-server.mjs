import {createServer} from 'node:https';
import {readFileSync,writeFileSync,createReadStream} from 'node:fs';
import path from 'node:path';
const directory=path.resolve(process.argv[2]);
if(!/^installer-acceptance-[a-f0-9]{32}$/.test(path.basename(directory)))throw new Error('Own updater fixture required');
const bytes=readFileSync(path.join(directory,'signed-installer.exe'));
const signature=readFileSync(path.join(directory,'signed-installer.exe.sig'),'utf8').trim();
const sockets=new Set();
const server=createServer({cert:readFileSync(path.join(directory,'cert.pem')),key:readFileSync(path.join(directory,'key.pem'))},(request,response)=>{
 const mode=readFileSync(path.join(directory,'mode.txt'),'utf8').trim();
 if(request.method!=='GET'||request.headers.authorization||request.headers.cookie){response.writeHead(400);response.end();return;}
 if(request.url==='/aoc/latest.json'){
  const version=mode==='wrong-version'?'0.7.99':'0.7.22';
  response.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});
  response.end(JSON.stringify({version,platforms:{'windows-x86_64-nsis':{url:`https://localhost:39447/aoc/AnimeOnCraft_${version}_x64-setup.exe`,signature}}}));return;
 }
 if(request.url===`/aoc/AnimeOnCraft_${mode==='wrong-version'?'0.7.99':'0.7.22'}_x64-setup.exe`){
  if(mode==='too-large'){response.writeHead(200,{'content-length':64*1024**2+1});response.write(Buffer.from('x'));return;}
  if(mode==='tampered'){const wrong=Buffer.from(bytes);wrong[wrong.length-1]^=1;response.writeHead(200,{'content-length':wrong.length});response.end(wrong);return;}
  response.writeHead(200,{'content-length':bytes.length,'content-type':'application/octet-stream'});
  if(mode==='slow'){let offset=0;const timer=setInterval(()=>{response.write(bytes.subarray(offset,offset+65536));offset+=65536;if(offset>=bytes.length){clearInterval(timer);response.end();}},100);response.on('close',()=>clearInterval(timer));return;}
  createReadStream(path.join(directory,'signed-installer.exe')).pipe(response);return;
 }
 response.writeHead(404);response.end();
});
server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
server.listen(39447,'127.0.0.1',()=>writeFileSync(path.join(directory,'server-ready.json'),JSON.stringify({port:39447})));
process.on('SIGTERM',()=>{for(const socket of sockets)socket.destroy();server.close();});
