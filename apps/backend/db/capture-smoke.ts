/**
 * Disposable-database check of the capture lifecycle (World Design stage 4), run by test:gameplay:integration.
 * Time is explicit: every call gets its own `now`, so protection (6 h), lease (24 h) and neutralisation (+24 h)
 * are checked without waiting. Data is committed into the disposable database and dropped with it.
 */
import '../src/environment.js';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { adminCapture, advanceCapture, declareCapture, finishCapture, refreshedSite, snapshotCapture, syncCapture, voidCapture } from '../src/capture/lifecycle.js';
import { applyCatalog, readWorldEvents, readWorldPoints } from '../src/world-catalog/catalog-store.js';
import { loadCatalog } from '../src/world-catalog/load.js';

const url = process.env.DATABASE_URL, name = process.env.AOC_GAMEPLAY_TEST_DATABASE;
assert.ok(url && name); assert.match(name, /^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const parsed = new URL(url); assert.equal(parsed.hostname, '127.0.0.1'); assert.equal(parsed.pathname, `/${name}`);
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
type Tx = Parameters<Parameters<typeof db.$transaction>[0]>[0];
const tx = <T>(work: (t: Tx) => Promise<T>) => db.$transaction(work, { timeout: 30000 });

// 16:00 Yekaterinburg: inside the 15:00–23:30 declaration window.
const base = Date.parse('2026-10-21T11:00:00.000Z');
const at = (seconds: number) => new Date(base + seconds * 1000);
const colors: Record<string, string> = { SCAM: 'E84A5F', NYA: 'F47DCB', AKUMA: 'A970FF', ZXC: '4DA8FF', YAKUZA: 'FFB347' };
try {
  const serverId = randomUUID();
  const members: Record<string, string[]> = {};
  let ownerId = '';
  await tx(async t => {
    for (const [code, color] of Object.entries(colors))
      await t.$executeRaw`INSERT INTO clans(name,short_code,code_key,enabled,color) VALUES (${code},${code},${code},true,${color})
        ON CONFLICT (code_key) DO UPDATE SET color=EXCLUDED.color,enabled=true,archived_at=NULL`;
    await t.$executeRaw`INSERT INTO game_servers(id,name) VALUES (${serverId}::uuid,'Capture fixture')`;
    for (const code of Object.keys(colors)) {
      const clanId = (await t.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE code_key=${code}`)[0]!.id;
      members[code] = [];
      for (let i = 0; i < 2; i++) {
        const email = `capture-${code.toLowerCase()}-${i}-${randomUUID()}@example.invalid`;
        const user = await t.user.create({ data: { email, emailKey: email, passwordHash: 'UNUSABLE_TEST_FIXTURE' } });
        await t.$executeRaw`UPDATE clan_members SET ended_at=now(),end_reason='fixture' WHERE user_id=${user.id}::uuid AND ended_at IS NULL`;
        await t.clan_members.create({ data: { clan_id: clanId, user_id: user.id } });
        members[code]!.push(user.id);
      }
    }
    // At most one OWNER exists; an earlier fixture in the same disposable database may have created it.
    const existing = await t.user.findFirst({ where: { systemRole: 'OWNER', state: 'ACTIVE' } });
    const email = `capture-owner-${randomUUID()}@example.invalid`;
    ownerId = existing?.id ?? (await t.user.create({ data: { email, emailKey: email, passwordHash: 'UNUSABLE_TEST_FIXTURE', systemRole: 'OWNER' } })).id;
    const catalog = await loadCatalog(fileURLToPath(new URL('../../../minecraft/server/world-config/world-states.toml', import.meta.url)));
    await applyCatalog(t, catalog, { activate: true, reason: 'capture fixture' });
  });
  const declare = (clan: string, point: string, now: Date, players = 2, defenders = 1, user = 0) =>
    tx(t => declareCapture(t, { serverId, pointCode: point, actorUserId: members[clan]![user]!, clanCode: clan, playersInZone: players, defendersOnline: defenders, now }));
  const score = (clan: string, progress: number, presence = 60, kills = 0) => ({ clan_code: clan, progress, kills, deaths: 0, presence_seconds: presence, peak_players: 2 });
  const point = (code: string) => tx(async t => (await t.$queryRaw<Array<{ owner: string | null; capture_state: string; protection_until: Date | null; lease_until: Date | null }>>`
    SELECT c.code_key AS owner,p.capture_state,p.protection_until,p.lease_until FROM strategic_points p LEFT JOIN clans c ON c.id=p.owner_clan_id WHERE p.code=${code}`)[0]!);
  const progression = (clan: string) => tx(async t => (await t.$queryRaw<Array<{ xp: bigint; reputation: number }>>`
    SELECT p.xp,p.reputation FROM clan_progression p JOIN clans c ON c.id=p.clan_id WHERE c.code_key=${clan}`)[0] ?? { xp: 0n, reputation: 0 });
  const site = 'site.ring.36';

  // 1. A neutral T1 site: one player is enough; the battle starts after 5 minutes of preparation.
  const first = await tx(t=>declareCapture(t,{serverId,pointCode:site,actorUserId:members.SCAM![0]!,clanCode:'SCAM',playersInZone:1,defendersOnline:0,now:at(0),autoNeutral:true}));
  assert.equal(first.accepted, true);
  if (!first.accepted) throw new Error('unreachable');
  assert.equal(first.event.preparation_seconds, 300);
  assert.equal((await point(site)).capture_state, 'CONTESTED');
  assert.deepEqual(await tx(t=>declareCapture(t,{serverId,pointCode:site,actorUserId:members.NYA![0]!,clanCode:'NYA',playersInZone:2,defendersOnline:0,now:at(1),autoNeutral:true})),
    {accepted:false,reason:'NOT_NEUTRAL'},'automatic retry cannot start a second capture');
  assert.deepEqual(await declare('NYA', site, at(10)), { accepted: false, reason: 'RUNNING' });
  assert.deepEqual(await declare('NYA', 'site.w1.t2', at(10), 1), { accepted: false, reason: 'TOO_FEW_PLAYERS' }, 'T2 needs two players');
  assert.deepEqual(await tx(t => declareCapture(t, { serverId, pointCode: site, actorUserId: members.NYA![0]!, clanCode: 'SCAM', playersInZone: 2, defendersOnline: 0, now: at(11) })),
    { accepted: false, reason: 'NOT_MEMBER' }, 'the game server cannot declare for another clan');
  await assert.rejects(tx(t => finishCapture(t, { serverId, eventId: first.event.id, winnerCode: 'SCAM', scores: [score('SCAM', 100)], now: at(20) })), /not started/);
  const synced = await tx(t => syncCapture(t, at(301)));
  assert.equal(synced.events.find(e => e.id === first.event.id)?.state, 'ACTIVE');
  assert.equal((await point(site)).capture_state, 'CAPTURING');
  assert.ok(synced.rules && synced.points.length === 57, `sync lists the capturable points (${synced.points.length})`);
  const publicPoints = await tx(t => readWorldPoints(t));
  const running = publicPoints!.items.find(p => p.event);
  assert.equal(running?.event?.declared_by.short_code, 'SCAM');
  assert.equal(running?.event?.phase, 'ACTIVE');
  const victimId=randomUUID();
  await tx(t => snapshotCapture(t, { serverId, eventId: first.event.id, phase: 'ACTIVE', scores: [score('SCAM', 40)], now: at(450),killLimits:[[victimId,2,Math.floor(at(450).getTime()/1000)]] }));
  const resumedSnapshot=(await tx(t=>syncCapture(t,at(450)))).events.find(e=>e.id===first.event.id)?.snapshot as Record<string,unknown>|null|undefined;
  assert.deepEqual(resumedSnapshot?.kill_limits,[[victimId,2,Math.floor(at(450).getTime()/1000)]]);
  await assert.rejects(tx(t=>snapshotCapture(t,{serverId,eventId:first.event.id,phase:'ACTIVE',scores:[],now:at(450),killLimits:[['bad-victim',999,-1]]})),/Invalid kill limits/);
  await assert.rejects(tx(t => snapshotCapture(t, { serverId: randomUUID(), eventId: first.event.id, phase: 'ACTIVE', scores: [], now: at(451) })), /another server/);
  await assert.rejects(tx(t => finishCapture(t, { serverId, eventId: first.event.id, winnerCode: 'SCAM', scores: [score('SCAM', 45)], now: at(455) })), /capture rules/,
    'a winner below 100 % before the end needs 50 % and a 10-point lead');

  // 2. Concurrent duplicate results credit the winner once.
  const before = await progression('SCAM');
  const results = await Promise.all([0, 1, 2].map(() => tx(t => finishCapture(t, { serverId, eventId: first.event.id, winnerCode: 'SCAM', scores: [score('SCAM', 100)], now: at(661) }))));
  assert.equal(results.filter(r => !r.duplicate).length, 1);
  assert.equal(results.find(r => !r.duplicate)?.outcome, 'CAPTURED');
  const after = await progression('SCAM');
  const credited=await tx(t=>t.$queryRaw<Array<{count:bigint;xp:bigint}>>`SELECT count(*) AS count,sum(xp_delta)::bigint AS xp FROM clan_gameplay_events WHERE event_id=${first.event.id}::uuid`);
  assert.deepEqual([credited[0]!.count,credited[0]!.xp],[1n,150n],'T1 capture ledger credited once; achievements are separate');
  assert.ok(after.xp-before.xp>=150n);
  let state = await point(site);
  assert.deepEqual([state.owner, state.capture_state], ['SCAM', 'PROTECTED']);
  assert.deepEqual(await tx(t=>declareCapture(t,{serverId,pointCode:site,actorUserId:members.NYA![0]!,clanCode:'NYA',playersInZone:2,defendersOnline:0,now:at(662),autoNeutral:true})),
    {accepted:false,reason:'NOT_NEUTRAL'},'stale neutral view cannot auto-attack a newly controlled point');
  assert.equal(state.protection_until?.getTime(), at(661 + 21600).getTime());
  assert.equal(state.lease_until?.getTime(), at(661 + 86400).getTime());

  // 3. PROTECTED refuses declarations; after six hours the owner holds it as CONTROLLED and it can be attacked.
  assert.equal((await declare('NYA', site, at(700))).reason, 'PROTECTED');
  await tx(t => advanceCapture(t, at(661 + 21601)));
  assert.equal((await point(site)).capture_state, 'CONTROLLED');
  assert.equal((await declare('SCAM', site, at(661 + 21602))).reason, 'OWN_POINT');
  const attack = await declare('NYA', site, at(661 + 21610), 2, 0);
  assert.equal(attack.accepted, true);
  if (!attack.accepted) throw new Error('unreachable');
  assert.equal(attack.event.preparation_seconds, 900, 'no defender online: longer preparation');
  const attackStart = 661 + 21610 + 900;
  await tx(t => advanceCapture(t, at(attackStart + 1)));
  await tx(t => snapshotCapture(t, { serverId, eventId: attack.event.id, phase: 'ACTIVE', scores: [score('NYA', 30), score('SCAM', 20)], now: at(attackStart + 200) }));
  // Still alive four minutes later thanks to the snapshot; the battle ends without a winner -> DEFENDED.
  await tx(t => advanceCapture(t, at(attackStart + 450)));
  assert.equal((await point(site)).capture_state, 'CAPTURING');
  for (const second of [400, 600, 850]) await tx(t => snapshotCapture(t, { serverId, eventId: attack.event.id, phase: 'ACTIVE', scores: [score('NYA', 38), score('SCAM', 33)], now: at(attackStart + second) }));
  const scamBefore = await progression('SCAM');
  const defended = await tx(t => finishCapture(t, { serverId, eventId: attack.event.id, winnerCode: null, scores: [score('NYA', 40), score('SCAM', 35)], now: at(attackStart + 900) }));
  assert.equal(defended.outcome, 'DEFENDED');
  state = await point(site);
  assert.deepEqual([state.owner, state.capture_state], ['SCAM', 'CONTROLLED']);
  assert.ok((await progression('SCAM')).xp-scamBefore.xp>=50n);
  const defenceLedger=await tx(t=>t.$queryRaw<Array<{xp:bigint}>>`SELECT xp_delta AS xp FROM clan_gameplay_events WHERE kind='POINT_DEFENCE' AND payload->>'capture_event_id'=${attack.event.id}`);
  assert.deepEqual(defenceLedger.map(row=>row.xp),[50n],'present defender credited once, separately from achievements');
  const cooldown = await declare('NYA', site, at(attackStart + 1000));
  assert.equal(cooldown.reason, 'COOLDOWN');

  // 4. Lease ends -> AVAILABLE (half yield); the owner renews with a short preparation.
  await tx(t => advanceCapture(t, at(661 + 86401)));
  assert.equal((await point(site)).capture_state, 'AVAILABLE');
  const sync2 = await tx(t => syncCapture(t, at(661 + 86402)));
  assert.equal(sync2.points.find(p => p.code === site)?.resource?.multiplier, 0.5);
  const renewal = await declare('SCAM', site, at(661 + 86410), 1);
  assert.equal(renewal.accepted && renewal.event.preparation_seconds, 120);
  if (!renewal.accepted) throw new Error('unreachable');
  await tx(t => advanceCapture(t, at(661 + 86410 + 121)));
  await tx(t => snapshotCapture(t, { serverId, eventId: renewal.event.id, phase: 'ACTIVE', scores: [score('SCAM', 70)], now: at(661 + 86410 + 380) }));
  const renewed = await tx(t => finishCapture(t, { serverId, eventId: renewal.event.id, winnerCode: 'SCAM', scores: [score('SCAM', 100)], now: at(661 + 86410 + 500) }));
  assert.equal(renewed.outcome, 'RENEWED');
  state = await point(site);
  assert.equal(state.capture_state, 'PROTECTED');
  const renewedAt = 661 + 86410 + 500;
  assert.equal(state.lease_until?.getTime(), at(renewedAt + 86400).getTime());

  // 5. Nobody renews: 24 h lease + 24 h AVAILABLE -> NEUTRAL, ownership closed.
  await tx(t => advanceCapture(t, at(renewedAt + 86400 + 86401)));
  state = await point(site);
  assert.deepEqual([state.owner, state.capture_state], [null, 'NEUTRAL']);
  const open = await tx(t => t.$queryRaw<Array<{ n: bigint }>>`SELECT count(*) AS n FROM resource_site_ownership o JOIN strategic_points p ON p.id=o.point_id WHERE p.code=${site} AND o.released_at IS NULL`);
  assert.equal(open[0]!.n, 0n);
  const day3 = renewedAt + 86400 + 86401 + 60;

  // 6. The influence limit.
  for (const code of ['site.w1.t3', 'site.w2.t3', 'site.w5.t2']) // 3 + 3 + 2 = 8 = base capacity
    assert.equal((await tx(t => adminCapture(t, { action: 'TRANSFER', actorUserId: ownerId, pointCode: code, clanCode: 'ZXC', reason: 'fixture', now: at(day3) }))).changed, true);
  assert.equal((await declare('ZXC', 'site.ring.108', at(day3 + 1), 1)).reason, 'INFLUENCE');
  const ranks = (await tx(t => syncCapture(t, at(day3 + 2)))).points.filter(p => p.owner === 'ZXC').map(p => p.resource?.multiplier).sort();
  assert.deepEqual(ranks, [0.85, 1, 1], 'third point of a clan yields less');
  await assert.rejects(tx(t => adminCapture(t, { action: 'RESET', actorUserId: members.ZXC![0]!, pointCode: 'site.w1.t3', now: at(day3 + 3) })), /Owner required/);
  await tx(t => adminCapture(t, { action: 'RESET', actorUserId: ownerId, pointCode: 'site.w1.t3', reason: 'fixture reset', now: at(day3 + 3) }));
  assert.equal((await point('site.w1.t3')).capture_state, 'NEUTRAL');
  const audits = await tx(t => t.$queryRaw<Array<{ n: bigint }>>`SELECT count(*) AS n FROM audit_logs WHERE action LIKE 'CAPTURE_%'`);
  assert.ok(audits[0]!.n >= 4n);

  // 7. Per-clan limit, voiding a battle without snapshots (no cooldown), admin cancel.
  const a1 = await declare('AKUMA', 'border.w3.fort', at(day3 + 10));
  const a2 = await declare('AKUMA', 'border.w3.pass', at(day3 + 11));
  assert.ok(a1.accepted && a2.accepted);
  assert.equal((await declare('AKUMA', 'border.w3.village', at(day3 + 12))).reason, 'CLAN_LIMIT');
  await tx(t => advanceCapture(t, at(day3 + 10 + 300 + 301)));
  assert.equal((await point('border.w3.fort')).capture_state, 'NEUTRAL', 'no snapshot for 5 minutes: voided');
  assert.equal((await declare('AKUMA', 'border.w3.fort', at(day3 + 700))).accepted, true, 'a voided attempt has no cooldown');
  if (!a2.accepted) throw new Error('unreachable');
  assert.equal((await tx(t => voidCapture(t, { serverId, eventId: a2.event.id, reason: 'SERVER_RESTART', now: at(day3 + 701) }))).duplicate, true, 'already voided by the clock');
  assert.equal((await tx(t => adminCapture(t, { action: 'CANCEL', actorUserId: ownerId, pointCode: 'border.w3.fort', reason: 'fixture', now: at(day3 + 702) }))).changed, true);
  assert.equal((await point('border.w3.fort')).capture_state, 'NEUTRAL');

  // 8. Resource refresh: due at once, idempotent by due time, rescheduled by the interval.
  const due = (await tx(t => syncCapture(t, at(day3 + 800)))).refreshes.find(r => r.point_code === 'site.ring.108')!;
  assert.ok(due);
  const done = await tx(t => refreshedSite(t, { pointCode: 'site.ring.108', dueAt: new Date(due.due_at), restored: 40, multiplier: due.multiplier, now: at(day3 + 801) }));
  assert.equal(done.duplicate, false);
  assert.equal((await tx(t => refreshedSite(t, { pointCode: 'site.ring.108', dueAt: new Date(due.due_at), restored: 40, multiplier: 1, now: at(day3 + 802) }))).duplicate, true);
  assert.ok(!(await tx(t => syncCapture(t, at(day3 + 803)))).refreshes.some(r => r.point_code === 'site.ring.108'));
  assert.ok((await tx(t => syncCapture(t, at(day3 + 801 + 7200)))).refreshes.some(r => r.point_code === 'site.ring.108'), 'T1 refreshes every 2 h');

  // 8b. A result waiting in the game server outbox survives an API outage longer than the grace.
  const late = await declare('YAKUZA', 'site.ring.252', at(day3 + 900), 1);
  if (!late.accepted) throw new Error(`late declaration refused: ${late.reason}`);
  await tx(t => advanceCapture(t, at(day3 + 1201)));
  await tx(t => snapshotCapture(t, { serverId, eventId: late.event.id, phase: 'ACTIVE', scores: [score('YAKUZA', 90)], now: at(day3 + 1210) }));
  await tx(t => syncCapture(t, at(day3 + 2210), [late.event.id]));
  assert.equal((await point('site.ring.252')).capture_state, 'CAPTURING', 'a finished battle reported by the server is not voided');
  const delivered = await tx(t => finishCapture(t, { serverId, eventId: late.event.id, winnerCode: 'YAKUZA', scores: [score('YAKUZA', 100)], now: at(day3 + 2300) }));
  assert.equal(delivered.outcome, 'CAPTURED');
  // Rounding: a 10.0005-point lead rounds to exactly 10 and is still a win.
  const close = await declare('YAKUZA', 'site.ring.324', at(day3 + 2400), 1);
  if (!close.accepted) throw new Error(`close declaration refused: ${close.reason}`);
  await tx(t => advanceCapture(t, at(day3 + 2701)));
  const rounded = await tx(t => finishCapture(t, { serverId, eventId: close.event.id, winnerCode: 'YAKUZA',
    scores: [score('YAKUZA', 60), score('AKUMA', 50)], now: at(day3 + 2702) }));
  assert.equal(rounded.outcome, 'CAPTURED');

  // 9. Public feed shows the story without technical codes.
  const feed = (await tx(t => readWorldEvents(t)))!;
  const kinds = new Set(feed.items.map(i => i.kind));
  for (const kind of ['DECLARED', 'CAPTURED', 'DEFENDED', 'RENEWED', 'NEUTRALIZED', 'ADMIN_TRANSFER', 'ADMIN_RESET']) assert.ok(kinds.has(kind), `feed has ${kind}`);
  assert.ok(!JSON.stringify(feed).includes('site.'), 'no technical codes in the feed');
  const history = await tx(t => t.$queryRaw<Array<{ event: string }>>`SELECT event FROM resource_site_history h JOIN strategic_points p ON p.id=h.point_id WHERE p.code=${site} ORDER BY h.id`);
  assert.deepEqual(history.map(h => h.event), ['IMPORTED', 'DECLARED', 'ACTIVE', 'CAPTURED', 'PROTECTION_ENDED', 'DECLARED', 'ACTIVE', 'DEFENDED', 'LEASE_EXPIRED',
    'DECLARED', 'ACTIVE', 'RENEWED', 'PROTECTION_ENDED', 'LEASE_EXPIRED', 'NEUTRALIZED']);
  // 10. Nine hours later it is past 23:30 in Yekaterinburg: declarations wait for the next window.
  assert.equal((await declare('ZXC', 'site.ring.180', at(day3 + 9 * 3600), 1)).reason, 'OUT_OF_WINDOW');
  console.log(JSON.stringify({ capture_lifecycle: 'passed', states: ['NEUTRAL', 'CONTESTED', 'CAPTURING', 'PROTECTED', 'CONTROLLED', 'AVAILABLE'],
    outcomes: ['CAPTURED', 'DEFENDED', 'RENEWED', 'VOIDED', 'CANCELLED'], concurrent_duplicates: 3, feed_items: feed.items.length }));
} finally {
  await db.$disconnect();
}
