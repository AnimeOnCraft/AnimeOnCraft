import '../src/environment.js';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {writeFileSync} from 'node:fs';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
// Explicitly scoped migration of the existing local development DB, with no account resets.
const backend=fileURLToPath(new URL('../',import.meta.url));
const uri=new URL(process.env.MIGRATION_DATABASE_URL!);
assert.equal(uri.hostname,'127.0.0.1');assert.equal(uri.port,'54329');assert.equal(uri.pathname,'/animeoncraft');
const db=new PrismaClient({adapter:new PrismaPg({connectionString:uri.toString(),connectionTimeoutMillis:2000,query_timeout:5000})});
const fingerprint=async()=>{const rows=await db.$queryRaw<unknown[]>`SELECT id,email,email_key,email_verified_at,password_hash,system_role,state,auth_epoch,access_epoch,version,created_at,updated_at FROM users ORDER BY id`;return {count:rows.length,digest:createHash('sha256').update(JSON.stringify(rows,(_,value:unknown)=>typeof value==='bigint'?value.toString():value)).digest('hex')};};
try{
 const before=await fingerprint();
 const result=spawnSync(process.execPath,[path.join(backend,'node_modules/prisma/build/index.js'),'migrate','deploy'],{cwd:backend,env:process.env,encoding:'utf8',timeout:60000});
 if(result.status!==0)throw new Error('Local friends migration failed; user data was not printed');
 const after=await fingerprint();assert.deepEqual(after,before,'Existing account fields changed unexpectedly');
 const fields=await db.$queryRaw<{count:bigint}[]>`SELECT count(*) FROM users WHERE login IS NULL OR login_key IS NULL OR login_key<>lower(login)`;assert.equal(fields[0]!.count,0n);
 const report={schema_version:1,migration:'20261015000000_friends_accounts',passed:true,user_count:before.count,existing_ids_credentials_contact_roles_states_epochs_preserved:true};
 writeFileSync(path.resolve(backend,'../../.tools/friends-main-migration-result.json'),JSON.stringify(report,null,2));
 console.log('Local friends migration applied; existing account IDs, passwords, email, roles, states and sessions preserved.');
}finally{await db.$disconnect();}
