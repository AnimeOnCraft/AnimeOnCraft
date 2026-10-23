/**
 * Region catalog for the game server's protection in a states world (World Design stages 7–8):
 * active regions of the active world with their build/PvP policy, owning clan code, polygon and spawn.
 * The revision is the SHA-256 of the canonical reply, so any change — an import or a clan assigned to a
 * frontier slot — reaches the mod on its next 30 s poll; an unchanged catalog answers {unchanged:true}.
 */
import { createHash } from 'node:crypto';
import type { Prisma } from '../generated/prisma/client.js';

type Tx = Prisma.TransactionClient;
export interface ServerRegion { code: string; kind: string; clan_code: string | null; color: string | null; pvp_policy: string; build_policy: string; polygon: [number, number][]; spawn: { x: number; z: number } | null }
export interface ServerRegions { world_id: string; revision: string; regions: ServerRegion[] }

export async function readServerRegions(db: Tx): Promise<ServerRegions | null> {
  const world = (await db.$queryRaw<Array<{ world_id: string }>>`SELECT world_id FROM world_catalog WHERE active LIMIT 1`)[0];
  if (!world) return null;
  const rows = await db.$queryRaw<Array<{ code: string; kind: string; clan_code: string | null; color: string | null; pvp_policy: string; build_policy: string; polygon: [number, number][] | null; spawn_x: number | null; spawn_z: number | null }>>`
    SELECT r.code,r.kind,c.code_key AS clan_code,COALESCE(r.color_override,c.color) AS color,r.pvp_policy,r.build_policy,b.polygon,r.spawn_x,r.spawn_z
    FROM world_regions r LEFT JOIN region_borders b ON b.region_id=r.id LEFT JOIN clans c ON c.id=r.clan_id AND c.archived_at IS NULL
    WHERE r.world_id=${world.world_id} AND r.status='ACTIVE' ORDER BY r.code`;
  // An active region without a border would silently become open land: refuse the whole catalog instead.
  if (rows.some(r => !r.polygon)) throw new Error('Active region without a border');
  const regions = rows.map(r => ({ code: r.code, kind: r.kind, clan_code: r.clan_code, color: r.color, pvp_policy: r.pvp_policy, build_policy: r.build_policy, polygon: r.polygon!,
    spawn: r.spawn_x === null || r.spawn_z === null ? null : { x: r.spawn_x, z: r.spawn_z } }));
  const revision = createHash('sha256').update(JSON.stringify({ world_id: world.world_id, regions })).digest('hex');
  return { world_id: world.world_id, revision, regions };
}
