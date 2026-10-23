/**
 * Relief underlay of the launcher map (World Design stage 6): one top-down image per world with its block bounds.
 * Rendered on the owner's PC (scripts/world-design/render-map.py → relief.jpg + relief.json) and imported with
 * `world:relief`. Only the image of the active world is public.
 */
import { createHash } from 'node:crypto';
import type { Prisma } from '../generated/prisma/client.js';

type Tx = Prisma.TransactionClient;
export const RELIEF_MAX_BYTES = 3_145_728;
export class ReliefError extends Error {}
export interface Relief {
  world_id: string; mime: 'image/jpeg' | 'image/png'; x0: number; z0: number; width_blocks: number; height_blocks: number;
  width_px: number; height_px: number; sha256: string; image: Buffer;
}
export interface PublicReliefBounds { revision: string; x0: number; z0: number; width_blocks: number; height_blocks: number }
export interface PublicRelief extends PublicReliefBounds { mime: string; width_px: number; height_px: number; sha256: string; image_base64: string }

/** Pixel size from the PNG IHDR or the first JPEG SOF segment; null when the bytes are neither. */
export function imageSize(image: Buffer): { mime: Relief['mime']; width: number; height: number } | null {
  if (image.length >= 24 && image.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) && image.toString('latin1', 12, 16) === 'IHDR')
    return { mime: 'image/png', width: image.readUInt32BE(16), height: image.readUInt32BE(20) };
  if (image.length < 4 || image[0] !== 0xff || image[1] !== 0xd8) return null;
  let at = 2;
  while (at + 9 < image.length) {
    if (image[at] !== 0xff) return null;
    const marker = image[at + 1]!;
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { at += 2; continue; }
    if (marker === 0xd9 || marker === 0xda) return null; // end of image or scan data before any frame header
    const length = image.readUInt16BE(at + 2);
    if (length < 2) return null;
    // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC)
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker))
      return { mime: 'image/jpeg', height: image.readUInt16BE(at + 5), width: image.readUInt16BE(at + 7) };
    at += 2 + length;
  }
  return null;
}

const integer = (value: unknown, name: string, min: number, max: number) => {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) throw new ReliefError(`${name}: integer ${min}..${max} expected`);
  return value;
};

/** Validates the sidecar metadata and the image bytes; the pixel aspect must match the block bounds (±1 %). */
export function parseRelief(meta: unknown, image: Buffer): Relief {
  if (!meta || typeof meta !== 'object' || Array.isArray(meta)) throw new ReliefError('relief.json: object expected');
  const row = meta as Record<string, unknown>;
  const known = ['format_version', 'world_id', 'image', 'x0', 'z0', 'width_blocks', 'height_blocks'];
  for (const key of Object.keys(row)) if (!known.includes(key)) throw new ReliefError(`relief.json: unknown field ${key}`);
  if (row.format_version !== 1) throw new ReliefError('relief.json: format_version 1 expected');
  if (typeof row.world_id !== 'string' || !/^[a-z0-9][a-z0-9-]{1,63}$/.test(row.world_id)) throw new ReliefError('relief.json: world_id invalid');
  if (image.length < 1 || image.length > RELIEF_MAX_BYTES) throw new ReliefError(`image: 1..${RELIEF_MAX_BYTES} bytes expected`);
  const size = imageSize(image);
  if (!size) throw new ReliefError('image: JPEG or PNG expected');
  integer(size.width, 'image width', 64, 8192); integer(size.height, 'image height', 64, 8192);
  const relief: Relief = {
    world_id: row.world_id, mime: size.mime,
    x0: integer(row.x0, 'x0', -30_000_000, 30_000_000), z0: integer(row.z0, 'z0', -30_000_000, 30_000_000),
    width_blocks: integer(row.width_blocks, 'width_blocks', 256, 60000), height_blocks: integer(row.height_blocks, 'height_blocks', 256, 60000),
    width_px: size.width, height_px: size.height, sha256: createHash('sha256').update(image).digest('hex'), image,
  };
  const aspectBlocks = relief.width_blocks / relief.height_blocks, aspectPixels = relief.width_px / relief.height_px;
  if (Math.abs(aspectBlocks / aspectPixels - 1) > 0.01) throw new ReliefError('image aspect does not match the block bounds');
  return relief;
}

/** Stores or replaces the relief of a world already in the catalog; an identical image is a no-op. */
export async function applyRelief(tx: Tx, relief: Relief): Promise<{ world_id: string; changed: boolean; revision: string }> {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002620)`;
  const world = await tx.$queryRaw<Array<{ world_id: string }>>`SELECT world_id FROM world_catalog WHERE world_id=${relief.world_id} FOR UPDATE`;
  if (!world[0]) throw new ReliefError(`World ${relief.world_id} is not in the catalog; run world:import first`);
  const prior = (await tx.$queryRaw<Array<{ sha256: string; revision: bigint; x0: number; z0: number; width_blocks: number; height_blocks: number }>>`
    SELECT sha256,revision,x0,z0,width_blocks,height_blocks FROM world_relief WHERE world_id=${relief.world_id} FOR UPDATE`)[0];
  if (prior && prior.sha256 === relief.sha256 && prior.x0 === relief.x0 && prior.z0 === relief.z0 && prior.width_blocks === relief.width_blocks && prior.height_blocks === relief.height_blocks)
    return { world_id: relief.world_id, changed: false, revision: prior.revision.toString() };
  const rows = await tx.$queryRaw<Array<{ revision: bigint }>>`
    INSERT INTO world_relief(world_id,mime,x0,z0,width_blocks,height_blocks,width_px,height_px,sha256,image)
    VALUES (${relief.world_id},${relief.mime},${relief.x0},${relief.z0},${relief.width_blocks},${relief.height_blocks},${relief.width_px},${relief.height_px},${relief.sha256},${relief.image})
    ON CONFLICT (world_id) DO UPDATE SET mime=EXCLUDED.mime,x0=EXCLUDED.x0,z0=EXCLUDED.z0,width_blocks=EXCLUDED.width_blocks,height_blocks=EXCLUDED.height_blocks,
      width_px=EXCLUDED.width_px,height_px=EXCLUDED.height_px,sha256=EXCLUDED.sha256,image=EXCLUDED.image,revision=world_relief.revision+1,imported_at=now()
    RETURNING revision`;
  return { world_id: relief.world_id, changed: true, revision: rows[0]!.revision.toString() };
}

/** Bounds of the active world's relief for the map response (the image itself is a separate request). */
export async function readReliefBounds(db: Tx, worldId: string): Promise<PublicReliefBounds | null> {
  const row = (await db.$queryRaw<Array<{ revision: bigint; x0: number; z0: number; width_blocks: number; height_blocks: number }>>`
    SELECT revision,x0,z0,width_blocks,height_blocks FROM world_relief WHERE world_id=${worldId}`)[0];
  return row ? { revision: row.revision.toString(), x0: row.x0, z0: row.z0, width_blocks: row.width_blocks, height_blocks: row.height_blocks } : null;
}

export async function readWorldRelief(db: Tx): Promise<PublicRelief | null> {
  const row = (await db.$queryRaw<Array<{ revision: bigint; mime: string; x0: number; z0: number; width_blocks: number; height_blocks: number; width_px: number; height_px: number; sha256: string; image: Uint8Array }>>`
    SELECT r.revision,r.mime,r.x0,r.z0,r.width_blocks,r.height_blocks,r.width_px,r.height_px,r.sha256,r.image
    FROM world_relief r JOIN world_catalog w ON w.world_id=r.world_id WHERE w.active LIMIT 1`)[0];
  if (!row) return null;
  return { revision: row.revision.toString(), mime: row.mime, x0: row.x0, z0: row.z0, width_blocks: row.width_blocks, height_blocks: row.height_blocks,
    width_px: row.width_px, height_px: row.height_px, sha256: row.sha256, image_base64: Buffer.from(row.image).toString('base64') };
}
