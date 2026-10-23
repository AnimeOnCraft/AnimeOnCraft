import '../src/environment.js';
import 'reflect-metadata';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { ValidationPipe } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify';
import { PrismaPg } from '@prisma/adapter-pg';
import { Prisma, PrismaClient } from '../src/generated/prisma/client.js';
import { DatabaseService } from '../src/infrastructure/database.service.js';
import { InfrastructureModule } from '../src/infrastructure/infrastructure.module.js';
import { DevelopmentModule } from '../src/modules/development/development.module.js';
import { developmentApiEnabled } from '../src/modules/development/development-config.js';
import { AllExceptionsFilter } from '../src/http/all-exceptions.filter.js';
import { ClansModule } from '../src/modules/clans/clans.module.js';
import { NewsModule } from '../src/modules/news/news.module.js';

const backend = fileURLToPath(new URL('../', import.meta.url));
const migrationUrl = process.env.MIGRATION_DATABASE_URL;
const runtimeUrl = process.env.DATABASE_URL;
assert.ok(migrationUrl && runtimeUrl);
for (const value of [migrationUrl, runtimeUrl]) {
  const parsed = new URL(value);
  assert.equal(parsed.hostname, '127.0.0.1'); assert.equal(parsed.port, '54329');
  assert.equal(parsed.pathname, '/animeoncraft', 'Tests require the known local development container');
}
const databaseName = `animeoncraft_stage3_test_${randomUUID().replaceAll('-', '')}`;
assert.match(databaseName, /^animeoncraft_stage3_test_[a-f0-9]{32}$/);
const urlFor = (value: string) => { const parsed = new URL(value); parsed.pathname = `/${databaseName}`; return parsed.toString(); };
const clientFor = (value: string) => new PrismaClient({ adapter: new PrismaPg({ connectionString: value, connectionTimeoutMillis: 2000, query_timeout: 5000 }) });
const admin = clientFor(migrationUrl);
const migration = clientFor(urlFor(migrationUrl));
const runtime = clientFor(urlFor(runtimeUrl));
let created = false;
let app: NestFastifyApplication | undefined;
mkdirSync(path.join(backend, '.tools'), { recursive: true });
const temporary = mkdtempSync(path.join(backend, '.tools', 'stage3-'));
assert.ok(path.resolve(temporary).startsWith(path.join(backend, '.tools') + path.sep));
const require = createRequire(import.meta.url);
const config = path.join(temporary, 'prisma.config.mjs');
writeFileSync(path.join(temporary, 'schema.prisma'), readFileSync(path.join(backend, 'db/schema.prisma')));
writeFileSync(config, `import { defineConfig } from ${JSON.stringify(pathToFileURL(require.resolve('prisma/config')).href)};
export default defineConfig({ schema: './schema.prisma', migrations: { path: './migrations' }, datasource: { url: process.env.TEST_DATABASE_URL } });\n`);
mkdirSync(path.join(temporary, 'migrations'));
cpSync(path.join(backend, 'db/migrations/migration_lock.toml'), path.join(temporary, 'migrations/migration_lock.toml'));
function deploy() {
  const result = spawnSync(process.execPath, [path.join(backend, 'node_modules/prisma/build/index.js'), 'migrate', 'deploy', '--config', config],
    { cwd: backend, env: { ...process.env, TEST_DATABASE_URL: urlFor(migrationUrl!) }, encoding: 'utf8', timeout: 60_000 });
  // Never print database URLs or CLI output on failure.
  assert.equal(result.status, 0, `Migration CLI failed (exit ${result.status})`);
}
function copyMigration(name: string) {
  cpSync(path.join(backend, 'db/migrations', name), path.join(temporary, 'migrations', name), { recursive: true });
}
async function sqlReject(query: Prisma.Sql, expected: string) {
  await assert.rejects(runtime.$executeRaw(query), (error: unknown) => {
    if (!(error instanceof Prisma.PrismaClientKnownRequestError)) return false;
    const meta = error.meta as { code?: string; driverAdapterError?: { cause?: { originalCode?: string } } } | undefined;
    return meta?.code === expected || meta?.driverAdapterError?.cause?.originalCode === expected;
  });
}
try {
  await admin.$executeRawUnsafe(`CREATE DATABASE "${databaseName}"`);
  created = true;
  copyMigration('20261009000100_skeleton'); deploy();
  const [clan] = await migration.$queryRaw<{ id: string }[]>`INSERT INTO clans(name,short_code,code_key) VALUES ('Upgrade preserved','UP','UP') RETURNING id`;
  assert.ok(clan);
  copyMigration('20261010000200_domain_base'); copyMigration('20261010000300_strict_null_checks'); copyMigration('20261010000400_public_indexes'); copyMigration('20261010000500_audit_service_identity'); copyMigration('20261010000600_relative_release_targets'); copyMigration('20261010000700_authentication'); copyMigration('20261010000800_identity_outbox'); deploy(); deploy();
  assert.equal((await runtime.clan.findUniqueOrThrow({ where: { id: clan.id } })).name, 'Upgrade preserved');
  const tables = await runtime.$queryRaw<{ tablename: string }[]>`SELECT tablename FROM pg_tables WHERE schemaname='public'`;
  for (const name of ['users','minecraft_accounts','sessions','clans','clan_members','clan_roles','clan_applications','whitelist','bans','news','launcher_versions','server_events','player_statistics','audit_logs']) {
    assert.ok(tables.some((table) => table.tablename === name), name);
  }
  console.log('Clean skeleton apply + previous-schema upgrade + repeated deploy: passed; 14 required entities present');
  const users = await Promise.all([0,1,2].map((index) => migration.user.create({ data: {
    email: `test-${index}@example.invalid`, emailKey: `test-${index}@example.invalid`, passwordHash: 'TEST_ONLY_UNUSABLE' } })));
  const secondClan = await migration.clan.create({ data: { name: 'Other clan', shortCode: 'OT', codeKey: 'OT' } });
  const [a,b] = users;
  assert.ok(a && b);
  await sqlReject(Prisma.sql`INSERT INTO minecraft_accounts(user_id,minecraft_uuid,nickname,state,verified_at) VALUES (${a.id}::uuid,${randomUUID()}::uuid,'PlayerA','REVOKED',now())`, '23514');
  await sqlReject(Prisma.sql`INSERT INTO sessions(user_id,client_kind,absolute_expires_at,idle_expires_at,auth_epoch) VALUES (${a.id}::uuid,'LAUNCHER',now()-interval '1 minute',now(),0)`, '23514');
  const minecraftUuid = randomUUID();
  await runtime.minecraft_accounts.create({ data: { user_id: a.id, minecraft_uuid: minecraftUuid, nickname: 'PlayerA', state: 'VERIFIED', verified_at: new Date() } });
  await sqlReject(Prisma.sql`INSERT INTO minecraft_accounts(user_id,minecraft_uuid,nickname,state,verified_at) VALUES (${a.id}::uuid,${randomUUID()}::uuid,'PlayerB','VERIFIED',now())`, '23505');
  await sqlReject(Prisma.sql`INSERT INTO minecraft_accounts(user_id,minecraft_uuid,nickname,state,verified_at) VALUES (${b.id}::uuid,${minecraftUuid}::uuid,'PlayerB','VERIFIED',now())`, '23505');
  await sqlReject(Prisma.sql`INSERT INTO player_statistics(user_id,server_id,playtime_seconds) VALUES (${a.id}::uuid,${randomUUID()}::uuid,-1)`, '23514');
  await sqlReject(Prisma.sql`INSERT INTO news(slug,title,body_markdown,author_id) VALUES ('unsafe','Title','<script>alert(1)</script>',${a.id}::uuid)`, '23514');
  await sqlReject(Prisma.sql`DELETE FROM users WHERE id=${a.id}::uuid`, '42501');

  const memberResults = await Promise.allSettled([clan.id, secondClan.id].map((id) => runtime.clan_members.create({ data: { clan_id: id, user_id: a.id } })));
  assert.equal(memberResults.filter((result) => result.status === 'fulfilled').length, 1);
  const memberFailure = memberResults.find((result) => result.status === 'rejected');
  assert.ok(memberFailure?.status === 'rejected' && memberFailure.reason.code === 'P2002');
  const member = await runtime.clan_members.findFirstOrThrow({ where: { user_id: a.id } });
  await sqlReject(Prisma.sql`INSERT INTO whitelist(user_id,kind,membership_id,reason) VALUES (${b.id}::uuid,'MEMBERSHIP',${member.id}::uuid,'Test mismatch')`, '23503');
  await sqlReject(Prisma.sql`UPDATE clan_members SET ended_at=now(),end_reason=NULL WHERE id=${member.id}::uuid`, '23514');
  await runtime.clan_members.update({ where: { id: member.id }, data: { ended_at: new Date(), end_reason: 'Test history' } });
  await runtime.clan_members.create({ data: { clan_id: secondClan.id, user_id: a.id } });
  assert.equal(await runtime.clan_members.count({ where: { user_id: a.id } }), 2, 'Historical membership retained');
  const applications = await Promise.allSettled([clan.id, secondClan.id].map((id) => runtime.clan_applications.create({ data: { clan_id: id, user_id: b.id, message: 'Test-only application message with at least fifty characters.' } })));
  assert.equal(applications.filter((result) => result.status === 'fulfilled').length, 1);
  const applicationFailure = applications.find((result) => result.status === 'rejected');
  assert.ok(applicationFailure?.status === 'rejected' && applicationFailure.reason.code === 'P2002');
  const application = await runtime.clan_applications.findFirstOrThrow({ where: { user_id: b.id } });
  await sqlReject(Prisma.sql`UPDATE clan_applications SET status='REJECTED',decided_by=${a.id}::uuid,decided_at=now(),reason=NULL WHERE id=${application.id}::uuid`, '23514');
  await sqlReject(Prisma.sql`INSERT INTO clan_members(user_id,clan_id,accepted_application_id) VALUES (${users[2]!.id}::uuid,${application.clan_id}::uuid,${application.id}::uuid)`, '23503');
  const role = await runtime.clan_roles.create({ data: { clan_id: clan.id, code: 'MANAGER', label: 'Manager' } });
  await sqlReject(Prisma.sql`INSERT INTO clan_manager_assignments(user_id,clan_id,role_id) VALUES (${a.id}::uuid,${secondClan.id}::uuid,${role.id}::uuid)`, '23503');
  const grant = await runtime.whitelist.create({ data: { user_id: a.id, kind: 'OWNER', reason: 'Test-only exception' } });
  await sqlReject(Prisma.sql`INSERT INTO whitelist(user_id,kind,reason) VALUES (${a.id}::uuid,'OWNER','Duplicate')`, '23505');
  await runtime.whitelist.update({ where: { id: grant.id }, data: { revoked_at: new Date() } });
  await runtime.whitelist.create({ data: { user_id: a.id, kind: 'OWNER', reason: 'New test-only exception' } });
  await sqlReject(Prisma.sql`INSERT INTO bans(user_id,scope,reason_private,message_public,issued_by,expires_at) VALUES (${a.id}::uuid,'GAME','Test reason','Test message',${b.id}::uuid,now()-interval '1 minute')`, '23514');
  const servers = await Promise.all(['A','B'].map((name) => runtime.game_servers.create({ data: { name } })));
  const server = servers[0]!;
  const boot = await runtime.server_boots.create({ data: { server_id: server.id, protocol_major: 1 } });
  const event = randomUUID();
  await sqlReject(Prisma.sql`INSERT INTO server_events(event_id,server_id,boot_id,kind,observed_at,schema_version,payload) VALUES (${event}::uuid,${servers[1]!.id}::uuid,${boot.id}::uuid,'HEARTBEAT',now(),1,'{}'::jsonb)`, '23503');
  const eventQuery = Prisma.sql`INSERT INTO server_events(event_id,server_id,boot_id,kind,observed_at,schema_version,payload) VALUES (${event}::uuid,${server.id}::uuid,${boot.id}::uuid,'HEARTBEAT',now(),1,'{}'::jsonb)`;
  await runtime.$executeRaw(eventQuery); await sqlReject(eventQuery, '23505');
  await sqlReject(Prisma.sql`INSERT INTO player_statistics(user_id,server_id,playtime_seconds) VALUES (${a.id}::uuid,${server.id}::uuid,-1)`, '23514');
  const release = await runtime.launcher_versions.create({ data: { channel: 'test', platform: 'windows-x86_64', version_label: 'test-only', release_sequence: 1n, min_api_major: 1, artifact_target: 'test/launcher.exe', sha256: 'a'.repeat(64), size: 1n } });
  await runtime.launcher_versions.update({ where: { id: release.id }, data: { published_at: new Date() } });
  await sqlReject(Prisma.sql`UPDATE launcher_versions SET sha256=${'b'.repeat(64)} WHERE id=${release.id}::uuid`, '23514');
  await sqlReject(Prisma.sql`INSERT INTO launcher_versions(channel,platform,version_label,release_sequence,min_api_major,artifact_target,sha256,size) VALUES ('test','windows-x86_64','bad-path',2,1,'https://example.invalid/file',${'a'.repeat(64)},1)`, '23514');
  console.log('Real PostgreSQL CHECK/FK/permissions and concurrent active membership/pending application: passed');

  const module = await Test.createTestingModule({ imports: [InfrastructureModule, DevelopmentModule, ClansModule, NewsModule] })
    .overrideProvider(DatabaseService).useValue({ client: runtime }).compile();
  // Importing an exported global provider through a test-only module avoids any external listeners.
  app = module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({ logger: false, genReqId: () => randomUUID() }));
  app.useGlobalPipes(new ValidationPipe({ transform: true, whitelist: true, forbidNonWhitelisted: true }));
  app.useGlobalFilters(new AllExceptionsFilter());
  await app.init(); await app.getHttpAdapter().getInstance().ready();
  const input = { name: 'Local CRUD test', short_code: 'CRUD', description: '', discord_url: null, enabled: true };
  const request = (method: 'GET' | 'POST' | 'PUT' | 'DELETE', url: string, payload?: object, version?: string) => app!.inject({ method, url,
    ...(payload ? { payload } : {}), ...(version !== undefined ? { headers: { 'if-match': `"${version}"` } } : {}) });
  const createdClan = await request('POST', '/development/v1/clans', input);
  assert.equal(createdClan.statusCode, 201);
  const dto = createdClan.json<{ id: string; version: string }>();
  assert.equal(dto.version, '0');
  const publicClan = await request('GET', `/api/v1/clans/${dto.id}`);
  assert.equal(publicClan.statusCode, 200); assert.equal(publicClan.json().member_count, 0);
  assert.equal((await request('GET', `/api/v1/clans/${clan.id}`)).statusCode, 404, 'Disabled clan hidden');
  assert.equal((await request('GET', `/development/v1/clans/${dto.id}`)).statusCode, 200);
  assert.equal((await request('POST', '/development/v1/clans', { ...input, system_role: 'OWNER' })).statusCode, 400);
  assert.equal((await request('POST', '/development/v1/clans', input)).statusCode, 409);
  assert.equal((await request('PUT', `/development/v1/clans/${dto.id}`, input)).statusCode, 428);
  const changes = await Promise.all([request('PUT', `/development/v1/clans/${dto.id}`, { ...input, name: 'Concurrent A' }, '0'), request('PUT', `/development/v1/clans/${dto.id}`, { ...input, name: 'Concurrent B' }, '0')]);
  assert.deepEqual(changes.map((result) => result.statusCode).sort(), [200,412]);
  const archived = await request('DELETE', `/development/v1/clans/${dto.id}`, undefined, '1');
  assert.equal(archived.statusCode, 200); assert.ok(archived.json().archived_at);
  assert.equal((await request('GET', `/api/v1/clans/${dto.id}`)).statusCode, 404, 'Archived clan hidden');
  assert.equal((await request('PUT', `/development/v1/clans/${dto.id}`, input, '2')).statusCode, 409);
  assert.equal((await request('DELETE', `/development/v1/clans/${member.clan_id}`, undefined, '0')).statusCode, 409);
  assert.equal((await request('GET', '/development/v1/clans/invalid')).statusCode, 400);
  const audits = await runtime.audit_logs.findMany({ where: { target_id: dto.id } });
  assert.equal(audits.length, 3, 'Only committed create/update/archive actions audited');
  assert.ok(audits.every((row) => row.actor_kind === 'DEVELOPMENT' && !JSON.stringify(row.after_redacted).includes('description')));
  await sqlReject(Prisma.sql`UPDATE audit_logs SET action='TAMPERED' WHERE target_id=${dto.id}::uuid`, '42501');
  await sqlReject(Prisma.sql`DELETE FROM audit_logs WHERE target_id=${dto.id}::uuid`, '42501');
  const article = await runtime.news.create({ data: { slug: 'test-article', title: 'Test article', body_markdown: '**Test**', author_id: a.id } });
  assert.equal((await request('GET', '/api/v1/news/test-article')).statusCode, 404, 'Draft hidden');
  await runtime.news.update({ where: { id: article.id }, data: { published_at: new Date(Date.now() + 60_000) } });
  assert.equal((await request('GET', '/api/v1/news/test-article')).statusCode, 404, 'Future publication hidden');
  await runtime.news.update({ where: { id: article.id }, data: { published_at: new Date(), version: 1n } });
  const publicNews = await request('GET', '/api/v1/news/test-article');
  assert.equal(publicNews.statusCode, 200); assert.equal(publicNews.json().version, '1');
  assert.ok(!('author_id' in publicNews.json()));
  await runtime.news.update({ where: { id: article.id }, data: { archived_at: new Date() } });
  assert.deepEqual((await request('GET', '/api/v1/news')).json(), { items: [], next_cursor: null });
  assert.equal((await request('GET', '/api/v1/news/test-article')).statusCode, 404, 'Archived article hidden');
  const stamp = new Date(Date.now() - 60_000);
  const pageRows = await Promise.all(['P1','P2','P3'].map((code) => runtime.clan.create({ data: { name: `Pagination ${code}`, shortCode: code, codeKey: code, enabled: true, createdAt: stamp } })));
  const seen: string[] = [];
  let cursor: string | null = null;
  let firstCursor = '';
  do {
    const page = await request('GET', `/api/v1/clans?limit=1${cursor ? `&cursor=${cursor}` : ''}`);
    assert.equal(page.statusCode, 200);
    const json = page.json<{ items: { id: string }[]; next_cursor: string | null }>();
    seen.push(...json.items.map((item) => item.id)); cursor = json.next_cursor;
    if (!firstCursor && cursor) firstCursor = cursor;
  } while (cursor);
  assert.deepEqual([...seen].sort(), pageRows.map((row) => row.id).sort());
  assert.equal(new Set(seen).size, 3, 'Timestamp ties neither skip nor duplicate');
  assert.equal((await request('GET', `/api/v1/clans?cursor=${firstCursor}x`)).statusCode, 400);
  assert.equal((await request('GET', `/api/v1/news?cursor=${firstCursor}`)).statusCode, 400, 'Cursor bound to resource');
  assert.equal((await request('GET', '/api/v1/clans?limit=101')).statusCode, 400);
  assert.equal((await request('GET', '/api/v1/clans?sort=password_hash')).statusCode, 400);
  const newsRows = await Promise.all(['page-a','page-b','page-c'].map((slug) => runtime.news.create({ data: {
    slug, title: slug, body_markdown: 'Test-only content', author_id: a.id, created_at: stamp, published_at: new Date() } })));
  const newsSeen: string[] = [];
  cursor = null;
  do {
    const page = await request('GET', `/api/v1/news?limit=1${cursor ? `&cursor=${cursor}` : ''}`);
    assert.equal(page.statusCode, 200);
    const json = page.json<{ items: { id: string }[]; next_cursor: string | null }>();
    newsSeen.push(...json.items.map((item) => item.id)); cursor = json.next_cursor;
  } while (cursor);
  assert.deepEqual(newsSeen, newsRows.map((row) => row.id).sort().reverse(), 'Descending timestamp ties paginated correctly');
  console.log('Public detail/draft/archive filtering and signed keyset pagination/timestamp ties/tampering: passed');
  console.log('Real DB HTTP CRUD/validation/optimistic concurrency/soft archive/atomic redacted append-only audit: passed');
  const previous = { flag: process.env.AOC_LOCAL_BASE_API, host: process.env.PUBLIC_HOST, mode: process.env.NODE_ENV };
  try {
    process.env.AOC_LOCAL_BASE_API = 'true'; process.env.NODE_ENV = 'development'; process.env.PUBLIC_HOST = '0.0.0.0';
    assert.throws(developmentApiEnabled);
    process.env.PUBLIC_HOST = '127.0.0.1'; process.env.NODE_ENV = 'production'; assert.throws(developmentApiEnabled);
    process.env.NODE_ENV = 'development'; assert.equal(developmentApiEnabled(), true);
    process.env.AOC_LOCAL_BASE_API = 'false'; assert.equal(developmentApiEnabled(), false);
  } finally {
    for (const [key,value] of [['AOC_LOCAL_BASE_API',previous.flag],['PUBLIC_HOST',previous.host],['NODE_ENV',previous.mode]]) {
      if (value === undefined) delete process.env[key!]; else process.env[key!] = value;
    }
  }
  console.log('Local API opt-in / loopback / production guards: passed');
} finally {
  await app?.close();
  await Promise.all([runtime.$disconnect(), migration.$disconnect()]);
  if (created) {
    await admin.$executeRawUnsafe(`DROP DATABASE "${databaseName}" WITH (FORCE)`);
    const remaining = await admin.$queryRaw<{ datname: string }[]>`SELECT datname FROM pg_database WHERE datname=${databaseName}`;
    assert.equal(remaining.length, 0, 'Own temporary database removed');
  }
  await admin.$disconnect();
  rmSync(temporary, { recursive: true, force: true });
}
