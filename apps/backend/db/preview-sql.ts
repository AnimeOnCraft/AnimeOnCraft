/** Dev helper for scripts/preview-world-map.mjs: runs JSON-encoded SQL statements against PREVIEW_URL (local only). */
import assert from 'node:assert/strict';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';

const url = process.env.PREVIEW_URL, statements = JSON.parse(process.env.PREVIEW_SQL ?? '[]') as string[];
assert.ok(url); const parsed = new URL(url); assert.equal(parsed.hostname, '127.0.0.1'); assert.equal(parsed.port, '54329');
assert.ok(['/animeoncraft', '/animeoncraft_map_preview'].includes(parsed.pathname), 'Only the local dev cluster');
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
try { for (const statement of statements) await db.$executeRawUnsafe(statement); } finally { await db.$disconnect(); }
