/** Disposable-database check of the world catalog import and public read model (run by test:gameplay:integration). */
import '../src/environment.js';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../src/generated/prisma/client.js';
import { buildCatalog, type WorldCatalog } from '../src/world-catalog/catalog.js';
import { applyCatalog, readWorldMap, readWorldPoints } from '../src/world-catalog/catalog-store.js';
import { loadCatalog } from '../src/world-catalog/load.js';
import { applyRelief, parseRelief, readWorldRelief } from '../src/world-catalog/relief.js';
import { readServerRegions } from '../src/world-catalog/server-regions.js';
import { assignSlot, listSlots, releaseSlot } from '../src/modules/community/region-assignment.js';

const url = process.env.DATABASE_URL, name = process.env.AOC_GAMEPLAY_TEST_DATABASE;
assert.ok(url && name); assert.match(name, /^animeoncraft_gameplay_test_[a-f0-9]{32}$/);
const parsed = new URL(url); assert.equal(parsed.hostname, '127.0.0.1'); assert.equal(parsed.pathname, `/${name}`);
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) }), rollback = new Error('World catalog fixture rollback');
const colors: Record<string, string> = { SCAM: 'E84A5F', NYA: 'F47DCB', AKUMA: 'A970FF', ZXC: '4DA8FF', YAKUZA: 'FFB347' };
try {
  await assert.rejects(db.$transaction(async tx => {
    for (const [code, color] of Object.entries(colors))
      await tx.$executeRaw`INSERT INTO clans(name,short_code,code_key,enabled,color) VALUES (${code},${code},${code},true,${color})
        ON CONFLICT (code_key) DO UPDATE SET color=EXCLUDED.color,enabled=true`;
    assert.equal(await readWorldMap(tx), null, 'no active world before import');
    const catalog = await loadCatalog(fileURLToPath(new URL('../../../minecraft/server/world-config/world.toml', import.meta.url)));

    const first = await applyCatalog(tx, catalog, { activate: true, reason: 'fixture' });
    assert.deepEqual([first.changed, first.version, first.regions], [true, '1', 5]);
    const again = await applyCatalog(tx, catalog, { activate: true, reason: 'fixture' });
    assert.deepEqual([again.changed, again.version], [false, '1'], 'unchanged import is a no-op');

    const map = (await readWorldMap(tx))!;
    assert.equal(map.regions.length, 5);
    for (const region of map.regions) assert.equal(region.color, colors[region.clan!.short_code], 'colour comes from the clan registry');
    assert.ok(!JSON.stringify(map).includes('state.'), 'public map exposes no technical region codes');
    const points = (await readWorldPoints(tx))!;
    assert.equal(points.items.filter(p => p.type === 'CLAN_CAPITAL').length, 5);
    // Server region catalog (protection in a states world): clan codes, stable revision that follows changes.
    const server = (await readServerRegions(tx))!;
    assert.deepEqual(server.regions.map(r => r.clan_code).sort(), ['AKUMA', 'NYA', 'SCAM', 'YAKUZA', 'ZXC']);
    assert.match(server.revision, /^[0-9a-f]{64}$/);
    assert.equal((await readServerRegions(tx))!.revision, server.revision, 'revision is deterministic');
    await tx.$executeRaw`UPDATE world_regions SET build_policy='OPEN' WHERE code='state.scam'`;
    assert.notEqual((await readServerRegions(tx))!.revision, server.revision, 'a policy change changes the revision');
    await tx.$executeRaw`UPDATE world_regions SET build_policy='MEMBERS' WHERE code='state.scam'`;
    assert.equal((await readServerRegions(tx))!.revision, server.revision);
    assert.ok(!JSON.stringify(points).includes('capital.scam'), 'public points expose no technical codes');

    // Owner state survives a re-import; a removed point is disabled with history; a removed region is archived.
    const extended: WorldCatalog = buildCatalog({ format_version: 1, world_id: catalog.world.id, display_name: catalog.world.display_name,
      layout: 'clan-world', regions: [{ code: 'frontier.wild.test', name: 'Свободные земли', kind: 'FRONTIER_WILD', polygon: [[5000, 5000], [6000, 5000], [6000, 6000], [5000, 6000]] }],
      points: [{ code: 'site.test', type: 'RESOURCE_SITE', name: 'Тестовая жила', region: 'frontier.wild.test', x: 5500, z: 5500, resource: { family: 'ARCANE', tier: 3 } }],
      markers: [{ code: 'event.arena', layer: 'events', kind: 'EVENT_ZONE', label: 'Арена', icon: 'boss', x: 0, z: 800 }] },
      JSON.parse(await (await import('node:fs/promises')).readFile(new URL('../../../minecraft/server/clan-world.json', import.meta.url), 'utf8')));
    const second = await applyCatalog(tx, extended, { activate: true, reason: 'fixture' });
    assert.deepEqual([second.changed, second.version], [true, '2']);
    const unchangedRegion = await tx.$queryRaw<Array<{ version: bigint }>>`SELECT version FROM world_regions WHERE code='state.scam'`;
    assert.equal(unchangedRegion[0]!.version, 1n, 'untouched regions keep their version');
    const scam = (await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE code_key='SCAM'`)[0]!.id;
    await tx.$executeRaw`UPDATE strategic_points SET owner_clan_id=${scam}::uuid,capture_state='CONTROLLED',lease_until=now()+interval '1 day' WHERE code='site.test'`;
    await applyCatalog(tx, { ...extended, markers: [] }, { activate: true, reason: 'fixture' });
    const kept = await tx.$queryRaw<Array<{ capture_state: string }>>`SELECT capture_state FROM strategic_points WHERE code='site.test'`;
    assert.equal(kept[0]!.capture_state, 'CONTROLLED', 'import never resets ownership of a capturable point');
    const sites = (await readWorldPoints(tx))!.items.filter(p => p.resource);
    assert.deepEqual(sites.map(p => [p.owner?.short_code, p.state, p.resource?.family, p.resource?.tier]), [['SCAM', 'CONTROLLED', 'ARCANE', 3]]);

    const third = await applyCatalog(tx, catalog, { activate: true, reason: 'fixture' });
    assert.deepEqual([third.archived_regions, third.disabled_points], [1, 1]);
    const history = await tx.$queryRaw<Array<{ event: string }>>`SELECT h.event FROM resource_site_history h JOIN strategic_points p ON p.id=h.point_id WHERE p.code='site.test' ORDER BY h.id`;
    assert.deepEqual(history.map(h => h.event), ['IMPORTED', 'DISABLED']);
    assert.equal((await readWorldMap(tx))!.regions.length, 5, 'archived regions disappear from the public map');
    assert.equal((await readWorldPoints(tx))!.items.some(p => p.name === 'Тестовая жила'), false);

    // Relief underlay (stage 6): bounds in the map, image only for the active world, identical image is a no-op.
    assert.equal((await readWorldMap(tx))!.relief, null);
    const png = (width: number, tag: number) => { const b = Buffer.alloc(40); Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b);
      b.writeUInt32BE(13, 8); b.write('IHDR', 12, 'latin1'); b.writeUInt32BE(width, 16); b.writeUInt32BE(width, 20); b[39] = tag; return b; };
    const meta = { format_version: 1, world_id: catalog.world.id, image: 'relief.png', x0: -2000, z0: -2000, width_blocks: 4000, height_blocks: 4000 };
    const r1 = await applyRelief(tx, parseRelief(meta, png(1024, 1)));
    assert.deepEqual([r1.changed, r1.revision], [true, '1']);
    assert.equal((await applyRelief(tx, parseRelief(meta, png(1024, 1)))).changed, false);
    assert.deepEqual((await readWorldMap(tx))!.relief, { revision: '1', x0: -2000, z0: -2000, width_blocks: 4000, height_blocks: 4000 });
    const r2 = await applyRelief(tx, parseRelief(meta, png(1024, 2)));
    assert.deepEqual([r2.changed, r2.revision], [true, '2']);
    const relief = (await readWorldRelief(tx))!;
    assert.deepEqual([relief.revision, relief.mime, relief.width_px, Buffer.from(relief.image_base64, 'base64').length], ['2', 'image/png', 1024, 40]);
    await assert.rejects(applyRelief(tx, parseRelief({ ...meta, world_id: 'no-such-world' }, png(1024, 1))), /not in the catalog/);
    await tx.$executeRaw`UPDATE world_catalog SET active=false`;
    assert.equal(await readWorldRelief(tx), null, 'no relief without an active world');
    await tx.$executeRaw`UPDATE world_catalog SET active=true WHERE world_id=${catalog.world.id}`;

    // Stage 8: a frontier slot of the states world goes to a new clan and back; re-imports keep the assignment.
    const states = await loadCatalog(fileURLToPath(new URL('../../../minecraft/server/world-config/world-states.toml', import.meta.url)));
    await applyCatalog(tx, states, { activate: true, reason: 'fixture' });
    const kitsune = (await tx.$queryRaw<Array<{ id: string }>>`INSERT INTO clans(name,short_code,code_key,enabled,color) VALUES ('Kitsune','KITSUNE','KITSUNE',true,'FF8A3D') RETURNING id`)[0]!.id;
    const free = (await listSlots(tx)).items;
    assert.equal(free.length, 10); assert.ok(free.every(s => s.status === 'RESERVED' && !s.clan));
    const slotD = free.find(s => s.code === 'frontier.slot.d')!;
    const command = (version: string) => ({ route: '/fixture', method: 'POST', key: undefined, match: `"${version}"`, body: { reason: 'fixture' }, request_id: 'fixture' });
    const audits: string[] = []; const audit = async (_tx: unknown, action: string) => { audits.push(action); };
    await assert.rejects(assignSlot(tx, slotD.id, kitsune, command('99'), audit), /Stale/);
    const scamId = (await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE code_key='SCAM'`)[0]!.id;
    await assert.rejects(assignSlot(tx, slotD.id, scamId, command(slotD.version), audit), /already holds/, 'a clan with a state gets no slot');
    const assigned = await assignSlot(tx, slotD.id, kitsune, command(slotD.version), audit);
    assert.deepEqual([assigned.status, assigned.clan?.short_code, assigned.assignment], ['ACTIVE', 'KITSUNE', 'ADMIN']);
    await assert.rejects(assignSlot(tx, slotD.id, kitsune, command(assigned.version), audit), /not free/);
    const catalogNow = (await readServerRegions(tx))!;
    assert.deepEqual(catalogNow.regions.find(r => r.code === 'frontier.slot.d')!.clan_code, 'KITSUNE', 'the game server sees the new owner');
    // A changed catalog import must not take the slot back.
    await applyCatalog(tx, { ...states, world: { ...states.world, display_name: 'Мир Одного Неба (2)' } }, { activate: true, reason: 'fixture' });
    assert.equal((await listSlots(tx)).items.find(s => s.code === 'frontier.slot.d')!.clan?.short_code, 'KITSUNE', 'import keeps admin assignments');
    const current = (await listSlots(tx)).items.find(s => s.code === 'frontier.slot.d')!;
    const released = await releaseSlot(tx, slotD.id, command(current.version), audit);
    assert.deepEqual([released.status, released.clan, released.assignment], ['RESERVED', null, 'CATALOG']);
    assert.equal((await readServerRegions(tx))!.regions.some(r => r.code === 'frontier.slot.d'), false, 'a released slot is wild land again');
    assert.deepEqual(audits, ['WORLD_SLOT_ASSIGNED', 'WORLD_SLOT_RELEASED']);
    await assert.rejects(tx.$executeRaw`UPDATE resource_site_history SET event='CAPTURED'`, /permission denied/, 'history is append-only for the runtime role');
    throw rollback;
  }, { timeout: 60000 }), error => error === rollback);
  console.log('World catalog SQL passed: idempotent import, versioning, registry colours, ownership preserved, disabled/archived history, no technical codes, append-only history, relief revisions, server region catalog, frontier slot assign/release. Fixture rolled back.');
} finally { await db.$disconnect(); }
