import { describe, expect, it } from 'vitest';
import { publicPath } from './public-api.js';
import { parseWorldEvents, parseWorldMap, parseWorldPoints, parseWorldRelief } from './world-api.js';
import { remaining } from './world-map.js';
import { clanColor } from './clan-brand.js';

const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const map = () => ({
  world: { display_name: 'Клановый мир', version: '3', border_half: null },
  regions: [{ id: uuid(1), display_name: 'Земли KITSUNE', kind: 'STATE', clan: { id: uuid(2), name: 'Kitsune', short_code: 'KITSUNE', color: 'FF8A3D' }, color: 'FF8A3D',
    polygon: [[0, 0], [768, 0], [768, 768], [0, 768]], pvp_policy: 'WAR_ONLY', build_policy: 'MEMBERS' }],
  connections: [{ id: uuid(3), kind: 'ROAD', polyline: [[0, 0], [100, 100]] }],
  markers: [{ id: uuid(4), layer: 'events', kind: 'EVENT_ZONE', label: 'Арена', icon: 'boss', x: 0, z: 800 }],
});
const point = (patch: Record<string, unknown> = {}) => ({ id: uuid(5), name: 'Жила', type: 'RESOURCE_SITE', x: 10, z: -20, radius: 24, capturable: true, region_id: null,
  owner: null, state: 'NEUTRAL', protection_until: null, lease_until: null, map_icon: 'resource-site',
  resource: { family: 'ARCANE', tier: 3, kind: 'PIT', next_refresh: null }, ...patch });

describe('world map API', () => {
  it('uses fixed public routes without keys or cursors', () => {
    expect(publicPath({ resource: 'world_map' })).toBe('/api/v1/world/map');
    expect(publicPath({ resource: 'world_points' })).toBe('/api/v1/world/points');
    expect(() => publicPath({ resource: 'world_map', key: uuid(1) })).toThrow();
    expect(() => publicPath({ resource: 'world_points', cursor: 'x' })).toThrow();
  });
  it('parses regions and registers clan colours from the registry', () => {
    const parsed = parseWorldMap(map());
    expect(parsed.regions[0]!.clan?.short_code).toBe('KITSUNE');
    expect(clanColor('KITSUNE')).toBe('#ff8a3d');
  });
  it('reads the relief bounds and accepts servers without them', () => {
    expect(parseWorldMap(map()).relief).toBeNull();
    const bounds = { revision: '2', x0: -5500, z0: -5500, width_blocks: 11001, height_blocks: 11001 };
    expect(parseWorldMap({ ...map(), relief: bounds }).relief).toEqual(bounds);
    expect(() => parseWorldMap({ ...map(), relief: { ...bounds, width_blocks: 10 } })).toThrow();
    expect(publicPath({ resource: 'world_relief' })).toBe('/api/v1/world/relief');
  });
  it('accepts only JPEG/PNG relief images whose bytes match the declared type', () => {
    const relief = { revision: '1', x0: -5500, z0: -5500, width_blocks: 11001, height_blocks: 11001, mime: 'image/jpeg', width_px: 2048, height_px: 2048,
      sha256: 'a'.repeat(64), image_base64: '/9j/4AAQSkZJRg==' };
    expect(parseWorldRelief(relief).mime).toBe('image/jpeg');
    expect(parseWorldRelief({ ...relief, mime: 'image/png', image_base64: 'iVBORw0KGgoAAAA=' }).mime).toBe('image/png');
    for (const patch of [{ mime: 'image/svg+xml' }, { mime: 'image/png' }, { image_base64: 'PHN2Zz4=' }, { image_base64: '/9j/<script>' },
      { width_px: 20000 }, { sha256: 'xyz' }, { revision: '0' }])
      expect(() => parseWorldRelief({ ...relief, ...patch })).toThrow();
  });
  it('rejects malformed map data', () => {
    for (const patch of [{ kind: 'EMPIRE' }, { polygon: [[0, 0], [1, 1]] }, { color: '#fff' }, { polygon: [[0, 0], [1, 1], [0.5, 2]] }, { clan: { id: 'x', name: 'A', short_code: 'AA', color: null } }])
      expect(() => parseWorldMap({ ...map(), regions: [{ ...map().regions[0], ...patch }] })).toThrow();
    expect(() => parseWorldMap({ ...map(), world: { display_name: 'X', version: '0', border_half: null } })).toThrow();
  });
  it('parses points and rejects unknown states, families and tiers', () => {
    expect(parseWorldPoints({ version: '1', items: [point()] }).items[0]!.resource?.family).toBe('ARCANE');
    for (const patch of [{ state: 'OWNED' }, { type: 'CASTLE' }, { resource: { family: 'URANIUM', tier: 1, kind: 'PIT', next_refresh: null } },
      { resource: { family: 'ARCANE', tier: 5, kind: 'PIT', next_refresh: null } }, { capturable: 'yes' }, { lease_until: 'soon' }])
      expect(() => parseWorldPoints({ version: '1', items: [point(patch)] })).toThrow();
  });
  it('parses the running capture of a point and the public event feed', () => {
    const scam = { id: uuid(7), name: 'SCAM', short_code: 'SCAM', color: 'E84A5F' };
    const running = { phase: 'ACTIVE', active_start: '2026-10-20T10:05:00.000Z', active_end: '2026-10-20T10:20:00.000Z', declared_by: scam };
    expect(parseWorldPoints({ version: '1', items: [point({ state: 'CAPTURING', event: running })] }).items[0]!.event?.declared_by.short_code).toBe('SCAM');
    expect(parseWorldPoints({ version: '1', items: [point()] }).items[0]!.event).toBeNull();
    for (const event of [{ ...running, phase: 'LOOTING' }, { ...running, declared_by: null }, { ...running, active_end: 'later' }])
      expect(() => parseWorldPoints({ version: '1', items: [point({ event })] })).toThrow();
    const item = { id: '12', at: '2026-10-20T10:21:00.000Z', kind: 'CAPTURED', point: { id: uuid(5), name: 'Жила' }, clan: scam };
    expect(parseWorldEvents({ items: [item] }).items[0]!.clan?.short_code).toBe('SCAM');
    expect(publicPath({ resource: 'world_events' })).toBe('/api/v1/world/events');
    for (const patch of [{ kind: 'VOIDED' }, { id: '0' }, { point: { id: 'site.ring.36', name: 'Жила' } }, { at: 'yesterday' }])
      expect(() => parseWorldEvents({ items: [{ ...item, ...patch }] })).toThrow();
  });
  it('formats remaining protection and lease time', () => {
    const now = Date.parse('2026-10-20T10:00:00Z');
    expect(remaining('2026-10-20T13:12:00Z', now)).toBe('3 ч 12 мин');
    expect(remaining('2026-10-20T10:05:00Z', now)).toBe('5 мин');
    expect(remaining('2026-10-22T12:00:00Z', now)).toBe('2 д 2 ч');
    expect(remaining('2026-10-20T09:00:00Z', now)).toBeNull();
    expect(remaining(null, now)).toBeNull();
  });
});
