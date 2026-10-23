import { randomUUID } from 'node:crypto';
import type { Prisma } from './generated/prisma/client.js';

export async function startBossCompetition(tx: Prisma.TransactionClient, actorId: string, minutes: number) {
  if (!Number.isInteger(minutes) || minutes < 10 || minutes > 1440) throw new Error('Duration must be 10–1440 minutes');
  const owner = await tx.user.findFirst({ where: { id: actorId, systemRole: 'OWNER', state: 'ACTIVE' } });
  if (!owner) throw new Error('Owner required');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  const pending = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clan_events
    WHERE status IN ('SCHEDULED','ACTIVE') AND ends_at>now()-interval '10 minutes' LIMIT 1`;
  if (pending.length) throw new Error('Competition already running or awaiting finalization');
  const id = randomUUID();
  await tx.$executeRaw`INSERT INTO clan_events (id,code,title,status,starts_at,ends_at,payload)
    VALUES (${id}::uuid,${'boss-' + id},'Охота на боссов','ACTIVE',now(),now()+${minutes}::int*interval '1 minute',
      jsonb_build_object('kind','BOSS_HUNT','actor_user_id',${actorId}::text))`;
  return { id, duration_minutes: minutes };
}

/** Accepted kills count once because this runs only after the idempotent event insert. */
export async function scoreBossCompetitions(tx: Prisma.TransactionClient, clanId: string, occurredAt: Date) {
  const events = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clan_events WHERE status='ACTIVE'
    AND payload->>'kind'='BOSS_HUNT' AND starts_at<=${occurredAt} AND ends_at>${occurredAt}
    AND ends_at>now()-interval '10 minutes' ORDER BY id FOR UPDATE`;
  for (const event of events) {
    await tx.$executeRaw`INSERT INTO clan_event_scores (event_id,clan_id,score,version) VALUES (${event.id}::uuid,${clanId}::uuid,1,1)
      ON CONFLICT(event_id,clan_id) DO UPDATE SET score=clan_event_scores.score+1,version=clan_event_scores.version+1,updated_at=now()`;
  }
}

/** Ten-minute grace permits short outages; row locks make settlement safe to retry. */
export async function settleBossCompetitions(tx: Prisma.TransactionClient) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
  const events = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clan_events WHERE status='ACTIVE'
    AND payload->>'kind'='BOSS_HUNT' AND ends_at<=now()-interval '10 minutes' ORDER BY id FOR UPDATE`;
  for (const event of events) {
    const scores = await tx.$queryRaw<Array<{ clan_id: string; score: bigint; reward_granted_at: Date | null }>>`
      SELECT clan_id,score,reward_granted_at FROM clan_event_scores WHERE event_id=${event.id}::uuid ORDER BY score DESC,clan_id FOR UPDATE`;
    const best = scores[0]?.score ?? 0n;
    for (const score of scores) {
      if (score.reward_granted_at !== null || score.score === 0n) continue;
      const winner = score.score === best, xp = winner ? 1000 : 300, reputation = winner ? 30 : 10;
      await tx.$executeRaw`INSERT INTO clan_progression (clan_id,xp,level,reputation,statistics,version)
        VALUES (${score.clan_id}::uuid,${xp},${1 + Math.floor(Math.sqrt(xp / 100))},${reputation},'{}'::jsonb,1)
        ON CONFLICT(clan_id) DO UPDATE SET xp=clan_progression.xp+${xp},reputation=LEAST(1000000,clan_progression.reputation+${reputation}),
          level=LEAST(100,1+floor(sqrt((clan_progression.xp+${xp})::numeric/100))::int),version=clan_progression.version+1,updated_at=now()`;
      await tx.$executeRaw`UPDATE clan_event_scores SET reward_granted_at=now(),version=version+1,updated_at=now()
        WHERE event_id=${event.id}::uuid AND clan_id=${score.clan_id}::uuid`;
    }
    await tx.$executeRaw`UPDATE clan_events SET status='FINISHED',version=version+1 WHERE id=${event.id}::uuid`;
  }
}
