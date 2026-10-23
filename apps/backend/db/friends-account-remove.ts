import '../src/environment.js';
import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
const uri=new URL(process.env.MIGRATION_DATABASE_URL!);
assert.equal(uri.hostname,'127.0.0.1');assert.equal(uri.port,'54329');assert.equal(uri.pathname,'/animeoncraft');
const db=new PrismaClient({adapter:new PrismaPg({connectionString:uri.toString()})});
try {
 const users=await db.user.findMany({select:{id:true,systemRole:true}});
 assert.equal(users.length,1,'Only the explicitly authorized sole local account may be removed');
 const refs=await db.$queryRaw<{table_name:string,column_name:string}[]>`SELECT c.conrelid::regclass::text AS table_name,a.attname AS column_name FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=ANY(c.conkey) WHERE c.contype='f' AND c.confrelid='users'::regclass`;
 const counts=[];
 for(const ref of refs){assert.match(ref.table_name,/^[a-z_]+$/);assert.match(ref.column_name,/^[a-z_]+$/);const rows=await db.$queryRawUnsafe<{count:bigint}[]>(`SELECT count(*) FROM "${ref.table_name}" WHERE "${ref.column_name}"=$1::uuid`,users[0]!.id);counts.push({...ref,count:Number(rows[0]!.count)});}
 console.log(JSON.stringify({user_count:users.length,role:users[0]!.systemRole,references:counts}));
 console.log(JSON.stringify({audit_actions:await db.audit_logs.findMany({where:{actor_user_id:users[0]!.id},select:{action:true}})}));
 if(process.argv.includes('--remove-authorized-sole-account')){
  const ownId=users[0]!.id;
  assert.equal(users[0]!.systemRole,'PLAYER');
  for(const ref of counts)if(!['sessions','auth_challenges','audit_logs'].includes(ref.table_name))assert.equal(ref.count,0,'Unexpected non-auth account relation: preserving all data');
  const report=await db.$transaction(async tx=>{
   await tx.$executeRaw`LOCK TABLE users IN ACCESS EXCLUSIVE MODE`;
   assert.equal(await tx.user.count(),1);assert.ok(await tx.user.findUnique({where:{id:ownId}}));
   const audit=await tx.audit_logs.findMany({where:{actor_user_id:ownId},select:{action:true}});
   assert.ok(audit.every(row=>['EMAIL_VERIFIED','SESSION_CREATED'].includes(row.action)),'Unexpected audit must be preserved');
   const unrelatedBefore={clans:await tx.clan.count(),news:await tx.news.count(),servers:await tx.game_servers.count()};
   await tx.auth_challenges.deleteMany({where:{user_id:ownId}});
   await tx.auth_tokens.deleteMany({where:{session:{user_id:ownId}}});
   await tx.sessions.deleteMany({where:{user_id:ownId}});
   await tx.audit_logs.deleteMany({where:{actor_user_id:ownId}});
   await tx.user.delete({where:{id:ownId}});
   assert.equal(await tx.user.count(),0);
   assert.deepEqual({clans:await tx.clan.count(),news:await tx.news.count(),servers:await tx.game_servers.count()},unrelatedBefore);
   return {passed:true,authorized_local_accounts_removed:1,remaining_accounts:0,unrelated_project_data_preserved:true};
  });
  writeFileSync(new URL('../../../.tools/friends-account-removal-result.json',import.meta.url),JSON.stringify(report,null,2));
  console.log('Authorized sole local account and its authentication records removed; other project data preserved.');
 }
} finally {await db.$disconnect();}
