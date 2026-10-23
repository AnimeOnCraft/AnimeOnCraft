/**
 * Imports the relief underlay of the launcher map (World Design stage 6) for a world already in the catalog.
 *   corepack pnpm --filter @animeoncraft/backend run world:relief -- --relief <dir>/relief.json [--check]
 * relief.json and the image next to it are written by scripts/world-design/render-map.py from a pregenerated world.
 * --check validates without touching the database. An identical image is a no-op; a new one bumps the revision.
 */
import '../src/environment.js';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { applyRelief, parseRelief, ReliefError } from '../src/world-catalog/relief.js';

const args = process.argv.slice(2).filter(arg => arg !== '--');
const at = args.indexOf('--relief'), file = at >= 0 ? args[at + 1] : undefined;
for (const [i, arg] of args.entries()) if (arg.startsWith('--') && !['--check', '--relief'].includes(arg) && args[i - 1] !== '--relief') throw new Error(`Unknown option ${arg}`);
if (!file) throw new Error('--relief <relief.json> is required');
const metaPath = path.resolve(file), metaText = await readFile(metaPath, 'utf8');
if (Buffer.byteLength(metaText) > 4096) throw new ReliefError('relief.json larger than 4 KiB');
const meta = JSON.parse(metaText) as Record<string, unknown>;
if (typeof meta.image !== 'string' || !/^[A-Za-z0-9_-]+\.(jpg|jpeg|png)$/.test(meta.image)) throw new ReliefError('relief.json: image must be a file name next to it');
const relief = parseRelief(meta, await readFile(path.join(path.dirname(metaPath), meta.image)));
const summary = { world_id: relief.world_id, mime: relief.mime, bytes: relief.image.length, pixels: [relief.width_px, relief.height_px],
  bounds: { x0: relief.x0, z0: relief.z0, width_blocks: relief.width_blocks, height_blocks: relief.height_blocks }, sha256: relief.sha256 };
if (args.includes('--check')) { console.log(JSON.stringify({ ok: true, check_only: true, ...summary })); process.exit(0); }
const url = process.env.DATABASE_URL;
if (!url) throw new Error('DATABASE_URL is required for import (use --check to validate only)');
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
try {
  const result = await db.$transaction(tx => applyRelief(tx, relief), { timeout: 60000 });
  console.log(JSON.stringify({ ok: true, ...summary, ...result }));
} finally { await db.$disconnect(); }
