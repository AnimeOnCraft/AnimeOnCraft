import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createRequire} from 'node:module';
import {createWriteStream,writeFileSync} from 'node:fs';
import path from 'node:path';
import {root,portClosed} from './friends-backup-lib.mjs';
import {localGameEnvironment} from './local-game-config.mjs';
const require=createRequire(new URL('../apps/backend/package.json',import.meta.url));
const env={...process.env};require('dotenv').config({path:path.join(root,'.env'),processEnv:env,quiet:true});
await portClosed(39435);await portClosed(39436);
Object.assign(env,localGameEnvironment(),{NODE_ENV:'production',AOC_FRIENDS_DEPLOYMENT:'true',AOC_AUTH_ENABLED:'true',AOC_LOCAL_BASE_API:'false',PUBLIC_HOST:'127.0.0.1',PUBLIC_PORT:'39435',INTEGRATION_HOST:'127.0.0.1',INTEGRATION_PORT:'39436',AOC_AUTH_MAIL_MODE:'disabled'});
const secrets=Object.entries(env).filter(([key,value])=>value&&value.length>=8&&/(PASSWORD|_KEY$|DATABASE_URL|VALKEY_URL|SECRET)/.test(key)).map(([,value])=>value);
const output=createWriteStream(path.join(root,'.tools/beta-production-api.log'));
const child=spawn(process.execPath,['apps/backend/dist/main.js'],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
const exit=once(child,'exit');let exited=false;
child.once('exit',()=>{exited=true;});
for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>output.write(secrets.reduce((text,secret)=>text.replaceAll(secret,'[REDACTED]'),bytes.toString())));
try{
 let ready=false;
 for(let i=0;i<100;i++){
  assert.ok(!exited,'Production API exited; inspect private local log');
  try{const response=await fetch('http://127.0.0.1:39435/api/v1/health/ready');ready=response.status===200;await response.body?.cancel();if(ready)break;}catch{/* Wait for loopback listeners. */}
  await new Promise(resolve=>setTimeout(resolve,200));
 }
 assert.ok(ready,'Production API did not become ready');
 for(const [route,status] of [['auth/me',401],['launcher/owner/clans',401],['clans',200]]){
  const response=await fetch(`http://127.0.0.1:39435/api/v1/${route}`,{headers:{'x-aoc-client':'launcher'}});assert.equal(response.status,status);await response.body?.cancel();
 }
 const result={passed:true,production_mode:true,loopback_only:true,account_auth_enabled:true,unauthenticated_owner_denied:true,public_catalog_available:true,tls_proxy_external_tested:false,checked_at:new Date().toISOString()};
 writeFileSync(path.join(root,'.tools/beta-production-api-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{if(!exited){child.kill();await exit;}output.end();}
