import {refreshExtraClanAchievements} from './clan-achievements.js';
import { randomUUID } from 'node:crypto';
import type { Prisma } from './generated/prisma/client.js';

// Clan codes are validated by format here; existence and enabled state come from the clans table.
const clanCode = /^[A-Z0-9]{2,12}$/;
const isCode = (value: string) => clanCode.test(value);
export async function scheduleWar(tx: Prisma.TransactionClient, input: {
  actorId: string; attacker: string; defender: string; preparationMinutes: number; raidMinutes: number;
}) {
  if (!isCode(input.attacker) || !isCode(input.defender) || input.attacker === input.defender
    || !Number.isInteger(input.preparationMinutes) || input.preparationMinutes < 10 || input.preparationMinutes > 1440
    || !Number.isInteger(input.raidMinutes) || input.raidMinutes < 10 || input.raidMinutes > 120) throw new Error('Invalid war schedule');
  const owner = await tx.user.findFirst({ where: { id: input.actorId, systemRole: 'OWNER', state: 'ACTIVE' } });
  if (!owner) throw new Error('Owner required');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  const clans = await tx.$queryRaw<Array<{ id: string; code_key: string }>>`SELECT id,code_key FROM clans
    WHERE code_key IN (${input.attacker},${input.defender}) AND enabled=true AND archived_at IS NULL`;
  const attacker = clans.find(clan => clan.code_key === input.attacker)?.id;
  const defender = clans.find(clan => clan.code_key === input.defender)?.id;
  if (!attacker || !defender) throw new Error('Clan unavailable');
  const busy = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clan_war_windows WHERE status<>'CANCELLED'
    AND COALESCE(cooldown_ends_at,active_ends_at)>now()
    AND (attacker_clan_id IN (${attacker}::uuid,${defender}::uuid) OR defender_clan_id IN (${attacker}::uuid,${defender}::uuid)) LIMIT 1`;
  if (busy.length) throw new Error('A clan has an active war or cooldown');
  const id = randomUUID();
  await tx.$executeRaw`INSERT INTO clan_war_windows (id,attacker_clan_id,defender_clan_id,status,preparation_starts_at,
    active_starts_at,active_ends_at,cooldown_ends_at,reason)
    VALUES (${id}::uuid,${attacker}::uuid,${defender}::uuid,'PREPARATION',now(),
      now()+${input.preparationMinutes}::int*interval '1 minute',
      now()+${input.preparationMinutes + input.raidMinutes}::int*interval '1 minute',
      now()+${input.preparationMinutes + input.raidMinutes}::int*interval '1 minute'+interval '24 hours',
      ${'Scheduled by owner ' + input.actorId})`;
  return { id, preparation_minutes: input.preparationMinutes, raid_minutes: input.raidMinutes, cooldown_hours: 24 };
}

export async function captureTerritory(tx: Prisma.TransactionClient, input: {
  eventId: string; serverId: string; warId: string; territory: string; attacker: string; defender: string; completedAt: Date;
}) {
  if (!isCode(input.territory)) throw new Error('Unknown territory');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  const wars = await tx.$queryRaw<Array<{ attacker_clan_id: string; defender_clan_id: string; active_starts_at: Date; active_ends_at: Date }>>`
    SELECT w.attacker_clan_id,w.defender_clan_id,w.active_starts_at,w.active_ends_at FROM clan_war_windows w JOIN clans a ON a.id=w.attacker_clan_id
    JOIN clans d ON d.id=w.defender_clan_id WHERE w.id=${input.warId}::uuid AND w.status<>'CANCELLED'
      AND a.code_key=${input.attacker} AND d.code_key=${input.defender}
      FOR UPDATE OF w`;
  const war = wars[0]; if (!war) throw new Error('No active war');
  const previous = await tx.$queryRaw<Array<{ event_id: string; server_id: string; clan_id: string; kind:string; payload:{war_window_id?:string;territory_code?:string} }>>`
    SELECT event_id,server_id,clan_id,kind,payload FROM clan_gameplay_events WHERE event_id=${input.eventId}::uuid
      OR kind='TERRITORY_CAPTURE' AND payload->>'war_window_id'=${input.warId} AND payload->>'territory_code'=${input.territory}`;
  if (previous.length) {
    if (previous.some(row => row.server_id !== input.serverId || row.clan_id !== war.attacker_clan_id || row.kind !== 'TERRITORY_CAPTURE' || row.payload.war_window_id !== input.warId || row.payload.territory_code !== input.territory)) throw new Error('Capture identity conflict');
    return { duplicate: true };
  }
  const now = new Date();
  if (!Number.isFinite(input.completedAt.getTime()) || input.completedAt > now || input.completedAt < war.active_starts_at || input.completedAt >= war.active_ends_at || now.getTime() > war.active_ends_at.getTime() + 600000) throw new Error('War window closed');
  const controllers = await tx.$queryRaw<Array<{ controller: string }>>`SELECT c.code_key AS controller FROM clan_territory_control t
    JOIN clans c ON c.id=t.controller_clan_id WHERE t.territory_code=${input.territory} FOR UPDATE OF t`;
  if ((controllers[0]?.controller ?? input.territory) !== input.defender) throw new Error('Defender no longer controls territory');
  await tx.$executeRaw`INSERT INTO clan_gameplay_events (event_id,server_id,clan_id,kind,xp_delta,reputation_delta,payload,occurred_at)
    VALUES (${input.eventId}::uuid,${input.serverId}::uuid,${war.attacker_clan_id}::uuid,'TERRITORY_CAPTURE',500,20,
      jsonb_build_object('territory_code',${input.territory}::text,'war_window_id',${input.warId}::text),now())`;
  await tx.$executeRaw`INSERT INTO clan_progression (clan_id,xp,level,reputation,statistics,version)
    VALUES (${war.attacker_clan_id}::uuid,500,3,20,'{"TERRITORY_CAPTURE":1}'::jsonb,1)
    ON CONFLICT(clan_id) DO UPDATE SET xp=clan_progression.xp+500,reputation=LEAST(1000000,clan_progression.reputation+20),
      level=LEAST(100,1+floor(sqrt((clan_progression.xp+500)::numeric/100))::int),
      statistics=clan_progression.statistics || jsonb_build_object('TERRITORY_CAPTURE',COALESCE((clan_progression.statistics->>'TERRITORY_CAPTURE')::bigint,0)+1),
      version=clan_progression.version+1,updated_at=now()`;
  await tx.$executeRaw`INSERT INTO clan_territory_control (territory_code,controller_clan_id,captured_from_clan_id,war_window_id,version)
    VALUES (${input.territory},${war.attacker_clan_id}::uuid,${war.defender_clan_id}::uuid,${input.warId}::uuid,1)
    ON CONFLICT(territory_code) DO UPDATE SET controller_clan_id=EXCLUDED.controller_clan_id,captured_from_clan_id=EXCLUDED.captured_from_clan_id,
      war_window_id=EXCLUDED.war_window_id,captured_at=now(),version=clan_territory_control.version+1`;
  await refreshExtraClanAchievements(tx,war.attacker_clan_id);
  return { duplicate: false };
}

export async function advanceWars(tx: Prisma.TransactionClient) {
  await tx.$executeRaw`UPDATE clan_war_windows SET status=CASE
    WHEN now()<active_starts_at THEN 'PREPARATION'
    WHEN now()<active_ends_at THEN 'ACTIVE'
    WHEN now()<COALESCE(cooldown_ends_at,active_ends_at) THEN 'COOLDOWN' ELSE 'FINISHED' END,
    version=version+1 WHERE status NOT IN ('CANCELLED','FINISHED') AND status<>CASE
    WHEN now()<active_starts_at THEN 'PREPARATION' WHEN now()<active_ends_at THEN 'ACTIVE'
    WHEN now()<COALESCE(cooldown_ends_at,active_ends_at) THEN 'COOLDOWN' ELSE 'FINISHED' END`;
}
