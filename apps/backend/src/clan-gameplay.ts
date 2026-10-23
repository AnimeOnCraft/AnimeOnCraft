import {refreshExtraClanAchievements} from './clan-achievements.js';
import { scoreBossCompetitions } from './clan-competitions.js';
import type { Prisma } from './generated/prisma/client.js';

export const BOSS_ACHIEVEMENTS = [
  { code: 'FIRST_BOSS', target: 1, xp: 100, reputation: 5 },
  { code: 'BOSS_HUNTERS', target: 10, xp: 500, reputation: 20 },
  { code: 'BOSS_VETERANS', target: 50, xp: 2000, reputation: 50 },
] as const;

/** The caller owns the transaction: event, counters and unlock bonuses commit together. */
export async function recordBossKill(tx: Prisma.TransactionClient, input: {
  eventId: string; serverId: string; clanId: string; occurredAt: Date; reward?:{xp:number;reputation:number};
}) {
  const xp=input.reward?.xp??100,reputation=input.reward?.reputation??5;
  if(!Number.isInteger(xp)||xp<0||xp>100000||!Number.isInteger(reputation)||reputation<0||reputation>10000)throw new Error('Invalid server reward');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  const inserted = await tx.$executeRaw`INSERT INTO clan_gameplay_events
    (event_id,server_id,clan_id,kind,xp_delta,reputation_delta,payload,occurred_at)
    VALUES (${input.eventId}::uuid,${input.serverId}::uuid,${input.clanId}::uuid,'BOSS_KILL',${xp},${reputation},'{}'::jsonb,${input.occurredAt})
    ON CONFLICT (event_id) DO NOTHING`;
  if (inserted === 0) {
    const existing = await tx.$queryRaw<Array<{ server_id: string; clan_id: string; kind: string }>>`
      SELECT server_id,clan_id,kind FROM clan_gameplay_events WHERE event_id=${input.eventId}::uuid`;
    if (existing[0]?.server_id !== input.serverId || existing[0]?.clan_id !== input.clanId || existing[0]?.kind !== 'BOSS_KILL') {
      throw new Error('Event identity conflict');
    }
    return { duplicate: true, unlocked: [] as string[] };
  }
  // This row lock also serializes achievement transitions for concurrent kills of one clan.
  const rows = await tx.$queryRaw<Array<{ kills: bigint }>>`INSERT INTO clan_progression
    (clan_id,xp,level,reputation,statistics,version,updated_at)
    VALUES (${input.clanId}::uuid,${xp},${1+Math.floor(Math.sqrt(xp/100))},${reputation},'{"BOSS_KILL":1}'::jsonb,1,now())
    ON CONFLICT (clan_id) DO UPDATE SET xp=clan_progression.xp+${xp},
      reputation=LEAST(1000000,clan_progression.reputation+${reputation}),
      level=LEAST(100,1+floor(sqrt((clan_progression.xp+${xp})::numeric/100))::int),
      statistics=clan_progression.statistics || jsonb_build_object('BOSS_KILL',COALESCE((clan_progression.statistics->>'BOSS_KILL')::bigint,0)+1),
      version=clan_progression.version+1,updated_at=now()
    RETURNING (statistics->>'BOSS_KILL')::bigint AS kills`;
  const kills = rows[0]!.kills;
  const unlocked: string[] = [];
  for (const achievement of BOSS_ACHIEVEMENTS) {
    const changed = await tx.$queryRaw<Array<{ code: string }>>`INSERT INTO clan_achievements
      (clan_id,code,progress,target,unlocked_at,version,updated_at)
      VALUES (${input.clanId}::uuid,${achievement.code},LEAST(${kills}::bigint,${achievement.target}::bigint),${achievement.target},
        CASE WHEN ${kills}::bigint>=${achievement.target}::bigint THEN now() ELSE NULL END,1,now())
      ON CONFLICT (clan_id,code) DO UPDATE SET progress=LEAST(EXCLUDED.progress,clan_achievements.target),
        unlocked_at=EXCLUDED.unlocked_at,version=clan_achievements.version+1,updated_at=now()
        WHERE clan_achievements.unlocked_at IS NULL
      RETURNING code,unlocked_at`;
    if (kills >= BigInt(achievement.target) && changed.length > 0) {
      await tx.$executeRaw`UPDATE clan_progression SET xp=xp+${achievement.xp},
        reputation=LEAST(1000000,reputation+${achievement.reputation}),
        level=LEAST(100,1+floor(sqrt((xp+${achievement.xp})::numeric/100))::int),
        version=version+1,updated_at=now() WHERE clan_id=${input.clanId}::uuid`;
      unlocked.push(achievement.code);
    }
  }
  unlocked.push(...await refreshExtraClanAchievements(tx,input.clanId));
  await scoreBossCompetitions(tx, input.clanId, input.occurredAt);
  return { duplicate: false, unlocked };
}
