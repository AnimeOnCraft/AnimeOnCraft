import {refreshExtraClanAchievements} from '../clan-achievements.js';
/**
 * Capture engine, server-authoritative part (World Design stage 4).
 *
 * The backend owns every state change of a strategic point: declarations, phases, owners, timers,
 * rewards and history. The game server only measures the battle (presence, kills, progress) and reports
 * snapshots and the result; see docs/CAPTURE_SYSTEM.md and docs/operations/CAPTURE_STAGE4.md.
 *
 * Every function takes the transaction and an explicit `now` so the lifecycle is testable in time.
 * All mutations run under one advisory lock, so concurrent declarations or duplicate results serialise.
 */
import { createHash, randomUUID } from 'node:crypto';
import type { Prisma } from '../generated/prisma/client.js';
import { captureReward, inWindow, influenceCapacity, influenceWeight, parseRules, rankMultiplier, serverRules, type CaptureRules, type Reward } from './rules.js';

type Tx = Prisma.TransactionClient;
const LOCK = 18002640;
const CLAN = /^[A-Z0-9]{2,12}$/, CODE = /^[a-z0-9][a-z0-9._-]{1,63}$/;
const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
type PointState = 'NEUTRAL' | 'CONTESTED' | 'CAPTURING' | 'CONTROLLED' | 'PROTECTED' | 'AVAILABLE';
type HeldState = 'NEUTRAL' | 'CONTROLLED' | 'PROTECTED' | 'AVAILABLE';
export type DeclineReason = 'NO_WORLD' | 'UNKNOWN_POINT' | 'NOT_CAPTURABLE' | 'NOT_MEMBER' | 'ROLE' | 'OUT_OF_WINDOW' | 'RUNNING' | 'PROTECTED'
  | 'OWN_POINT' | 'CLAN_LIMIT' | 'COOLDOWN' | 'INFLUENCE' | 'TOO_FEW_PLAYERS' | 'NOT_NEUTRAL';
export class CaptureError extends Error {}
const reject = (message: string): never => { throw new CaptureError(message); };

const addSeconds = (at: Date, seconds: number) => new Date(at.getTime() + seconds * 1000);
/** Deterministic UUID derived from another id (idempotent reward ledger rows). */
export function derivedId(base: string, label: string): string {
  const bytes = createHash('sha256').update(`${base}:${label}`).digest().subarray(0, 16);
  bytes[6] = (bytes[6]! & 15) | 80; bytes[8] = (bytes[8]! & 63) | 128;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

interface World { world_id: string; rules: CaptureRules }
async function activeWorld(tx: Tx): Promise<World | null> {
  const row = (await tx.$queryRaw<Array<{ world_id: string; capture_rules: unknown }>>`SELECT world_id,capture_rules FROM world_catalog WHERE active LIMIT 1`)[0];
  return row && row.capture_rules ? { world_id: row.world_id, rules: parseRules(row.capture_rules) } : null;
}
const lock = (tx: Tx) => tx.$executeRaw`SELECT pg_advisory_xact_lock(${LOCK})`;

interface PointRow {
  id: string; code: string; name: string; type: string; capturable: boolean; owner_clan_id: string | null; capture_state: PointState;
  protection_until: Date | null; lease_until: Date | null; resource_tier: number | null; capture_cooldown_seconds: number | null;
}
async function pointByCode(tx: Tx, worldId: string, code: string): Promise<PointRow | null> {
  return (await tx.$queryRaw<PointRow[]>`SELECT p.id,p.code,p.name,p.type,p.capturable,p.owner_clan_id,p.capture_state,p.protection_until,p.lease_until,
      s.resource_tier,s.capture_cooldown_seconds
    FROM strategic_points p LEFT JOIN resource_sites s ON s.point_id=p.id
    WHERE p.world_id=${worldId} AND p.code=${code} AND p.enabled FOR UPDATE OF p`)[0] ?? null;
}
async function clanIdByCode(tx: Tx, code: string): Promise<string | null> {
  if (!CLAN.test(code)) return null;
  return (await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE code_key=${code} AND enabled AND archived_at IS NULL`)[0]?.id ?? null;
}
async function history(tx: Tx, pointId: string, event: string, at: Date, extra: { clanId?: string | null; captureEventId?: string | null;
  actorUserId?: string | null; actorKind?: 'USER' | 'SERVICE' | 'OPERATOR' | 'SYSTEM'; payload?: Record<string, unknown> } = {}) {
  await tx.$executeRaw`INSERT INTO resource_site_history(point_id,at,event,clan_id,capture_event_id,actor_user_id,actor_kind,payload)
    VALUES (${pointId}::uuid,${at},${event},${extra.clanId ?? null}::uuid,${extra.captureEventId ?? null}::uuid,${extra.actorUserId ?? null}::uuid,
      ${extra.actorKind ?? 'SYSTEM'},${JSON.stringify(extra.payload ?? {})}::jsonb)`;
}
/** Credits a clan once per ledger id through the same tables as wars and boss kills. */
async function award(tx: Tx, input: { ledgerId: string; serverId: string | null; clanId: string; kind: 'POINT_CAPTURE' | 'POINT_DEFENCE'; reward: Reward; at: Date; payload: Record<string, unknown> }) {
  if (!input.serverId || (input.reward.xp === 0 && input.reward.reputation === 0)) return false;
  const inserted = await tx.$executeRaw`INSERT INTO clan_gameplay_events(event_id,server_id,clan_id,kind,xp_delta,reputation_delta,payload,occurred_at)
    VALUES (${input.ledgerId}::uuid,${input.serverId}::uuid,${input.clanId}::uuid,${input.kind},${input.reward.xp},${input.reward.reputation},
      ${JSON.stringify(input.payload)}::jsonb,${input.at}) ON CONFLICT (event_id) DO NOTHING`;
  if (inserted !== 1) return false;
  await tx.$executeRaw`INSERT INTO clan_progression(clan_id,xp,level,reputation,statistics,version)
    VALUES (${input.clanId}::uuid,${input.reward.xp},LEAST(100,1+floor(sqrt(${input.reward.xp}::numeric/100))::int),${input.reward.reputation},
      jsonb_build_object(${input.kind}::text,1),1)
    ON CONFLICT(clan_id) DO UPDATE SET xp=clan_progression.xp+${input.reward.xp},
      reputation=LEAST(1000000,clan_progression.reputation+${input.reward.reputation}),
      level=LEAST(100,1+floor(sqrt((clan_progression.xp+${input.reward.xp})::numeric/100))::int),
      statistics=clan_progression.statistics || jsonb_build_object(${input.kind}::text,COALESCE((clan_progression.statistics->>${input.kind})::bigint,0)+1),
      version=clan_progression.version+1,updated_at=now()`;
  await refreshExtraClanAchievements(tx,input.clanId);
  return true;
}

interface EventRow {
  id: string; point_id: string; declared_by_clan: string; state: string; prep_start: Date; active_start: Date; active_end: Date;
  prior_state: HeldState | null; prior_owner: string | null; server_id: string | null; snapshot_at: Date | null; winner_clan: string | null; outcome: string | null;
}
const EVENT_COLUMNS = 'id,point_id,declared_by_clan,state,prep_start,active_start,active_end,prior_state,prior_owner,server_id,snapshot_at,winner_clan,outcome';
async function eventById(tx: Tx, id: string): Promise<EventRow | null> {
  if (!UUID.test(id)) return null;
  return (await tx.$queryRawUnsafe<EventRow[]>(`SELECT ${EVENT_COLUMNS} FROM capture_events WHERE id=$1::uuid FOR UPDATE`, id))[0] ?? null;
}

/** Ends a running event without a result and puts the point back into the state it had before. */
async function closeWithoutResult(tx: Tx, event: EventRow, now: Date, outcome: 'VOIDED' | 'CANCELLED', reason: string,
  actor: { userId?: string | null; kind: 'USER' | 'SERVICE' | 'OPERATOR' | 'SYSTEM' }) {
  await tx.$executeRaw`UPDATE capture_events SET state=${outcome},outcome=${outcome},finished_at=${now},end_reason=${reason.slice(0, 64)},version=version+1
    WHERE id=${event.id}::uuid`;
  await tx.$executeRaw`UPDATE strategic_points SET capture_state=${event.prior_state ?? 'NEUTRAL'},capture_end=${now},updated_at=${now}
    WHERE id=${event.point_id}::uuid AND capture_state IN ('CONTESTED','CAPTURING')`;
  await history(tx, event.point_id, outcome === 'VOIDED' ? 'VOIDED' : 'ADMIN_CANCEL', now,
    { clanId: event.declared_by_clan, captureEventId: event.id, actorUserId: actor.userId ?? null, actorKind: actor.kind, payload: { reason } });
}

/**
 * Clock tick: phases, stale battles, protection, lease and neutralisation. Idempotent; called by every entry point.
 * `finished` lists events whose result the game server already holds (waiting in its outbox): they are never voided
 * as stale, so an API outage longer than the grace cannot throw a decided battle away.
 */
export async function advanceCapture(tx: Tx, now: Date, worldArg?: World | null, finished: string[] = []): Promise<number> {
  const world = worldArg === undefined ? await activeWorld(tx) : worldArg;
  if (!world) return 0;
  const { timing } = world.rules;
  let changes = 0;
  const starting = await tx.$queryRawUnsafe<EventRow[]>(`SELECT e.${EVENT_COLUMNS.split(',').join(',e.')} FROM capture_events e JOIN strategic_points p ON p.id=e.point_id
    WHERE p.world_id=$1 AND e.state='PREPARING' AND e.active_start<=$2 ORDER BY e.active_start FOR UPDATE OF e`, world.world_id, now);
  for (const event of starting) {
    await tx.$executeRaw`UPDATE capture_events SET state='ACTIVE',version=version+1 WHERE id=${event.id}::uuid`;
    await tx.$executeRaw`UPDATE strategic_points SET capture_state='CAPTURING',updated_at=${now} WHERE id=${event.point_id}::uuid`;
    await history(tx, event.point_id, 'ACTIVE', now, { clanId: event.declared_by_clan, captureEventId: event.id });
    changes++;
  }
  // A battle without fresh snapshots (server restart or API outage longer than the grace) is voided without consequences.
  const graceStart = addSeconds(now, -timing.resume_grace_seconds), lastEnd = addSeconds(now, -(timing.overtime_seconds + timing.resume_grace_seconds));
  const stale = await tx.$queryRawUnsafe<EventRow[]>(`SELECT e.${EVENT_COLUMNS.split(',').join(',e.')} FROM capture_events e JOIN strategic_points p ON p.id=e.point_id
    WHERE p.world_id=$1 AND e.state IN ('ACTIVE','OVERTIME') AND (COALESCE(e.snapshot_at,e.active_start)<$2 OR e.active_end<$3)
      AND NOT (e.id::text = ANY($4::text[])) FOR UPDATE OF e`,
    world.world_id, graceStart, lastEnd, finished.filter(id => UUID.test(id)));
  for (const event of stale) { await closeWithoutResult(tx, event, now, 'VOIDED', 'NO_SNAPSHOT', { kind: 'SYSTEM' }); changes++; }

  const timers = await tx.$queryRaw<Array<{ id: string; owner_clan_id: string; capture_state: PointState; protection_until: Date | null; lease_until: Date | null }>>`
    SELECT id,owner_clan_id,capture_state,protection_until,lease_until FROM strategic_points
    WHERE world_id=${world.world_id} AND owner_clan_id IS NOT NULL AND (
      (capture_state='PROTECTED' AND protection_until<=${now}) OR (capture_state='CONTROLLED' AND lease_until<=${now})
      OR (capture_state='AVAILABLE' AND lease_until<=${addSeconds(now, -timing.available_seconds)})) FOR UPDATE`;
  for (const point of timers) {
    let state = point.capture_state;
    if (state === 'PROTECTED' && point.protection_until! <= now) {
      state = 'CONTROLLED';
      await history(tx, point.id, 'PROTECTION_ENDED', point.protection_until!, { clanId: point.owner_clan_id });
    }
    if (state === 'CONTROLLED' && point.lease_until! <= now) {
      state = 'AVAILABLE';
      await history(tx, point.id, 'LEASE_EXPIRED', point.lease_until!, { clanId: point.owner_clan_id });
    }
    if (state === 'AVAILABLE' && addSeconds(point.lease_until!, timing.available_seconds) <= now) {
      await tx.$executeRaw`UPDATE resource_site_ownership SET released_at=${now},release_reason='LEASE_EXPIRED' WHERE point_id=${point.id}::uuid AND released_at IS NULL`;
      await tx.$executeRaw`UPDATE strategic_points SET owner_clan_id=NULL,capture_state='NEUTRAL',protection_until=NULL,lease_until=NULL,updated_at=${now} WHERE id=${point.id}::uuid`;
      await history(tx, point.id, 'NEUTRALIZED', now, { clanId: point.owner_clan_id });
    } else await tx.$executeRaw`UPDATE strategic_points SET capture_state=${state},updated_at=${now} WHERE id=${point.id}::uuid`;
    changes++;
  }
  // A new site is due at once: the first refresh seeds the pit.
  changes += await tx.$executeRaw`UPDATE resource_sites s SET next_refresh=${now} FROM strategic_points p
    WHERE p.id=s.point_id AND p.world_id=${world.world_id} AND p.enabled AND s.next_refresh IS NULL`;
  return changes;
}

export interface DeclareInput { serverId: string; pointCode: string; actorUserId: string; clanCode: string; playersInZone: number; defendersOnline: number; now: Date; autoNeutral?: boolean }
export type DeclareResult = { accepted: false; reason: DeclineReason; retry_at?: string | undefined; event?: undefined }
  | { accepted: true; reason?: undefined; event: { id: string; point_code: string; prep_start: string; active_start: string; active_end: string; renewal: boolean; preparation_seconds: number } };

export async function declareCapture(tx: Tx, input: DeclareInput): Promise<DeclareResult> {
  if (!CODE.test(input.pointCode) || !UUID.test(input.actorUserId) || !CLAN.test(input.clanCode)
    || !Number.isInteger(input.playersInZone) || input.playersInZone < 0 || input.playersInZone > 500
    || !Number.isInteger(input.defendersOnline) || input.defendersOnline < 0 || input.defendersOnline > 500) reject('Invalid declaration');
  await lock(tx);
  const world = await activeWorld(tx);
  if (!world) return { accepted: false, reason: 'NO_WORLD' };
  await advanceCapture(tx, input.now, world);
  const { rules } = world, now = input.now;
  const point = await pointByCode(tx, world.world_id, input.pointCode);
  if (!point) return { accepted: false, reason: 'UNKNOWN_POINT' };
  if (!point.capturable) return { accepted: false, reason: 'NOT_CAPTURABLE' };
  // Checked under the capture lock: a stale game-server view must never auto-attack a new owner.
  if (input.autoNeutral && (point.owner_clan_id !== null || point.capture_state !== 'NEUTRAL')) return { accepted: false, reason: 'NOT_NEUTRAL' };
  const member = (await tx.$queryRaw<Array<{ clan_id: string; code_key: string }>>`SELECT m.clan_id,c.code_key FROM clan_members m
      JOIN users u ON u.id=m.user_id AND u.state='ACTIVE' JOIN clans c ON c.id=m.clan_id AND c.enabled AND c.archived_at IS NULL
    WHERE m.user_id=${input.actorUserId}::uuid AND m.ended_at IS NULL`)[0];
  if (!member || member.code_key !== input.clanCode) return { accepted: false, reason: 'NOT_MEMBER' };
  if (!rules.declare.roles.includes('MEMBER')) {
    const manager = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clan_manager_assignments
      WHERE user_id=${input.actorUserId}::uuid AND clan_id=${member.clan_id}::uuid AND ended_at IS NULL`;
    if (!manager.length) return { accepted: false, reason: 'ROLE' };
  }
  if (!inWindow(rules, now)) return { accepted: false, reason: 'OUT_OF_WINDOW' };
  if (point.capture_state === 'CONTESTED' || point.capture_state === 'CAPTURING') return { accepted: false, reason: 'RUNNING' };
  if (point.capture_state === 'PROTECTED') return { accepted: false, reason: 'PROTECTED', retry_at: point.protection_until?.toISOString() };
  const renewal = point.owner_clan_id === member.clan_id;
  if (renewal && point.capture_state !== 'AVAILABLE') return { accepted: false, reason: 'OWN_POINT' };
  const running = await tx.$queryRaw<Array<{ count: bigint }>>`SELECT count(*) AS count FROM capture_events
    WHERE declared_by_clan=${member.clan_id}::uuid AND state IN ('PREPARING','ACTIVE','OVERTIME')`;
  if (Number(running[0]!.count) >= rules.declare.max_active_per_clan) return { accepted: false, reason: 'CLAN_LIMIT' };
  const cooldown = point.capture_cooldown_seconds ?? rules.declare.failed_cooldown_seconds;
  const failed = (await tx.$queryRaw<Array<{ finished_at: Date }>>`SELECT finished_at FROM capture_events
    WHERE point_id=${point.id}::uuid AND declared_by_clan=${member.clan_id}::uuid AND outcome IN ('DEFENDED','NO_WINNER','CAPTURED','RENEWED')
      AND winner_clan IS DISTINCT FROM declared_by_clan AND finished_at>${addSeconds(now, -cooldown)} ORDER BY finished_at DESC LIMIT 1`)[0];
  if (failed) return { accepted: false, reason: 'COOLDOWN', retry_at: addSeconds(failed.finished_at, cooldown).toISOString() };
  if (!renewal) {
    // Held points plus the clan's other running declarations must fit the clan's influence capacity.
    const holdings = await tx.$queryRaw<Array<{ tier: number | null }>>`SELECT s.resource_tier AS tier FROM strategic_points p
        LEFT JOIN resource_sites s ON s.point_id=p.id
      WHERE p.world_id=${world.world_id} AND p.enabled AND p.id<>${point.id}::uuid AND (p.owner_clan_id=${member.clan_id}::uuid
        OR EXISTS (SELECT 1 FROM capture_events e WHERE e.point_id=p.id AND e.declared_by_clan=${member.clan_id}::uuid AND e.state IN ('PREPARING','ACTIVE','OVERTIME')))`;
    const level = (await tx.$queryRaw<Array<{ level: number }>>`SELECT level FROM clan_progression WHERE clan_id=${member.clan_id}::uuid`)[0]?.level ?? 1;
    const used = holdings.reduce((sum, row) => sum + influenceWeight(rules, row.tier === null ? null : Number(row.tier)), 0);
    if (used + influenceWeight(rules, point.resource_tier === null ? null : Number(point.resource_tier)) > influenceCapacity(rules, level))
      return { accepted: false, reason: 'INFLUENCE' };
  }
  const needed = point.resource_tier !== null && Number(point.resource_tier) === 1 ? rules.declare.min_players_t1 : rules.declare.min_players;
  if (input.playersInZone < needed) return { accepted: false, reason: 'TOO_FEW_PLAYERS' };
  const preparation = renewal ? rules.timing.renewal_preparation_seconds
    : point.owner_clan_id && input.defendersOnline < rules.timing.min_defenders_online ? rules.timing.preparation_weak_defence_seconds : rules.timing.preparation_seconds;
  const id = randomUUID(), activeStart = addSeconds(now, preparation), activeEnd = addSeconds(activeStart, rules.timing.battle_seconds);
  await tx.$executeRaw`INSERT INTO capture_events(id,point_id,declared_by_clan,declared_by_user,state,prep_start,active_start,active_end,server_id,prior_state,prior_owner,snapshot)
    VALUES (${id}::uuid,${point.id}::uuid,${member.clan_id}::uuid,${input.actorUserId}::uuid,'PREPARING',${now},${activeStart},${activeEnd},
      ${input.serverId}::uuid,${point.capture_state},${point.owner_clan_id}::uuid,'{}'::jsonb)`;
  await tx.$executeRaw`UPDATE strategic_points SET capture_state='CONTESTED',capture_start=${activeStart},capture_end=${activeEnd},updated_at=${now} WHERE id=${point.id}::uuid`;
  await history(tx, point.id, 'DECLARED', now, { clanId: member.clan_id, captureEventId: id, actorUserId: input.actorUserId, actorKind: 'USER',
    payload: { renewal, preparation_seconds: preparation, players_in_zone: input.playersInZone } });
  return { accepted: true, event: { id, point_code: point.code, prep_start: now.toISOString(), active_start: activeStart.toISOString(),
    active_end: activeEnd.toISOString(), renewal, preparation_seconds: preparation } };
}

export interface ScoreInput { clan_code: string; progress: number; kills: number; deaths: number; presence_seconds: number; peak_players: number }
async function resolveScores(tx: Tx, scores: unknown): Promise<Array<ScoreInput & { clan_id: string }>> {
  if (!Array.isArray(scores) || scores.length > 64) reject('Invalid scores');
  const seen = new Set<string>(), result: Array<ScoreInput & { clan_id: string }> = [];
  for (const raw of scores as unknown[]) {
    const row = raw as ScoreInput;
    if (!row || typeof row.clan_code !== 'string' || seen.has(row.clan_code) || typeof row.progress !== 'number' || !(row.progress >= 0 && row.progress <= 100)
      || ![row.kills, row.deaths, row.presence_seconds, row.peak_players].every(n => Number.isInteger(n) && n >= 0 && n <= 1_000_000)) reject('Invalid score row');
    const clanId = await clanIdByCode(tx, row.clan_code) ?? reject('Unknown clan in scores');
    seen.add(row.clan_code);
    result.push({ ...row, progress: Math.round(row.progress * 1000) / 1000, clan_id: clanId });
  }
  return result;
}
async function storeScores(tx: Tx, eventId: string, scores: Array<ScoreInput & { clan_id: string }>) {
  for (const s of scores) await tx.$executeRaw`INSERT INTO capture_event_scores(event_id,clan_id,progress,kills,deaths,presence_seconds,peak_players)
      VALUES (${eventId}::uuid,${s.clan_id}::uuid,${s.progress},${s.kills},${s.deaths},${s.presence_seconds},${s.peak_players})
    ON CONFLICT (event_id,clan_id) DO UPDATE SET progress=EXCLUDED.progress,kills=EXCLUDED.kills,deaths=EXCLUDED.deaths,
      presence_seconds=EXCLUDED.presence_seconds,peak_players=GREATEST(capture_event_scores.peak_players,EXCLUDED.peak_players)`;
}

/** Periodic battle snapshot from the game server; it keeps the event alive and lets a restart resume. */
export async function snapshotCapture(tx: Tx, input: { serverId: string; eventId: string; phase: 'ACTIVE' | 'OVERTIME'; scores: unknown; now: Date; killLimits?: unknown }) {
  if (input.phase !== 'ACTIVE' && input.phase !== 'OVERTIME') reject('Invalid phase');
  await lock(tx);
  const world=await activeWorld(tx)??reject('No active world');
  await advanceCapture(tx, input.now,world);
  const event = await eventById(tx, input.eventId) ?? reject('Unknown capture event');
  if (event.server_id !== input.serverId) reject('Capture event belongs to another server');
  if (event.state !== 'ACTIVE' && event.state !== 'OVERTIME') return { accepted: true, event_id: event.id, state: event.state };
  if (input.phase === 'OVERTIME' && input.now < event.active_end) reject('Overtime before the battle end');
  const scores = await resolveScores(tx, input.scores);
  await storeScores(tx, event.id, scores);
  const killLimits=input.killLimits??[];
  if(!Array.isArray(killLimits)||killLimits.length>4096)reject('Invalid kill limits');
  const seenVictims=new Set<string>();
  for(const row of killLimits as unknown[]) {
    if(!Array.isArray(row)||row.length!==3||typeof row[0]!=='string'||!UUID.test(row[0])||seenVictims.has(row[0])
      ||!Number.isInteger(row[1])||row[1]<1||row[1]>world.rules.score.kill_max_per_victim||!Number.isSafeInteger(row[2])||row[2]<0)reject('Invalid kill limits');
    seenVictims.add((row as [string,number,number])[0]);
  }
  const snapshot = JSON.stringify({ phase: input.phase, scores: scores.map(({ clan_id: _id, ...rest }) => rest),kill_limits:killLimits });
  await tx.$executeRaw`UPDATE capture_events SET snapshot=${snapshot}::jsonb,snapshot_at=${input.now},state=${input.phase},version=version+1 WHERE id=${event.id}::uuid`;
  return { accepted: true, event_id: event.id, state: input.phase };
}

export interface FinishInput { serverId: string; eventId: string; winnerCode: string | null; scores: unknown; now: Date }
export async function finishCapture(tx: Tx, input: FinishInput) {
  await lock(tx);
  const world = await activeWorld(tx) ?? reject('No active world');
  const event = await eventById(tx, input.eventId) ?? reject('Unknown capture event');
  if (event.server_id !== input.serverId) reject('Capture event belongs to another server');
  // A repeated delivery (outbox retry, concurrent duplicate) is acknowledged without a second effect.
  if (['FINISHED', 'VOIDED', 'CANCELLED'].includes(event.state)) return { accepted: true, duplicate: true, event_id: event.id, outcome: event.outcome };
  // The result itself proves the server is alive: this event is not voided as stale here.
  await advanceCapture(tx, input.now, world, [event.id]);
  const fresh = (await eventById(tx, input.eventId))!;
  if (fresh.state === 'VOIDED') return { accepted: true, duplicate: true, event_id: event.id, outcome: 'VOIDED' };
  if (fresh.state !== 'ACTIVE' && fresh.state !== 'OVERTIME') reject('Capture battle has not started');
  const { rules } = world, now = input.now;
  if (now > addSeconds(fresh.active_end, rules.timing.overtime_seconds + rules.timing.resume_grace_seconds)) reject('Capture battle ended too long ago');
  const scores = await resolveScores(tx, input.scores);
  const winner = input.winnerCode === null ? null : scores.find(s => s.clan_code === input.winnerCode) ?? reject('Winner has no score');
  if (winner) {
    const others = scores.filter(s => s !== winner).map(s => s.progress), second = others.length ? Math.max(...others) : 0;
    // The engine decides on raw doubles and sends values rounded to 0.001: allow that rounding.
    const eps = 0.002;
    const decisive = winner.progress >= 100 - eps || (winner.progress >= rules.score.win_progress - eps && winner.progress - second > rules.score.win_margin - eps);
    if (!decisive || winner.progress < second) reject('Winner does not satisfy the capture rules');
  }
  await storeScores(tx, fresh.id, scores);
  const point = (await tx.$queryRaw<Array<{ id: string; code: string; owner_clan_id: string | null; resource_tier: number | null }>>`
    SELECT p.id,p.code,p.owner_clan_id,s.resource_tier FROM strategic_points p LEFT JOIN resource_sites s ON s.point_id=p.id
    WHERE p.id=${fresh.point_id}::uuid FOR UPDATE OF p`)[0]!;
  const tier = point.resource_tier === null ? null : Number(point.resource_tier);
  const owner = point.owner_clan_id, prior = fresh.prior_state ?? 'NEUTRAL';
  let outcome: 'CAPTURED' | 'RENEWED' | 'DEFENDED' | 'NO_WINNER';
  if (winner && winner.clan_id !== owner) {
    outcome = 'CAPTURED';
    await tx.$executeRaw`UPDATE resource_site_ownership SET released_at=${now},release_reason='CAPTURED' WHERE point_id=${point.id}::uuid AND released_at IS NULL`;
    await tx.$executeRaw`INSERT INTO resource_site_ownership(point_id,clan_id,acquired_at,lease_until) VALUES (${point.id}::uuid,${winner.clan_id}::uuid,${now},${addSeconds(now, rules.timing.lease_seconds)})`;
    await tx.$executeRaw`UPDATE strategic_points SET owner_clan_id=${winner.clan_id}::uuid,capture_state='PROTECTED',protection_until=${addSeconds(now, rules.timing.protection_seconds)},
      lease_until=${addSeconds(now, rules.timing.lease_seconds)},capture_end=${now},updated_at=${now} WHERE id=${point.id}::uuid`;
    await award(tx, { ledgerId: fresh.id, serverId: fresh.server_id, clanId: winner.clan_id, kind: 'POINT_CAPTURE', reward: captureReward(rules, tier), at: now,
      payload: { point_code: point.code, capture_event_id: fresh.id, tier } });
  } else if (winner && prior === 'AVAILABLE') {
    outcome = 'RENEWED';
    await tx.$executeRaw`UPDATE resource_site_ownership SET lease_until=${addSeconds(now, rules.timing.lease_seconds)} WHERE point_id=${point.id}::uuid AND released_at IS NULL`;
    await tx.$executeRaw`UPDATE strategic_points SET capture_state='PROTECTED',protection_until=${addSeconds(now, rules.timing.protection_seconds)},
      lease_until=${addSeconds(now, rules.timing.lease_seconds)},capture_end=${now},updated_at=${now} WHERE id=${point.id}::uuid`;
  } else {
    outcome = owner ? 'DEFENDED' : 'NO_WINNER';
    await tx.$executeRaw`UPDATE strategic_points SET capture_state=${prior},capture_end=${now},updated_at=${now} WHERE id=${point.id}::uuid`;
    const defender = owner ? scores.find(s => s.clan_id === owner) : undefined;
    if (owner && fresh.declared_by_clan !== owner && defender && defender.presence_seconds > 0)
      await award(tx, { ledgerId: derivedId(fresh.id, 'defended'), serverId: fresh.server_id, clanId: owner, kind: 'POINT_DEFENCE', reward: rules.rewards.defended, at: now,
        payload: { point_code: point.code, capture_event_id: fresh.id } });
  }
  await tx.$executeRaw`UPDATE capture_events SET state='FINISHED',outcome=${outcome},winner_clan=${winner?.clan_id ?? null}::uuid,finished_at=${now},version=version+1
    WHERE id=${fresh.id}::uuid`;
  await history(tx, point.id, outcome, now, { clanId: winner?.clan_id ?? owner, captureEventId: fresh.id, actorKind: 'SERVICE',
    payload: { scores: scores.map(s => ({ clan: s.clan_code, progress: s.progress, kills: s.kills })) } });
  // Timers that already ran out while the battle was going (e.g. a lease) apply immediately.
  await advanceCapture(tx, now, world, [fresh.id]);
  return { accepted: true, duplicate: false, event_id: fresh.id, outcome, winner_clan_code: winner?.clan_code ?? null };
}

/** The game server gives up a battle (restart longer than the grace, unrecoverable state). */
export async function voidCapture(tx: Tx, input: { serverId: string; eventId: string; reason: string; now: Date }) {
  if (!/^[A-Z_]{2,32}$/.test(input.reason)) reject('Invalid reason');
  await lock(tx);
  const event = await eventById(tx, input.eventId) ?? reject('Unknown capture event');
  if (event.server_id !== input.serverId) reject('Capture event belongs to another server');
  if (!['PREPARING', 'ACTIVE', 'OVERTIME'].includes(event.state)) return { accepted: true, duplicate: true, event_id: event.id };
  await closeWithoutResult(tx, event, input.now, 'VOIDED', input.reason, { kind: 'SERVICE' });
  return { accepted: true, duplicate: false, event_id: event.id };
}

// ---------------------------------------------------------------------------------------------
// Administration. The project OWNER acts from the game console; every change is audited.
// ---------------------------------------------------------------------------------------------
async function requireOwner(tx: Tx, userId: string) {
  if (!UUID.test(userId)) reject('Invalid actor');
  const owner = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM users WHERE id=${userId}::uuid AND system_role='OWNER' AND state='ACTIVE'`;
  if (!owner.length) reject('Owner required');
}
async function audit(tx: Tx, actorUserId: string, action: string, pointId: string, before: unknown, after: unknown, reason: string) {
  await tx.$executeRaw`INSERT INTO audit_logs(actor_user_id,actor_kind,action,target_type,target_id,before_redacted,after_redacted,reason,request_id)
    VALUES (${actorUserId}::uuid,'USER',${action},'strategic_point',${pointId}::uuid,${JSON.stringify(before)}::jsonb,${JSON.stringify(after)}::jsonb,
      ${reason.slice(0, 1000) || action},${randomUUID()}::uuid)`;
}
async function runningEvent(tx: Tx, pointId: string) {
  return (await tx.$queryRawUnsafe<EventRow[]>(`SELECT ${EVENT_COLUMNS} FROM capture_events WHERE point_id=$1::uuid AND state IN ('PREPARING','ACTIVE','OVERTIME') FOR UPDATE`, pointId))[0] ?? null;
}
const pointSummary = async (tx: Tx, id: string) => (await tx.$queryRaw<Array<Record<string, unknown>>>`SELECT p.code,c.code_key AS owner,p.capture_state,p.protection_until,p.lease_until
  FROM strategic_points p LEFT JOIN clans c ON c.id=p.owner_clan_id WHERE p.id=${id}::uuid`)[0] ?? {};

export async function adminCapture(tx: Tx, input: { action: 'CANCEL' | 'RESET' | 'TRANSFER' | 'REFRESH' | 'RELEASE'; actorUserId: string; pointCode: string;
  clanCode?: string | undefined; reason?: string | undefined; now: Date }) {
  const now = input.now, reason = (input.reason ?? '').trim().slice(0, 200) || input.action;
  if (!CODE.test(input.pointCode)) reject('Invalid point');
  await lock(tx);
  const world = await activeWorld(tx) ?? reject('No active world');
  await advanceCapture(tx, now, world);
  const point = await pointByCode(tx, world.world_id, input.pointCode) ?? reject('Unknown point');
  let actorClan: string | null = null;
  if (input.action === 'RELEASE') {
    // A clan may drop its own point (to free influence): its manager, or the project owner.
    const member = (await tx.$queryRaw<Array<{ clan_id: string }>>`SELECT a.clan_id FROM clan_manager_assignments a JOIN users u ON u.id=a.user_id AND u.state='ACTIVE'
      WHERE a.user_id=${input.actorUserId}::uuid AND a.ended_at IS NULL AND a.clan_id=${point.owner_clan_id}::uuid`)[0];
    if (member) actorClan = member.clan_id; else await requireOwner(tx, input.actorUserId);
  } else await requireOwner(tx, input.actorUserId);
  const before = await pointSummary(tx, point.id);
  const running = await runningEvent(tx, point.id);
  if (input.action === 'CANCEL') {
    if (!running) return { accepted: true, changed: false };
    await closeWithoutResult(tx, running, now, 'CANCELLED', reason, { userId: input.actorUserId, kind: 'USER' });
  } else if (input.action === 'REFRESH') {
    const changed = await tx.$executeRaw`UPDATE resource_sites SET next_refresh=${now} WHERE point_id=${point.id}::uuid`;
    if (!changed) reject('Not a resource site');
    await history(tx, point.id, 'ADMIN_REFRESH', now, { actorUserId: input.actorUserId, actorKind: 'USER', payload: { reason } });
  } else {
    if (!point.capturable) reject('Point is not capturable');
    if (input.action === 'RELEASE' && !point.owner_clan_id) return { accepted: true, changed: false };
    if (running) {
      if (input.action === 'RELEASE') reject('Point is under capture');
      await closeWithoutResult(tx, running, now, 'CANCELLED', reason, { userId: input.actorUserId, kind: 'USER' });
    }
    const target = input.action === 'TRANSFER' ? await clanIdByCode(tx, input.clanCode ?? '') ?? reject('Unknown clan') : null;
    await tx.$executeRaw`UPDATE resource_site_ownership SET released_at=${now},release_reason=${input.action === 'RELEASE' ? 'RELEASED' : `ADMIN_${input.action}`}
      WHERE point_id=${point.id}::uuid AND released_at IS NULL`;
    if (target) {
      const lease = addSeconds(now, world.rules.timing.lease_seconds);
      await tx.$executeRaw`INSERT INTO resource_site_ownership(point_id,clan_id,acquired_at,lease_until) VALUES (${point.id}::uuid,${target}::uuid,${now},${lease})`;
      await tx.$executeRaw`UPDATE strategic_points SET owner_clan_id=${target}::uuid,capture_state='CONTROLLED',protection_until=NULL,lease_until=${lease},updated_at=${now} WHERE id=${point.id}::uuid`;
    } else await tx.$executeRaw`UPDATE strategic_points SET owner_clan_id=NULL,capture_state='NEUTRAL',protection_until=NULL,lease_until=NULL,updated_at=${now} WHERE id=${point.id}::uuid`;
    await history(tx, point.id, input.action === 'RELEASE' ? 'RELEASED' : input.action === 'TRANSFER' ? 'ADMIN_TRANSFER' : 'ADMIN_RESET', now,
      { clanId: target ?? point.owner_clan_id, actorUserId: input.actorUserId, actorKind: 'USER', payload: { reason } });
  }
  await audit(tx, input.actorUserId, `CAPTURE_${input.action}`, point.id, before, { ...await pointSummary(tx, point.id), by_clan_manager: actorClan !== null }, reason);
  return { accepted: true, changed: true };
}

// ---------------------------------------------------------------------------------------------
// Game server sync and resource refreshes
// ---------------------------------------------------------------------------------------------
/** Everything the game server needs for the next seconds: rules, capturable points, running battles, due refreshes. */
export async function syncCapture(tx: Tx, now: Date, finished: string[] = []) {
  await lock(tx);
  const world = await activeWorld(tx);
  if (!world) return { now: now.toISOString(), world_id: null, rules: null, points: [], events: [], refreshes: [] };
  await advanceCapture(tx, now, world, finished.slice(0, 64));
  const rows = await tx.$queryRaw<Array<{ id: string; code: string; name: string; type: string; x: number; y: number | null; z: number; radius: number; owner: string | null;
    capture_state: string; protection_until: Date | null; lease_until: Date | null; family: string | null; tier: number | null; kind: string | null;
    next_refresh: Date | null; rank: bigint | null; yield: Record<string, unknown> }>>`
    SELECT p.id,p.code,p.name,p.type,p.x,p.y,p.z,p.radius,c.code_key AS owner,p.capture_state,p.protection_until,p.lease_until,
      s.resource_type AS family,s.resource_tier AS tier,s.kind,s.next_refresh,s.yield,o.rank
    FROM strategic_points p LEFT JOIN clans c ON c.id=p.owner_clan_id LEFT JOIN resource_sites s ON s.point_id=p.id
      LEFT JOIN (SELECT point_id,row_number() OVER (PARTITION BY clan_id ORDER BY acquired_at,point_id)-1 AS rank FROM resource_site_ownership WHERE released_at IS NULL) o ON o.point_id=p.id
    WHERE p.world_id=${world.world_id} AND p.enabled AND (p.capturable OR s.point_id IS NOT NULL) ORDER BY p.code`;
  const iso = (d: Date | null) => d ? d.toISOString() : null;
  const multiplier = (row: typeof rows[number]) => row.owner === null ? 1
    : Math.round(rankMultiplier(world.rules, Number(row.rank ?? 0), row.capture_state === 'AVAILABLE') * 1000) / 1000;
  const events = await tx.$queryRaw<Array<{ id: string; code: string; declared_by: string; state: string; prep_start: Date; active_start: Date; active_end: Date;
    prior_state: string | null; snapshot: Record<string, unknown>; snapshot_at: Date | null }>>`
    SELECT e.id,p.code,c.code_key AS declared_by,e.state,e.prep_start,e.active_start,e.active_end,e.prior_state,e.snapshot,e.snapshot_at
    FROM capture_events e JOIN strategic_points p ON p.id=e.point_id JOIN clans c ON c.id=e.declared_by_clan
    WHERE p.world_id=${world.world_id} AND e.state IN ('PREPARING','ACTIVE','OVERTIME') ORDER BY e.prep_start`;
  return {
    now: now.toISOString(), world_id: world.world_id, rules: serverRules(world.rules),
    points: rows.map(row => ({ code: row.code, name: row.name, type: row.type, x: row.x, y: row.y, z: row.z, radius: row.radius, owner: row.owner,
      state: row.capture_state, protection_until: iso(row.protection_until), lease_until: iso(row.lease_until),
      resource: row.family ? { family: row.family, tier: Number(row.tier), kind: row.kind, next_refresh: iso(row.next_refresh), multiplier: multiplier(row), output: row.yield } : null })),
    events: events.map(e => ({ id: e.id, point_code: e.code, declared_by: e.declared_by, state: e.state, prep_start: e.prep_start.toISOString(),
      active_start: e.active_start.toISOString(), active_end: e.active_end.toISOString(), prior_state: e.prior_state,
      snapshot: e.snapshot_at ? { ...e.snapshot, at: e.snapshot_at.toISOString() } : null })),
    refreshes: rows.filter(row => row.family && row.next_refresh && row.next_refresh <= now)
      .map(row => ({ point_code: row.code, due_at: row.next_refresh!.toISOString(), multiplier: multiplier(row) })),
  };
}

/** The game server restored a pit or filled a cache; the next refresh is scheduled from now. Idempotent by due time. */
export async function refreshedSite(tx: Tx, input: { pointCode: string; dueAt: Date; restored: number; multiplier: number; now: Date }) {
  if (!CODE.test(input.pointCode) || !Number.isInteger(input.restored) || input.restored < 0 || input.restored > 100000
    || !(input.multiplier >= 0 && input.multiplier <= 2) || !Number.isFinite(input.dueAt.getTime())) reject('Invalid refresh');
  await lock(tx);
  const world = await activeWorld(tx) ?? reject('No active world');
  const site = (await tx.$queryRaw<Array<{ point_id: string; next_refresh: Date | null; interval: number; owner: string | null }>>`
    SELECT s.point_id,s.next_refresh,s.respawn_interval_seconds AS interval,p.owner_clan_id AS owner FROM resource_sites s JOIN strategic_points p ON p.id=s.point_id
    WHERE p.world_id=${world.world_id} AND p.code=${input.pointCode} AND p.enabled FOR UPDATE OF s`)[0] ?? reject('Unknown resource site');
  if (!site.next_refresh || site.next_refresh.getTime() !== input.dueAt.getTime()) return { accepted: true, duplicate: true, point_code: input.pointCode };
  const next = addSeconds(input.now, site.interval);
  await tx.$executeRaw`UPDATE resource_sites SET last_refresh=${input.now},next_refresh=${next} WHERE point_id=${site.point_id}::uuid`;
  await history(tx, site.point_id, 'REFRESHED', input.now, { clanId: site.owner, actorKind: 'SERVICE', payload: { restored: input.restored, multiplier: input.multiplier } });
  return { accepted: true, duplicate: false, point_code: input.pointCode, next_refresh: next.toISOString() };
}
