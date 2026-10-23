import {cancelCompetition,cancelWar} from '../src/clan-gameplay-admin.js';
import 'reflect-metadata';
import { recordPresence } from '../src/clan-presence.js';
import { ClansService } from '../src/modules/clans/clans.service.js';
import { PaginationService } from '../src/infrastructure/pagination.js';
import type { DatabaseService } from '../src/infrastructure/database.service.js';
import { scheduleWar, captureTerritory, advanceWars } from '../src/clan-wars.js';
import { startBossCompetition, settleBossCompetitions } from '../src/clan-competitions.js';
import '../src/environment.js';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { recordBossKill } from '../src/clan-gameplay.js';

const url = process.env.DATABASE_URL;
assert.ok(url);
const parsed = new URL(url);
assert.equal(parsed.hostname, '127.0.0.1');
assert.equal(parsed.port, '54329');
const isolated=process.env.AOC_GAMEPLAY_TEST_DATABASE;
if(isolated){assert.match(isolated,/^animeoncraft_gameplay_test_[a-f0-9]{32}$/);assert.equal(parsed.pathname,`/${isolated}`);}
else assert.equal(parsed.pathname,'/animeoncraft');
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
const clanId = randomUUID(), serverId = randomUUID(), eventId = randomUUID();
const rollback = new Error('Intentional fixture rollback');
try {
  await assert.rejects(db.$transaction(async tx => {
    await tx.$executeRaw`INSERT INTO clans (id,name,short_code,code_key,enabled) VALUES (${clanId}::uuid,'Gameplay fixture',${'T' + clanId.replaceAll('-', '').slice(0, 11).toUpperCase()},${'T' + clanId.replaceAll('-', '').slice(0, 11).toUpperCase()},true)`;
    await tx.$executeRaw`INSERT INTO game_servers (id,name) VALUES (${serverId}::uuid,'Gameplay fixture')`;
    const fixtureEmail=`gameplay-${randomUUID()}@example.invalid`;
    const fixtureUser=await tx.user.create({data:{email:fixtureEmail,emailKey:fixtureEmail,passwordHash:'UNUSABLE_TEST_FIXTURE'}});
    await tx.clan_members.create({data:{clan_id:clanId,user_id:fixtureUser.id}});
    const snapshotTime=new Date();
    assert.equal(await recordPresence(tx,serverId,snapshotTime,[{user_id:fixtureUser.id},{user_id:fixtureUser.id}]),true);
    assert.equal(await recordPresence(tx,serverId,new Date(snapshotTime.getTime()-1000),[]),false);
    const catalog=new ClansService({client:tx} as unknown as DatabaseService,new PaginationService());
    assert.equal((await catalog.detail(clanId)).online_count,1,'Older snapshots cannot hide a connected member');
    assert.equal(await recordPresence(tx,serverId,new Date(snapshotTime.getTime()+1000),[]),true);
    assert.equal((await catalog.detail(clanId)).online_count,0,'A fresh empty server reports zero, not unknown');
    assert.equal((await catalog.list({limit:20})).items.find(clan=>clan.id===clanId)?.online_count,0);
    const seen=await tx.clan_online_presence.findUniqueOrThrow({where:{server_id_user_id:{server_id:serverId,user_id:fixtureUser.id}}});
    assert.equal(seen.last_seen_at.getTime(),snapshotTime.getTime(),'Empty snapshots must preserve the actual last seen time');
    await recordPresence(tx,serverId,new Date(snapshotTime.getTime()+2000),[{user_id:fixtureUser.id}]);
    await tx.$executeRaw`UPDATE clan_server_presence SET snapshot_at=now()-interval '91 seconds' WHERE server_id=${serverId}::uuid`;
    assert.equal((await catalog.detail(clanId)).online_count,null,'Recent delivery cannot make an old snapshot fresh');
    const freshServer=await tx.game_servers.create({data:{name:'Fresh empty presence fixture'}});
    await recordPresence(tx,freshServer.id,new Date(),[]);
    assert.equal((await catalog.detail(clanId)).online_count,0,'A fresh server must not revive another server\'s stale players');
    assert.equal((await catalog.list({limit:20})).items.find(clan=>clan.id===clanId)?.online_count,0);
    assert.equal((await catalog.progression(clanId)).achievements.length,13);
    for(const code of ['SCAM','NYA'])await tx.clan.upsert({where:{codeKey:code},create:{name:code,shortCode:code,codeKey:code,enabled:true},update:{enabled:true}});
    if(isolated){
      const email=`owner-${randomUUID()}@example.invalid`;
      const owner=await tx.user.create({data:{email,emailKey:email,passwordHash:'UNUSABLE_TEST_FIXTURE',systemRole:'OWNER'}});
      await startBossCompetition(tx,owner.id,10);
      await assert.rejects(startBossCompetition(tx,owner.id,30),/already running/);
      assert.equal((await cancelCompetition(tx,owner.id)).cancelled,true);
      assert.equal((await cancelCompetition(tx,owner.id)).cancelled,false);
      const planned=await scheduleWar(tx,{actorId:owner.id,attacker:'SCAM',defender:'NYA',preparationMinutes:10,raidMinutes:10});
      await assert.rejects(scheduleWar(tx,{actorId:owner.id,attacker:'NYA',defender:'SCAM',preparationMinutes:20,raidMinutes:30}),/active war or cooldown/);
      assert.equal((await cancelWar(tx,owner.id,'SCAM','NYA')).cancelled,true);
      assert.equal((await tx.clan_war_windows.findUniqueOrThrow({where:{id:planned.id}})).status,'CANCELLED');
      const active=await scheduleWar(tx,{actorId:owner.id,attacker:'SCAM',defender:'NYA',preparationMinutes:10,raidMinutes:10});
      await tx.$executeRaw`UPDATE clan_war_windows SET preparation_starts_at=now()-interval '20 minutes',active_starts_at=now()-interval '10 minutes' WHERE id=${active.id}::uuid`;
      assert.equal((await cancelWar(tx,owner.id,'SCAM','NYA')).cancelled,true);
      assert.equal((await tx.clan_war_windows.findUniqueOrThrow({where:{id:active.id}})).status,'COOLDOWN');
      await assert.rejects(scheduleWar(tx,{actorId:owner.id,attacker:'SCAM',defender:'NYA',preparationMinutes:10,raidMinutes:10}),/active war or cooldown/);
    }
    const competitionId = randomUUID();
    await tx.$executeRaw`INSERT INTO clan_events (id,code,title,status,starts_at,ends_at,payload)
      VALUES (${competitionId}::uuid,${'fixture-' + competitionId},'Fixture race','ACTIVE',now()-interval '1 minute',now()+interval '1 hour','{"kind":"BOSS_HUNT"}'::jsonb)`;
    await assert.rejects(startBossCompetition(tx, randomUUID(), 30), /Owner required/);
    const input = { eventId, serverId, clanId, occurredAt: new Date() };
    assert.deepEqual(await recordBossKill(tx, input), { duplicate: false, unlocked: ['FIRST_BOSS'] });
    assert.deepEqual(await recordBossKill(tx, input), { duplicate: true, unlocked: [] });
    for (let kill = 2; kill <= 50; kill++) {
      const result = await recordBossKill(tx, { ...input, eventId: randomUUID() });
      assert.deepEqual(result.unlocked, kill === 10 ? ['BOSS_HUNTERS'] : kill === 50 ? ['BOSS_VETERANS'] : []);
    }
    const rows = await tx.$queryRaw<Array<{ xp: bigint; reputation: number; level: number; kills: number }>>`
      SELECT xp,reputation,level,(statistics->>'BOSS_KILL')::int AS kills FROM clan_progression WHERE clan_id=${clanId}::uuid`;
    assert.deepEqual(rows, [{ xp: 7600n, reputation: 325, level: 9, kills: 50 }]);
    const achievements = await tx.$queryRaw<Array<{ count: bigint }>>`SELECT count(*) AS count FROM clan_achievements WHERE clan_id=${clanId}::uuid AND unlocked_at IS NOT NULL`;
    assert.equal(achievements[0]?.count, 3n);
    const scores = await tx.$queryRaw<Array<{score:bigint}>>`SELECT score FROM clan_event_scores WHERE event_id=${competitionId}::uuid AND clan_id=${clanId}::uuid`;
    assert.equal(scores[0]?.score,50n);
    await tx.$executeRaw`UPDATE clan_events SET starts_at=now()-interval '1 hour',ends_at=now()-interval '11 minutes' WHERE id=${competitionId}::uuid`;
    const competitors=await tx.clan.findMany({where:{codeKey:{in:['SCAM','NYA']}}});
    for(const clan of competitors)await tx.clan_event_scores.create({data:{event_id:competitionId,clan_id:clan.id,score:clan.codeKey==='SCAM'?50n:1n}});
    await settleBossCompetitions(tx);
    await settleBossCompetitions(tx);
    for(const clan of competitors){
      const progression=await tx.clan_progression.findUniqueOrThrow({where:{clan_id:clan.id}});
      if(isolated)assert.equal(progression.xp,clan.codeKey==='SCAM'?1000n:300n);
    }
    const awarded = await tx.$queryRaw<Array<{xp:bigint}>>`SELECT xp FROM clan_progression WHERE clan_id=${clanId}::uuid`;
    assert.equal(awarded[0]?.xp,8600n,'Winner bonus must be applied once across repeated settlement');
    const finished = await tx.$queryRaw<Array<{status:string;reward_granted_at:Date|null}>>`SELECT e.status,s.reward_granted_at FROM clan_events e JOIN clan_event_scores s ON s.event_id=e.id WHERE e.id=${competitionId}::uuid`;
    assert.ok(finished.every(row=>row.status==='FINISHED'&&row.reward_granted_at));
    await assert.rejects(recordBossKill(tx, { ...input, clanId: randomUUID() }), /Event identity conflict/);
    const warId=randomUUID();
    for(const code of ['SCAM','NYA'])await tx.clan.upsert({where:{codeKey:code},create:{name:code,shortCode:code,codeKey:code,enabled:true},update:{enabled:true}});
    const clans=await tx.clan.findMany({where:{codeKey:{in:['SCAM','NYA']}}});
    const attacker=clans.find(clan=>clan.codeKey==='SCAM')!.id, defender=clans.find(clan=>clan.codeKey==='NYA')!.id;
    const before=await tx.$queryRaw<Array<{xp:bigint}>>`SELECT xp FROM clan_progression WHERE clan_id=${attacker}::uuid`;
    await tx.$executeRaw`INSERT INTO clan_war_windows (id,attacker_clan_id,defender_clan_id,status,preparation_starts_at,active_starts_at,active_ends_at,reason)
      VALUES (${warId}::uuid,${attacker}::uuid,${defender}::uuid,'ACTIVE',now()-interval '20 minutes',now()-interval '10 minutes',now()+interval '10 minutes','Rollback fixture')`;
    await tx.$executeRaw`INSERT INTO clan_territory_control (territory_code,controller_clan_id,captured_from_clan_id,war_window_id)
      VALUES ('NYA',${defender}::uuid,${attacker}::uuid,${warId}::uuid)
      ON CONFLICT(territory_code) DO UPDATE SET controller_clan_id=EXCLUDED.controller_clan_id,captured_from_clan_id=EXCLUDED.captured_from_clan_id,war_window_id=EXCLUDED.war_window_id`;
    const capture={eventId:randomUUID(),serverId,warId,territory:'NYA',attacker:'SCAM',defender:'NYA',completedAt:new Date()};
    await assert.rejects(captureTerritory(tx,{...capture,territory:'SCAM'}),/Defender no longer controls/);
    assert.deepEqual(await captureTerritory(tx,capture),{duplicate:false});
    assert.deepEqual(await captureTerritory(tx,{...capture,eventId:randomUUID()}),{duplicate:true});
    await tx.$executeRaw`UPDATE clan_war_windows SET active_ends_at=now()-interval '1 minute' WHERE id=${warId}::uuid`;
    await advanceWars(tx);
    assert.equal((await tx.clan_war_windows.findUniqueOrThrow({where:{id:warId}})).status,'FINISHED');
    assert.deepEqual(await captureTerritory(tx,capture),{duplicate:true},'Lost acknowledgement must remain retryable after the war ends');
    const after=await tx.$queryRaw<Array<{xp:bigint}>>`SELECT xp FROM clan_progression WHERE clan_id=${attacker}::uuid`;
    assert.equal(after[0]?.xp,(before[0]?.xp??0n)+500n);
    throw rollback;
  }, { timeout: 30000 }), error => error === rollback);
  const leftovers = await db.$queryRaw<Array<{ count: bigint }>>`SELECT count(*) AS count FROM clan_gameplay_events WHERE event_id=${eventId}::uuid`;
  assert.equal(leftovers[0]?.count, 0n);
  assert.equal(await db.clan.count({ where: { id: clanId } }), 0);
  console.log('PostgreSQL gameplay passed: runtime-role writes, 50 kills, duplicate replay, three one-time bonuses, competition settlement, war capture/replay, ordered presence and public catalog, conflict and complete rollback. No fixtures retained.');
} finally { await db.$disconnect(); }

