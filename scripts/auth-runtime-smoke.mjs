import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
async function port() {
  const server=createServer(); await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const value=server.address().port; await new Promise(resolve=>server.close(resolve)); return value;
}
const publicPort=await port(); let integrationPort=await port(); while(integrationPort===publicPort) integrationPort=await port();
const child=spawn(process.execPath,['apps/backend/dist/main.js'],{cwd:root,env:{...process.env,NODE_ENV:'development',AOC_AUTH_ENABLED:'true',AOC_LOCAL_BASE_API:'false',PUBLIC_HOST:'127.0.0.1',INTEGRATION_HOST:'127.0.0.1',PUBLIC_PORT:String(publicPort),INTEGRATION_PORT:String(integrationPort)},stdio:['ignore','pipe','pipe']});
let output=''; child.stdout.on('data',data=>{output+=data.toString();}); child.stderr.on('data',data=>{output+=data.toString();});
const base=`http://127.0.0.1:${publicPort}`;
try {
  let ready=false;
  for(let i=0;i<100;i++) {
    if(child.exitCode!==null) throw new Error('Compiled authentication backend exited during startup');
    try { ready=(await fetch(`${base}/api/v1/health/ready`,{signal:AbortSignal.timeout(1000)})).status===200; } catch { /* Startup has not bound the listener yet. */ }
    if(ready) break; await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.equal(ready,true,'Local PG/Valkey must be healthy');
  const token=`aa_${randomBytes(32).toString('base64url')}`; const password='Runtime-Fixture-Password!';
  const denied=await fetch(`${base}/api/v1/auth/me`,{headers:{'x-aoc-client':'launcher',authorization:`Bearer ${token}`}}); assert.equal(denied.status,401);
  const csrf=await fetch(`${base}/api/v1/auth/csrf`,{headers:{'x-aoc-client':'web-admin'}}); assert.equal(csrf.status,200);
  assert.match(csrf.headers.get('set-cookie'),/^__Host-aoc-pre=.*; Path=\/; Secure; HttpOnly; SameSite=Strict/);
  const csrfValue=(await csrf.json()).csrf_token; assert.ok(csrfValue);
  const login=await fetch(`${base}/api/v1/auth/login`,{method:'POST',headers:{'content-type':'application/json','x-aoc-client':'launcher'},body:JSON.stringify({login:'runtime-nonexistent',password})}); assert.equal(login.status,401);
  assert.equal((await fetch(`${base}/development/v1/clans`)).status,404);
  assert.equal((await fetch(`${base}/integration/v1/health/live`)).status,404);
  assert.equal((await fetch(`http://127.0.0.1:${integrationPort}/integration/v1/health/live`)).status,200);
  await new Promise(resolve=>setTimeout(resolve,50));
  assert.equal(output.includes(token),false); assert.equal(output.includes(password),false); assert.equal(output.includes(csrfValue),false);
  console.log('Compiled Node HTTP/auth opt-in/readiness/native 401/web Secure CSRF/log redaction/local API off/private boundary: passed');
} finally {
  if(child.exitCode===null) { const exited=once(child,'exit'); child.kill('SIGTERM'); await exited; }
  for(const number of [publicPort,integrationPort]) { const server=createServer(); await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(number,'127.0.0.1',resolve);}); await new Promise(resolve=>server.close(resolve)); }
}
