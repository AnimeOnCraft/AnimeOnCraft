/**
 * World Design stage 8: assign a frontier slot of the active world to a new clan, or take it back
 * (docs/FRONTIER_EXPANSION.md). Owner-only commands with If-Match versions, idempotency and audit through
 * CommunityService. The game server sees the change on its next region-catalog poll (≤ 30 s), no restart.
 */
import { HttpException, NotFoundException } from '@nestjs/common';
import type { Prisma } from '../../generated/prisma/client.js';
import { checkVersion, type Command } from './community.service.js';

type Tx = Prisma.TransactionClient;
interface SlotRow { id: string; code: string; display_name: string; status: string; assignment: string; clan_id: string | null; clan_code: string | null; clan_name: string | null;
  version: bigint; min_x: number; min_z: number; max_x: number; max_z: number; capital_x: number | null; capital_z: number | null; natural_profile: unknown }
const view = (row: SlotRow) => ({ id: row.id, code: row.code, display_name: row.display_name, status: row.status, assignment: row.assignment,
  clan: row.clan_id ? { id: row.clan_id, short_code: row.clan_code, name: row.clan_name } : null, version: row.version.toString(),
  center: { x: Math.round((row.min_x + row.max_x) / 2), z: Math.round((row.min_z + row.max_z) / 2) },
  capital: row.capital_x === null || row.capital_z === null ? null : { x: row.capital_x, z: row.capital_z }, natural_profile: row.natural_profile });

async function slots(tx: Tx, id?: string, lock = false): Promise<SlotRow[]> {
  const rows = await tx.$queryRaw<SlotRow[]>`
    SELECT r.id,r.code,r.display_name,r.status,r.assignment,r.clan_id,c.short_code AS clan_code,c.name AS clan_name,r.version,
      b.min_x,b.min_z,b.max_x,b.max_z,r.capital_x,r.capital_z,r.natural_profile
    FROM world_regions r JOIN world_catalog w ON w.world_id=r.world_id AND w.active JOIN region_borders b ON b.region_id=r.id
      LEFT JOIN clans c ON c.id=r.clan_id
    WHERE r.kind='FRONTIER_SLOT' AND r.status<>'ARCHIVED' AND (${id ?? null}::uuid IS NULL OR r.id=${id ?? null}::uuid) ORDER BY r.code`;
  if (lock && rows[0]) await tx.$queryRaw`SELECT id FROM world_regions WHERE id=${rows[0].id}::uuid FOR UPDATE`;
  return rows;
}

export async function listSlots(tx: Tx) { return { items: (await slots(tx)).map(view) }; }

type Audit = (tx: Tx, action: string, id: string, before: Prisma.InputJsonObject, after: Prisma.InputJsonObject) => Promise<unknown>;

export async function assignSlot(tx: Tx, id: string, clanId: string, command: Command, audit: Audit) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002620)`; // never races a catalog import
  const slot = (await slots(tx, id, true))[0];
  if (!slot) throw new NotFoundException();
  checkVersion(slot.version, command);
  if (slot.status !== 'RESERVED') throw new HttpException('Slot is not free', 409);
  const clan = (await tx.$queryRaw<Array<{ id: string; code_key: string; archived_at: Date | null; enabled: boolean }>>`
    SELECT id,code_key,archived_at,enabled FROM clans WHERE id=${clanId}::uuid FOR UPDATE`)[0];
  if (!clan || clan.archived_at || !clan.enabled) throw new HttpException('Clan is not active', 409);
  const held = await tx.$queryRaw<Array<{ code: string }>>`SELECT r.code FROM world_regions r JOIN world_catalog w ON w.world_id=r.world_id AND w.active
    WHERE r.clan_id=${clanId}::uuid AND r.status='ACTIVE' AND r.kind <> 'MARKET'`;
  if (held.length) throw new HttpException('Clan already holds a region', 409);
  await tx.$executeRaw`UPDATE world_regions SET status='ACTIVE',clan_id=${clanId}::uuid,assignment='ADMIN',pvp_policy='WAR_ONLY',build_policy='MEMBERS',
      spawn_x=COALESCE(spawn_x,capital_x),spawn_z=COALESCE(spawn_z,capital_z),version=version+1,updated_at=now() WHERE id=${id}::uuid`;
  const after = (await slots(tx, id))[0]!;
  await audit(tx, 'WORLD_SLOT_ASSIGNED', id, { status: slot.status, clan_id: slot.clan_id, version: slot.version.toString() },
    { status: after.status, clan_id: clanId, clan_code: clan.code_key, version: after.version.toString() });
  return view(after);
}

export async function releaseSlot(tx: Tx, id: string, command: Command, audit: Audit) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002620)`;
  const slot = (await slots(tx, id, true))[0];
  if (!slot) throw new NotFoundException();
  checkVersion(slot.version, command);
  if (slot.status !== 'ACTIVE' || slot.assignment !== 'ADMIN') throw new HttpException('Slot was not assigned in the admin panel', 409);
  // Back to wild land: protection and spawn go, blocks and history stay.
  await tx.$executeRaw`UPDATE world_regions SET status='RESERVED',clan_id=NULL,assignment='CATALOG',pvp_policy='ON',build_policy='OPEN',
      spawn_x=NULL,spawn_y=NULL,spawn_z=NULL,version=version+1,updated_at=now() WHERE id=${id}::uuid`;
  const after = (await slots(tx, id))[0]!;
  await audit(tx, 'WORLD_SLOT_RELEASED', id, { status: slot.status, clan_id: slot.clan_id, version: slot.version.toString() },
    { status: after.status, clan_id: null, version: after.version.toString() });
  return view(after);
}
