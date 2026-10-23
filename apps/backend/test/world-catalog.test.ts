import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildCatalog, catalogDigest, CatalogError, insidePolygon, polygonsOverlap, territoryRectangle } from '../src/world-catalog/catalog.js';
import { loadCatalog } from '../src/world-catalog/load.js';

const profilePath = new URL('../../../minecraft/server/world-config/world.toml', import.meta.url);
const clanWorld = async () => JSON.parse(await readFile(new URL('../../../minecraft/server/clan-world.json', import.meta.url), 'utf8')) as Record<string, unknown>;
const base = { format_version: 1, world_id: 'test-world', display_name: 'Test' };
const square = (x: number, z: number, size = 100): [number, number][] => [[x, z], [x + size, z], [x + size, z + size], [x, z + size]];

describe('world catalog', () => {
  it('derives the current world exactly like the game server territories', async () => {
    const catalog = await loadCatalog(fileURLToPath(profilePath));
    expect(catalog.world.id).toBe('clan-world-20261016');
    const states = catalog.regions.filter(r => r.kind === 'STATE');
    expect(states.map(r => r.clan_code)).toEqual(['SCAM', 'NYA', 'AKUMA', 'ZXC', 'YAKUZA']);
    // docs/operations/CLAN_MAP.md: SCAM X −768…−1, Z 640…1407 (half-open upper bound).
    expect(states[0]!.polygon).toEqual([[-768, 640], [0, 640], [0, 1408], [-768, 1408]]);
    expect(catalog.points.filter(p => p.type === 'CLAN_CAPITAL')).toHaveLength(5);
    expect(catalog.points.find(p => p.code === 'spawn.common')).toMatchObject({ type: 'CAPITAL', x: -257, z: 1535, region_code: null });
    expect(catalogDigest(catalog)).toMatch(/^[0-9a-f]{64}$/);
  });

  it('accepts a sixth clan from configuration alone', async () => {
    const world = await clanWorld();
    (world.clans as Record<string, unknown>).KITSUNE = { x: 6000, y: 0, z: -6000 };
    ((world.territories as Record<string, Record<string, string>>).colors!).KITSUNE = 'FF8A3D';
    const catalog = buildCatalog({ ...base, layout: 'clan-world' }, world);
    expect(catalog.regions.map(r => r.clan_code)).toContain('KITSUNE');
  });

  it('keeps negative coordinates chunk aligned like Math.floorDiv', () => {
    expect(territoryRectangle({ x: -1, z: -17 }, 64)).toEqual([[-48, -64], [16, -64], [16, 0], [-48, 0]]);
  });

  it('rejects overlapping regions, capturable points inside states and dangling references', () => {
    const state = (code: string, clan: string, x: number) => ({ code, name: code, kind: 'STATE', clan, polygon: square(x, 0) });
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0), state('state.b', 'BBB', 50)] })).toThrow(/overlap/);
    expect(buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0), state('state.b', 'BBB', 100)] }).regions).toHaveLength(2);
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0)],
      points: [{ code: 'fort.a', type: 'FORT', name: 'Fort', region: 'state.a', x: 10, z: 10 }] })).toThrow(/never inside a state/);
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0)],
      points: [{ code: 'fort.a', type: 'FORT', name: 'Fort', x: 10, z: 10 }] })).toThrow(/inside a state/);
    expect(() => buildCatalog({ ...base, points: [{ code: 'x.a', type: 'FORT', name: 'F', region: 'nowhere', x: 0, z: 0 }] })).toThrow(/unknown region/);
    expect(() => buildCatalog({ ...base, regions: [{ code: 'state.a', name: 'A', kind: 'STATE', polygon: square(0, 0) }] })).toThrow(/needs a clan/);
    expect(() => buildCatalog({ ...base, regions: [state('state.a', 'AAA', 0), state('state.b', 'AAA', 200)] })).toThrow(/two active regions/);
    expect(() => buildCatalog({ ...base, points: [{ code: 'a.a', type: 'FORT', name: 'A', x: 0, z: 0 }, { code: 'a.a', type: 'FORT', name: 'B', x: 0, z: 0 }] })).toThrow(/duplicate/);
  });

  it('requires a resource table exactly for resource sites', () => {
    const site = { code: 'site.w1.t2', type: 'RESOURCE_SITE', name: 'Жила', x: 0, z: 0 };
    expect(() => buildCatalog({ ...base, points: [site] })).toThrow(CatalogError);
    const ok = buildCatalog({ ...base, points: [{ ...site, resource: { family: 'FERROUS', tier: 2 } }] });
    expect(ok.points[0]!.resource).toMatchObject({ type: 'FERROUS', tier: 2, kind: 'PIT', respawn_interval_seconds: 14400, capture_cooldown_seconds: 7200 });
    expect(ok.points[0]!.capturable).toBe(true);
    expect(() => buildCatalog({ ...base, points: [{ ...site, resource: { family: 'URANIUM', tier: 2 } }] })).toThrow(/family/);
  });

  it('geometry helpers treat touching edges as non-overlapping and containment as overlap', () => {
    expect(polygonsOverlap(square(0, 0), square(100, 0))).toBe(false);
    expect(polygonsOverlap(square(0, 0), square(10, 10, 20))).toBe(true);
    expect(polygonsOverlap(square(0, 0), square(0, 0))).toBe(true);
    expect(insidePolygon(square(0, 0), 99, 99)).toBe(true);
    expect(insidePolygon(square(0, 0), 100, 0)).toBe(false);
  });
});
