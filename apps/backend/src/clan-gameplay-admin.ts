import type { Prisma } from './generated/prisma/client.js';

async function requireOwner(tx: Prisma.TransactionClient, actorId: string) {
  if(!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(actorId))throw new Error('Invalid owner identity');
  if (!await tx.user.findFirst({where:{id:actorId,systemRole:'OWNER',state:'ACTIVE'}})) throw new Error('Owner required');
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002601)`;
}
export async function cancelCompetition(tx: Prisma.TransactionClient, actorId: string) {
  await requireOwner(tx,actorId);
  const rows=await tx.$queryRaw<Array<{id:string}>>`SELECT id FROM clan_events WHERE status IN ('SCHEDULED','ACTIVE')
    AND payload->>'kind' IN ('BOSS_HUNT','WORLD_BOSS') ORDER BY starts_at DESC LIMIT 1 FOR UPDATE`;
  if(!rows[0])return {cancelled:false};
  await tx.$executeRaw`UPDATE clan_events SET status='CANCELLED',version=version+1,
    payload=payload||jsonb_build_object('cancelled_by',${actorId}::text) WHERE id=${rows[0].id}::uuid`;
  return {cancelled:true,id:rows[0].id};
}
export async function cancelWar(tx: Prisma.TransactionClient, actorId:string, attacker:string, defender:string) {
  await requireOwner(tx,actorId);
  const rows=await tx.$queryRaw<Array<{id:string;active_starts_at:Date;active_ends_at:Date}>>`
    SELECT w.id,w.active_starts_at,w.active_ends_at FROM clan_war_windows w JOIN clans a ON a.id=w.attacker_clan_id
    JOIN clans d ON d.id=w.defender_clan_id WHERE a.code_key=${attacker} AND d.code_key=${defender}
      AND w.status<>'CANCELLED' AND w.active_ends_at>now() ORDER BY w.created_at DESC LIMIT 1 FOR UPDATE OF w`;
  const war=rows[0];if(!war)return {cancelled:false};
  if(new Date()<war.active_starts_at){
    await tx.$executeRaw`UPDATE clan_war_windows SET status='CANCELLED',version=version+1,
      reason=reason||${' | Cancelled by owner '+actorId} WHERE id=${war.id}::uuid`;
  }else{
    await tx.$executeRaw`UPDATE clan_war_windows SET status='COOLDOWN',active_ends_at=now(),cooldown_ends_at=now()+interval '24 hours',
      version=version+1,reason=reason||${' | Aborted by owner '+actorId} WHERE id=${war.id}::uuid`;
  }
  return {cancelled:true,id:war.id};
}
