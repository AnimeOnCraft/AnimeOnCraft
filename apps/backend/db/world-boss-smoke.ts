import '../src/environment.js';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
import {startWorldBoss,currentWorldBoss,worldBossProgress,finishWorldBoss} from '../src/world-boss.js';
import {cancelCompetition} from '../src/clan-gameplay-admin.js';
const url=process.env.DATABASE_URL,name=process.env.AOC_GAMEPLAY_TEST_DATABASE;
assert.ok(url&&name);assert.match(name,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const parsed=new URL(url);assert.equal(parsed.hostname,'127.0.0.1');assert.equal(parsed.pathname,`/${name}`);
const db=new PrismaClient({adapter:new PrismaPg({connectionString:url})}),rollback=new Error('World boss fixture rollback');
try{
 await assert.rejects(db.$transaction(async tx=>{
  const email=`boss-${randomUUID()}@example.invalid`;
  const owner=await tx.user.create({data:{email,emailKey:email,passwordHash:'UNUSABLE_FIXTURE',systemRole:'OWNER'}});
  const server=await tx.game_servers.create({data:{name:'World boss fixture'}});
  for(const code of ['SCAM','NYA','YAKUZA'])await tx.clan.upsert({where:{codeKey:code},create:{name:code,shortCode:code,codeKey:code,enabled:true},update:{enabled:true}});
  await assert.rejects(startWorldBoss(tx,randomUUID(),server.id,30),/Owner required/);
  const event=await startWorldBoss(tx,owner.id,server.id,30);
  assert.equal((await currentWorldBoss(tx,server.id))?.boss_uuid,event.boss_uuid);
  assert.equal(await currentWorldBoss(tx,randomUUID()),null);
  await assert.rejects(startWorldBoss(tx,owner.id,server.id,30),/active/);
  const identity={server_id:server.id,competition_id:event.id,boss_uuid:event.boss_uuid};
  const arena={x:100,y:75,z:100},contributions=[{clan_code:'SCAM',damage_milli:750},{clan_code:'NYA',damage_milli:250},{clan_code:'YAKUZA',damage_milli:1}];
  await worldBossProgress(tx,{...identity,arena,revision:0,contributions:[]});
  await assert.rejects(worldBossProgress(tx,{...identity,arena,revision:1,contributions}),/not appeared/);
  await tx.$executeRaw`UPDATE clan_events SET starts_at=now()-interval '1 minute' WHERE id=${event.id}::uuid`;
  await worldBossProgress(tx,{...identity,arena,revision:1,contributions});
  await worldBossProgress(tx,{...identity,arena,revision:0,contributions:[]});
  await assert.rejects(worldBossProgress(tx,{...identity,arena,revision:2,contributions:[]}),/decrease/);
  const result={...identity,event_id:randomUUID(),outcome:'KILLED',completed_at:new Date().toISOString(),contributions};
  assert.equal((await finishWorldBoss(tx,result)).duplicate,false);
  assert.equal((await finishWorldBoss(tx,result)).duplicate,true);
  await assert.rejects(finishWorldBoss(tx,{...result,event_id:randomUUID()}),/identity conflict/);
  for(const [code,xp,rep] of [['SCAM',1600,50],['NYA',600,20]] as const){
   const clan=await tx.clan.findUniqueOrThrow({where:{codeKey:code}}),progress=await tx.clan_progression.findUniqueOrThrow({where:{clan_id:clan.id}});
   assert.equal(progress.xp,BigInt(xp));assert.equal(progress.reputation,rep);
  }
  assert.equal(await currentWorldBoss(tx,server.id),null);
  const expired=await startWorldBoss(tx,owner.id,server.id,10);
  await tx.$executeRaw`UPDATE clan_events SET starts_at=now()-interval '20 minutes',ends_at=now()-interval '1 minute' WHERE id=${expired.id}::uuid`;
  await finishWorldBoss(tx,{server_id:server.id,competition_id:expired.id,boss_uuid:expired.boss_uuid,event_id:randomUUID(),outcome:'EXPIRED',completed_at:new Date().toISOString(),contributions});
  assert.equal((await tx.clan_event_scores.findMany({where:{event_id:expired.id}})).filter(row=>row.reward_granted_at!==null).length,0);
  await startWorldBoss(tx,owner.id,server.id,10);assert.equal((await cancelCompetition(tx,owner.id)).cancelled,true);assert.equal(await currentWorldBoss(tx,server.id),null);
  throw rollback;
 },{timeout:30000}),error=>error===rollback);
 console.log('World boss SQL passed: countdown, server binding, cumulative damage, proportional rewards/achievements once, replay/conflict, timeout without rewards and cancellation. Fixture rolled back.');
}finally{await db.$disconnect();}
