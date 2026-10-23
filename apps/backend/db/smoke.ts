import '../src/environment.js';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';

const migrationUrl = process.env.MIGRATION_DATABASE_URL;
const runtimeUrl = process.env.DATABASE_URL;
assert.ok(migrationUrl && runtimeUrl, 'Local database URLs required');
for (const url of [migrationUrl, runtimeUrl]) {
  const parsed = new URL(url);
  assert.equal(parsed.hostname, '127.0.0.1', 'Smoke is limited to local development storage');
  assert.equal(parsed.port, '54329');
  assert.equal(parsed.pathname, '/animeoncraft');
}
const migration = new PrismaClient({ adapter: new PrismaPg({ connectionString: migrationUrl, connectionTimeoutMillis: 2000 }) });
const runtime = new PrismaClient({ adapter: new PrismaPg({ connectionString: runtimeUrl, connectionTimeoutMillis: 2000 }) });
try {
  const clans = await runtime.clan.findMany({ orderBy: { codeKey: 'asc' } });
  assert.deepEqual(clans.map((clan) => clan.codeKey), ['AKUMA', 'NYA', 'SCAM', 'YAKUZA', 'ZXC']);
  assert.ok(clans.every((clan) => clan.archivedAt === null));
  const initialUserCount = await runtime.user.count();
  const privileges = await runtime.$queryRaw<{ rolsuper: boolean; rolcreatedb: boolean; rolcreaterole: boolean; ddl: boolean }[]>`
    SELECT rolsuper, rolcreatedb, rolcreaterole, has_schema_privilege(current_user, 'public', 'CREATE') AS ddl
    FROM pg_roles WHERE rolname = current_user`;
  assert.deepEqual(privileges, [{ rolsuper: false, rolcreatedb: false, rolcreaterole: false, ddl: false }]);
  await assert.rejects(migration.$transaction(async (tx) => {
    for (let index = 0; index < 2; index++) {
      const email = `smoke-${randomUUID()}@example.invalid`;
      await tx.user.create({ data: { email, emailKey: email, passwordHash: 'TEST_ONLY_UNUSABLE', systemRole: 'OWNER' } });
    }
  }), (error: unknown) => typeof error === 'object' && error !== null && 'code' in error && error.code === 'P2002');
  assert.equal(await runtime.user.count(), initialUserCount, 'OWNER uniqueness probe must roll back both inserts');
  console.log('Real PostgreSQL smoke: disabled seeds, no default user, runtime privileges, one OWNER constraint passed');
} finally {
  await Promise.all([migration.$disconnect(), runtime.$disconnect()]);
}
