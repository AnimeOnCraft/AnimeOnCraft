import '../src/environment.js';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer, type TLSSocket } from 'node:tls';
import { AuthMailService } from '../src/modules/auth/auth-mail.service.js';
import { opaque } from '../src/modules/auth/auth-crypto.js';

const backend = fileURLToPath(new URL('../',import.meta.url));
mkdirSync(path.join(backend,'.tools'),{recursive:true}); const temporary=mkdtempSync(path.join(backend,'.tools','mail-'));
assert.ok(path.resolve(temporary).startsWith(path.join(backend,'.tools')+path.sep));
const openssl = process.env.AOC_TEST_OPENSSL ?? 'openssl';
const keyFile=path.join(temporary,'key.pem'); const certificate=path.join(temporary,'certificate.pem');
const generated = process.platform === 'win32'
  ? spawnSync(process.env.AOC_TEST_PWSH ?? 'pwsh.exe',['-NoProfile','-NonInteractive','-File',path.resolve(backend,'../../scripts/test-certificate.ps1'),'-OutputDirectory',temporary],{encoding:'utf8',timeout:15000})
  : spawnSync(openssl,['req','-x509','-newkey','rsa:2048','-nodes','-keyout',keyFile,'-out',certificate,'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],{encoding:'utf8',timeout:15000});
if (generated.status !== 0) { rmSync(temporary,{recursive:true,force:true}); throw new Error('Mail test needs PowerShell 7 on Windows or OpenSSL on Linux to generate an ephemeral certificate'); }
const messages: string[]=[]; const sockets = new Set<TLSSocket>();
const server = createServer({key:readFileSync(keyFile),cert:readFileSync(certificate),minVersion:'TLSv1.2'},socket=>{
  sockets.add(socket); socket.once('close',()=>sockets.delete(socket)); socket.on('error',()=>{});
  socket.write('220 localhost test SMTP\r\n'); let buffer=''; let data=false; let content='';
  socket.on('data',chunk=>{
    buffer+=chunk.toString(); let end: number;
    while((end=buffer.indexOf('\r\n'))>=0) {
      const line=buffer.slice(0,end); buffer=buffer.slice(end+2);
      if(data) { if(line==='.') { messages.push(content); content=''; data=false; socket.write('250 accepted\r\n'); } else content+=line+'\n'; continue; }
      if(line.startsWith('EHLO ')) socket.write('250-localhost\r\n250 AUTH PLAIN\r\n');
      else if(line.startsWith('AUTH PLAIN ')) {
        const parts=Buffer.from(line.slice(11),'base64').toString().split('\0');
        socket.write(parts[1]==='fixture-user' && parts[2]==='fixture-password' ? '235 authenticated\r\n' : '535 rejected\r\n');
      } else if(line.startsWith('MAIL FROM:') || line.startsWith('RCPT TO:') || line==='RSET') socket.write('250 ok\r\n');
      else if(line==='DATA') { data=true; socket.write('354 send content\r\n'); }
      else if(line==='QUIT') { socket.end('221 bye\r\n'); }
      else socket.write('500 unsupported\r\n');
    }
  });
});
server.on('tlsClientError',()=>{});
let mail: AuthMailService | undefined;
try {
  await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve)); const address=server.address(); assert.ok(address && typeof address!=='string');
  Object.assign(process.env,{NODE_ENV:'test',AOC_AUTH_ORIGIN:'https://accounts.example.test',AOC_AUTH_MAIL_MODE:'smtp',AOC_SMTP_HOST:'127.0.0.1',AOC_SMTP_PORT:String(address.port),
    AOC_SMTP_SECURE:'true',AOC_SMTP_SERVERNAME:'localhost',AOC_SMTP_FROM:'accounts@example.test',AOC_SMTP_USER:'fixture-user',AOC_SMTP_PASSWORD:'fixture-password',AOC_SMTP_CA_FILE:certificate});
  mail=new AuthMailService(); await mail.onModuleInit(); mail.assertAvailable();
  const token=opaque('ac'); await mail.deliver('player@example.test','PASSWORD_RESET',token); await mail.onApplicationShutdown();
  assert.equal(messages.length,1); assert.match(messages[0]!,/To: player@example.test/);
  // MIME encoding may wrap quoted-printable lines; decode just those standard soft line breaks/escapes for this fixed ASCII fixture.
  const decoded=messages[0]!.replace(/=\n/g,'').replace(/=([A-Fa-f0-9]{2})/g,(_,hex:string)=>String.fromCharCode(parseInt(hex,16)));
  assert.ok(decoded.includes(`https://accounts.example.test/account/reset-password#token=${token}`));
  assert.equal(decoded.includes('fixture-password'),false);
  delete process.env.AOC_SMTP_CA_FILE;
  const untrusted=new AuthMailService(); await assert.rejects(untrusted.onModuleInit(),/SMTP verification failed/); await untrusted.onApplicationShutdown();
  console.log('Actual TLS SMTP delivery/verified certificate/fixed HTTPS recovery link/authentication/no credential in message/untrusted certificate rejection: passed');
} finally {
  await mail?.onApplicationShutdown(); for(const socket of sockets) socket.destroy();
  await new Promise<void>(resolve=>server.close(()=>resolve())); rmSync(temporary,{recursive:true,force:true});
}
