/**
 * Disposable database for scripts/test-capture.mjs (World Design stage 4 E2E on a test server).
 *   tsx db/capture-probe-db.ts create|seed|report|drop
 * Only the local dev cluster (127.0.0.1:54329) and only the database animeoncraft_capture_probe are touched.
 */
import '../src/environment.js';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';

const NAME = 'animeoncraft_capture_probe';
const command = process.argv[2];
const migration = process.env.MIGRATION_DATABASE_URL, runtime = process.env.DATABASE_URL;
assert.ok(migration && runtime, 'MIGRATION_DATABASE_URL and DATABASE_URL required');
for (const value of [migration, runtime]) { const url = new URL(value); assert.equal(url.hostname, '127.0.0.1'); assert.equal(url.port, '54329'); }
const at = (value: string, name: string) => { const url = new URL(value); url.pathname = `/${name}`; return url.toString(); };
const connect = (url: string) => new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
const colors: Record<string, string> = { SCAM: 'E84A5F', NYA: 'F47DCB', AKUMA: 'A970FF', ZXC: '4DA8FF', YAKUZA: 'FFB347' };

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
    const users: Record<string, string[]> = {};
    let owner = '';
    await db.$transaction(async tx => {
      await tx.$executeRaw`INSERT INTO game_servers(id,name) VALUES (${serverId}::uuid,'Capture probe server')`;
      for (const [code, color] of Object.entries(colors)) {
        const clan = (await tx.$queryRaw<Array<{ id: string }>>`INSERT INTO clans(name,short_code,code_key,enabled,color,description)
          VALUES (${code},${code},${code},true,${color},'Capture probe') RETURNING id`)[0]!.id;
        users[code] = [];
        for (let i = 0; i < 2; i++) {
          const email = `capture-probe-${code.toLowerCase()}-${i}-${randomUUID()}@example.invalid`;
          const user = await tx.user.create({ data: { email, emailKey: email, passwordHash: 'UNUSABLE_TEST_FIXTURE' } });
          await tx.clan_members.create({ data: { clan_id: clan, user_id: user.id } });
          users[code]!.push(user.id);
        }
      }
      const email = `capture-probe-owner-${randomUUID()}@example.invalid`;
      owner = (await tx.user.create({ data: { email, emailKey: email, passwordHash: 'UNUSABLE_TEST_FIXTURE', systemRole: 'OWNER' } })).id;
    });
    console.log(JSON.stringify({ owner, users }));
  } finally { await db.$disconnect(); }
} else if (command === 'report') {
  const db = connect(at(runtime, NAME));
  try {
    const outcomes = await db.$queryRaw<Array<{ code: string; outcome: string | null; state: string; winner: string | null }>>`
      SELECT p.code,e.outcome,e.state,c.code_key AS winner FROM capture_events e JOIN strategic_points p ON p.id=e.point_id
      LEFT JOIN clans c ON c.id=e.winner_clan ORDER BY e.created_at`;
    const owners = await db.$queryRaw<Array<{ code: string; owner: string | null; capture_state: string }>>`
      SELECT p.code,c.code_key AS owner,p.capture_state FROM strategic_points p LEFT JOIN clans c ON c.id=p.owner_clan_id WHERE p.owner_clan_id IS NOT NULL ORDER BY p.code`;
    const progression = await db.$queryRaw<Array<{ code: string; xp: bigint; reputation: number }>>`
      SELECT c.code_key AS code,p.xp,p.reputation FROM clan_progression p JOIN clans c ON c.id=p.clan_id ORDER BY c.code_key`;
    const ledger = await db.$queryRaw<Array<{ kind: string; n: bigint }>>`SELECT kind,count(*) AS n FROM clan_gameplay_events GROUP BY kind ORDER BY kind`;
    const history = await db.$queryRaw<Array<{ event: string; n: bigint }>>`SELECT event,count(*) AS n FROM resource_site_history GROUP BY event ORDER BY event`;
    console.log(JSON.stringify({ outcomes, owners, progression: progression.map(r => ({ ...r, xp: Number(r.xp) })),
      ledger: Object.fromEntries(ledger.map(r => [r.kind, Number(r.n)])), history: Object.fromEntries(history.map(r => [r.event, Number(r.n)])) }));
  } finally { await db.$disconnect(); }
} else throw new Error('Usage: capture-probe-db.ts create|seed|report|drop');
