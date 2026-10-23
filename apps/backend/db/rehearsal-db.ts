/**
 * Disposable database for scripts/rehearse-states.mjs (World Design stage 7 dress rehearsal).
 *   tsx db/rehearsal-db.ts create|seed|assign|drop
 * Only the local dev cluster (127.0.0.1:54329) and only the database animeoncraft_rehearsal are touched.
 */
import '../src/environment.js';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { assignSlot, listSlots } from '../src/modules/community/region-assignment.js';

const NAME = 'animeoncraft_rehearsal';
const command = process.argv[2];
const migration = process.env.MIGRATION_DATABASE_URL, runtime = process.env.DATABASE_URL;
assert.ok(migration && runtime, 'MIGRATION_DATABASE_URL and DATABASE_URL required');
for (const value of [migration, runtime]) { const url = new URL(value); assert.equal(url.hostname, '127.0.0.1'); assert.equal(url.port, '54329'); }
const at = (value: string, name: string) => { const url = new URL(value); url.pathname = `/${name}`; return url.toString(); };
const connect = (url: string) => new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
const colors: Record<string, string> = { SCAM: 'E84A5F', NYA: 'F47DCB', AKUMA: 'A970FF', ZXC: '4DA8FF', YAKUZA: 'FFB347', KITSUNE: 'FF8A3D' };

if (command === 'create' || command === 'drop') {
  const admin = connect(migration);
  try {
    await admin.$executeRawUnsafe(`DROP DATABASE IF EXISTS "${NAME}" WITH (FORCE)`);
    if (command === 'create') await admin.$executeRawUnsafe(`CREATE DATABASE "${NAME}"`);
  } finally { await admin.$disconnect(); }
  console.log(JSON.stringify({ ok: true, command, database: NAME }));
} else if (command === 'seed') {
  const serverId = process.env.AOC_GAME_SERVER_ID ?? '';
  assert.match(serverId, /^[0-9a-f-]{36}$/);
  const db = connect(at(migration, NAME));
  try {
    await db.$transaction(async tx => {
      await tx.$executeRaw`INSERT INTO game_servers(id,name) VALUES (${serverId}::uuid,'Rehearsal server')`;
      for (const [code, color] of Object.entries(colors))
        await tx.$executeRaw`INSERT INTO clans(name,short_code,code_key,enabled,color,description) VALUES (${code},${code},${code},true,${color},'Rehearsal')`;
      const email = `rehearsal-owner-${randomUUID()}@example.invalid`;
      await tx.user.create({ data: { email, emailKey: email, passwordHash: 'UNUSABLE_TEST_FIXTURE', systemRole: 'OWNER' } });
    });
    console.log(JSON.stringify({ ok: true, clans: Object.keys(colors) }));
  } finally { await db.$disconnect(); }
} else if (command === 'assign') {
  // The same code path as POST /api/v1/management/world/slots/:id/assign, without the HTTP/session layer.
  const db = connect(at(runtime, NAME));
  try {
    const result = await db.$transaction(async tx => {
      const slot = (await listSlots(tx)).items.find(s => s.code === 'frontier.slot.d');
      assert.ok(slot, 'slot D missing');
      const clan = (await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE code_key='KITSUNE'`)[0]!.id;
      return assignSlot(tx, slot.id, clan, { route: '/rehearsal', method: 'POST', key: undefined, match: `"${slot.version}"`, body: { reason: 'Rehearsal' }, request_id: 'rehearsal' },
        async () => undefined);
    });
    console.log(JSON.stringify({ ok: true, slot: result.code, clan: result.clan?.short_code, status: result.status }));
  } finally { await db.$disconnect(); }
} else throw new Error('Usage: rehearsal-db.ts create|seed|assign|drop');
