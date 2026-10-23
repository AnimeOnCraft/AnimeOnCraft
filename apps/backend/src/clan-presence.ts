import type { Prisma } from './generated/prisma/client.js';

export async function recordPresence(tx: Prisma.TransactionClient, serverId: string, observedAt: Date, players: readonly {user_id:string}[]) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${serverId}))`;
  const accepted = await tx.$queryRaw<Array<{server_id:string}>>`INSERT INTO clan_server_presence (server_id,snapshot_at,updated_at)
    VALUES (${serverId}::uuid,${observedAt},now()) ON CONFLICT(server_id) DO UPDATE SET snapshot_at=EXCLUDED.snapshot_at,updated_at=now()
    WHERE clan_server_presence.snapshot_at<EXCLUDED.snapshot_at RETURNING server_id`;
  if (!accepted.length) return false;
  await tx.$executeRaw`UPDATE clan_online_presence SET online=false,updated_at=${observedAt} WHERE server_id=${serverId}::uuid`;
  for (const player of players) {
    await tx.$executeRaw`INSERT INTO clan_online_presence (server_id,user_id,clan_id,online,last_seen_at,updated_at)
      SELECT ${serverId}::uuid,u.id,m.clan_id,true,${observedAt},${observedAt} FROM users u LEFT JOIN clan_members m ON m.user_id=u.id AND m.ended_at IS NULL
      WHERE u.id=${player.user_id}::uuid AND u.state='ACTIVE'
      ON CONFLICT(server_id,user_id) DO UPDATE SET clan_id=EXCLUDED.clan_id,online=true,last_seen_at=EXCLUDED.last_seen_at,updated_at=EXCLUDED.updated_at`;
  }
  return true;
}
