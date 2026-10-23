import '../src/environment.js';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {PrismaPg} from '@prisma/adapter-pg';
import {PrismaClient} from '../src/generated/prisma/client.js';
import {recordBossKill} from '../src/clan-gameplay.js';
import {settleBossCompetitions} from '../src/clan-competitions.js';
import {captureTerritory} from '../src/clan-wars.js';

const name=process.env.AOC_GAMEPLAY_TEST_DATABASE,url=process.env.DATABASE_URL;
assert.ok(name&&url);assert.match(name,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const parsed=new URL(url);assert.equal(parsed.hostname,'127.0.0.1');assert.equal(parsed.port,'54329');assert.equal(parsed.pathname,`/${name}`);
const db=new PrismaClient({adapter:new PrismaPg({connectionString:url})});
const options={timeout:10000,maxWait:10000};
try{
  const clan=await db.clan.create({data:{name:'Concurrent fixture',shortCode:'TEST',codeKey:'TEST',enabled:true}});
  const server=await db.game_servers.create({data:{name:'Concurrent fixture'}});
  const input={eventId:randomUUID(),serverId:server.id,clanId:clan.id,occurredAt:new Date()};
  const repeated=await Promise.all(Array.from({length:10},()=>db.$transaction(tx=>recordBossKill(tx,input),options)));
  assert.equal(repeated.filter(result=>!result.duplicate).length,1);
  await Promise.all(Array.from({length:20},()=>db.$transaction(tx=>recordBossKill(tx,{...input,eventId:randomUUID()}),options)));
  const progression=await db.clan_progression.findUniqueOrThrow({where:{clan_id:clan.id}});
  assert.equal(progression.xp,2700n);assert.equal(progression.reputation,130);assert.equal(progression.level,6);
  const failed={...input,eventId:randomUUID()};const fault=new Error('Simulated failure after writes');
  await assert.rejects(db.$transaction(async tx=>{await recordBossKill(tx,failed);throw fault;},options),error=>error===fault);
  assert.equal(await db.clan_gameplay_events.count({where:{event_id:failed.eventId}}),0);
  assert.equal((await db.$transaction(tx=>recordBossKill(tx,failed),options)).duplicate,false);
  const competition=await db.clan_events.create({data:{code:'concurrent-'+randomUUID(),title:'Concurrent settlement',status:'ACTIVE',starts_at:new Date(Date.now()-3600000),ends_at:new Date(Date.now()-1200000),payload:{kind:'BOSS_HUNT'}}});
  await db.clan_event_scores.create({data:{event_id:competition.id,clan_id:clan.id,score:1n}});
  await Promise.all(Array.from({length:10},()=>db.$transaction(tx=>settleBossCompetitions(tx),options)));
  assert.equal((await db.clan_progression.findUniqueOrThrow({where:{clan_id:clan.id}})).xp,3800n);
  const attacker=await db.clan.create({data:{name:'SCAM',shortCode:'SCAM',codeKey:'SCAM',enabled:true}});
  const defender=await db.clan.create({data:{name:'NYA',shortCode:'NYA',codeKey:'NYA',enabled:true}});
  const war=await db.clan_war_windows.create({data:{attacker_clan_id:attacker.id,defender_clan_id:defender.id,status:'ACTIVE',preparation_starts_at:new Date(Date.now()-1200000),active_starts_at:new Date(Date.now()-600000),active_ends_at:new Date(Date.now()+600000),reason:'Concurrency fixture'}});
  const captures=await Promise.all(Array.from({length:10},()=>db.$transaction(tx=>captureTerritory(tx,{eventId:randomUUID(),serverId:server.id,warId:war.id,territory:'NYA',attacker:'SCAM',defender:'NYA',completedAt:new Date()}),options)));
  assert.equal(captures.filter(result=>!result.duplicate).length,1);
  assert.equal((await db.clan_progression.findUniqueOrThrow({where:{clan_id:attacker.id}})).xp,500n);
  console.log('Concurrent SQL passed: duplicate events, independent kills, rollback/retry, settlement and capture each award exactly once. Fixtures exist only in the disposable database.');
}finally{await db.$disconnect();}
