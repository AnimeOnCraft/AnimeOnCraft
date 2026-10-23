import '../src/environment.js';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';

const migrationUrl=process.env.MIGRATION_DATABASE_URL, runtimeUrl=process.env.DATABASE_URL;
assert.ok(migrationUrl&&runtimeUrl);
for(const value of [migrationUrl,runtimeUrl]){
  const url=new URL(value);assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'54329');assert.equal(url.pathname,'/animeoncraft');
}
const name=`animeoncraft_gameplay_test_${randomUUID().replaceAll('-','')}`;
assert.match(name,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const urlFor=(value:string)=>{const url=new URL(value);url.pathname=`/${name}`;return url.toString();};
const admin=new PrismaClient({adapter:new PrismaPg({connectionString:migrationUrl})});
const backend=fileURLToPath(new URL('../',import.meta.url));let created=false;
const minecraftOutbox=process.argv.slice(2);
assert.ok(minecraftOutbox.length===0||(minecraftOutbox.length===1&&['--minecraft-outbox','--java-outbox'].includes(minecraftOutbox[0]!)));
function run(args:string[],extra:Record<string,string>){
  const result=spawnSync(process.execPath,args,{cwd:backend,env:{...process.env,...extra},encoding:'utf8',timeout:90000});
  if(result.status!==0)throw new Error(`Isolated gameplay verification failed: ${result.stdout}\n${result.stderr}`);
  console.log(result.stdout.trim());
}
try{
  await admin.$executeRawUnsafe(`CREATE DATABASE "${name}"`);created=true;
  run([path.join(backend,'node_modules/prisma/build/index.js'),'migrate','deploy'],{MIGRATION_DATABASE_URL:urlFor(migrationUrl)});
  if(minecraftOutbox.length){
    run(['--import','tsx','db/world-boss-gameplay.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name,
      AOC_VERIFY_JAVA_OUTBOX:minecraftOutbox[0]==='--java-outbox'?'1':'0'});
  }else{
  run(['--import','tsx','db/gameplay-smoke.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name});
  run(['--import','tsx','db/world-boss-smoke.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name});
  run(['--import','tsx','db/gameplay-concurrency.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name});
  run(['--import','tsx','db/world-boss-http.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name});
  run(['--import','tsx','db/world-catalog-smoke.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name});
  run(['--import','tsx','db/capture-smoke.ts'],{DATABASE_URL:urlFor(runtimeUrl),AOC_GAMEPLAY_TEST_DATABASE:name});
  }
  console.log('Clean-database gameplay integration passed with the runtime role. Disposable database removed on exit.');
}finally{
  if(created)await admin.$executeRawUnsafe(`DROP DATABASE "${name}" WITH (FORCE)`);
  await admin.$disconnect();
}
