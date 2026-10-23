/**
 * Imports the world catalog (minecraft/server/world-config/world.toml by default) into the backend.
 *   corepack pnpm --filter @animeoncraft/backend run world:import -- [--check] [--activate] [--profile <path>] [--reason "..."]
 * --check validates and prints the catalog summary without touching the database.
 * The import is idempotent: an unchanged catalog does not bump the version. Runtime ownership is never reset.
 */
import '../src/environment.js';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { catalogDigest } from '../src/world-catalog/catalog.js';
import { applyCatalog } from '../src/world-catalog/catalog-store.js';
import { loadCatalog } from '../src/world-catalog/load.js';

const args = process.argv.slice(2).filter(arg => arg !== '--');
const flag = (name: string) => args.includes(name);
const value = (name: string) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
for (const [i, arg] of args.entries()) if (arg.startsWith('--') && !['--check', '--activate', '--profile', '--reason'].includes(arg) && !['--profile', '--reason'].includes(args[i - 1] ?? '')) throw new Error(`Unknown option ${arg}`);
const repo = fileURLToPath(new URL('../../../', import.meta.url));
const profile = path.resolve(value('--profile') ?? path.join(repo, 'minecraft/server/world-config/world.toml'));
const catalog = await loadCatalog(profile);
const summary = { world_id: catalog.world.id, digest: catalogDigest(catalog), regions: catalog.regions.length, points: catalog.points.length,
  resource_sites: catalog.points.filter(p => p.resource).length, connections: catalog.connections.length, markers: catalog.markers.length };
if (flag('--check')) { console.log(JSON.stringify({ ok: true, check_only: true, ...summary })); process.exit(0); }
const url = process.env.DATABASE_URL;
if (!url) throw new Error('DATABASE_URL is required for import (use --check to validate only)');
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
try {
  const result = await db.$transaction(tx => applyCatalog(tx, catalog, { activate: flag('--activate'), reason: value('--reason') ?? 'World catalog import' }), { timeout: 60000 });
  console.log(JSON.stringify({ ok: true, ...summary, ...result }));
} finally { await db.$disconnect(); }
