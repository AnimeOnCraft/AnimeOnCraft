import assert from 'node:assert/strict';
import net from 'node:net';
import {networkInterfaces} from 'node:os';
import {pathToFileURL} from 'node:url';

export function privateIPv4(value){
  if(net.isIP(value)!==4)return false;
  const [a,b]=value.split('.').map(Number);
  return a===10||a===172&&b>=16&&b<=31||a===192&&b===168;
}

// A raw TCP bridge preserves HTTP Host/Origin and admission payloads. It never
// exposes integration port 3001, PostgreSQL, Valkey or administrative services.
export async function startRelay({listenHost,targetHost,ports,allowedPeer=()=>true}){
  const servers=[],sockets=new Set();
  const close=async()=>{
    for(const socket of sockets)socket.destroy();
    await Promise.all(servers.map(server=>new Promise(resolve=>server.close(resolve))));
  };
  try{
    for(const port of ports){
      const server=net.createServer(incoming=>{
        if(!allowedPeer(incoming.remoteAddress)){incoming.destroy();return;}
        const outgoing=net.createConnection({host:targetHost,port});
        for(const socket of [incoming,outgoing]){
          sockets.add(socket);socket.once('close',()=>sockets.delete(socket));
          socket.on('error',()=>{incoming.destroy();outgoing.destroy();});
        }
        incoming.once('close',()=>outgoing.destroy());
        outgoing.once('close',()=>incoming.destroy());
        incoming.pipe(outgoing).pipe(incoming);
      });
      server.maxConnections=128;
      servers.push(server);
      await new Promise((resolve,reject)=>{
        server.once('error',reject);server.listen(port,listenHost,resolve);
      });
      server.on('error',()=>{console.error('LAN listener failed; restart the relay.');void close();});
    }
    return {close};
  }catch(error){await close();throw error;}
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [mode,address]=process.argv.slice(2);
  assert.ok(process.argv.length===4&&['host','client'].includes(mode)&&privateIPv4(address),
    'Usage: node gameplay-lan-relay.mjs host|client PRIVATE_LAN_IP');
  if(mode==='host')assert.ok(Object.values(networkInterfaces()).flat().some(i=>i?.address===address),'Host IP is not assigned to this PC.');
  const relay=await startRelay({listenHost:mode==='host'?address:'127.0.0.1',
    targetHost:mode==='host'?'127.0.0.1':address,ports:[3000,25565],
    allowedPeer:peer=>mode==='client'?peer==='127.0.0.1':privateIPv4(peer)&&peer.split('.').slice(0,3).join('.')===address.split('.').slice(0,3).join('.')});
  console.log(`LAN ${mode} ready: ${address}; API 3000 / Minecraft 25565. Keep this window open. Ctrl+C stops access.`);
  console.log('Trusted home network only: this test bridge uses HTTP. Use a separate local test password. Voice UDP is not forwarded.');
  for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>{void relay.close().then(()=>process.exit(0));});
}
