/** Strict parsers for the public world map API (World Design stage 2). Invalid data never reaches the UI. */
import type { ResourceFamily, WorldCaptureState, WorldClanRef, WorldEvent, WorldEventKind, WorldEventsResponse, WorldMapResponse, WorldPoint, WorldPointType, WorldPointsResponse, WorldRegionKind, WorldReliefBounds, WorldReliefResponse, WorldVec } from '@animeoncraft/contracts';
import { rememberClanColor } from './clan-brand.js';
import { PublicApiError } from './public-api.js';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, hex = /^[0-9A-F]{6}$/, LIMIT = 30_000_000;
const invalid = (): never => { throw new PublicApiError('INVALID_DATA'); };
const object = (value: unknown) => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : invalid();
const array = (value: unknown, max: number) => Array.isArray(value) && value.length <= max ? value : invalid();
const text = (value: unknown, max: number, min = 1) => typeof value === 'string' && value.length >= min && value.length <= max ? value : invalid();
const id = (value: unknown) => { const v = text(value, 36); return uuid.test(v) ? v : invalid(); };
const int = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && Math.abs(value) <= LIMIT ? value : invalid();
const color = (value: unknown) => value === null ? null : (() => { const v = text(value, 6); return hex.test(v) ? v : invalid(); })();
const date = (value: unknown) => value === null ? null : (() => { const v = text(value, 64); return Number.isFinite(Date.parse(v)) ? v : invalid(); })();
function oneOf<T extends string>(value: unknown, options: readonly T[]): T { return options.includes(value as T) ? value as T : invalid(); }
const vec = (value: unknown): WorldVec => { const pair = array(value, 2); return pair.length === 2 ? [int(pair[0]), int(pair[1])] : invalid(); };
const version = (value: unknown) => { const v = text(value, 20); return /^[1-9][0-9]*$/.test(v) ? v : invalid(); };
function clan(value: unknown): WorldClanRef | null {
  if (value === null) return null;
  const row = object(value), code = text(row.short_code, 12, 2);
  if (!/^[A-Z0-9]+$/.test(code)) return invalid();
  const ref = { id: id(row.id), name: text(row.name, 64), short_code: code, color: color(row.color) };
  rememberClanColor(ref.short_code, ref.color);
  return ref;
}

export const REGION_KINDS = ['CORE', 'STATE', 'BORDERLAND', 'RIM', 'FRONTIER_SLOT', 'FRONTIER_WILD', 'SEA'] as const satisfies readonly WorldRegionKind[];
export const POINT_TYPES = ['CAPITAL', 'CLAN_CAPITAL', 'FORT', 'MINE', 'VILLAGE', 'PORT', 'BRIDGE', 'PASS', 'WATCHTOWER', 'RUINS', 'RESOURCE_SITE', 'OUTPOST', 'EVENT_ZONE', 'BOSS_LAIR'] as const satisfies readonly WorldPointType[];
export const CAPTURE_STATES = ['NEUTRAL', 'CONTESTED', 'CAPTURING', 'CONTROLLED', 'PROTECTED', 'AVAILABLE'] as const satisfies readonly WorldCaptureState[];
export const FAMILIES = ['FERROUS', 'NONFERROUS', 'PRECIOUS', 'ARCANE', 'TECH', 'TIMBER', 'AGRARIAN', 'ALCHEMY', 'MARINE'] as const satisfies readonly ResourceFamily[];

export function parseWorldMap(value: unknown): WorldMapResponse {
  const row = object(value), world = object(row.world);
  return {
    world: { display_name: text(world.display_name, 80), version: version(world.version), border_half: world.border_half === null ? null : int(world.border_half) },
    regions: array(row.regions, 512).map(value => {
      const region = object(value);
      const polygon = array(region.polygon, 4096).map(vec);
      if (polygon.length < 3) return invalid();
      return { id: id(region.id), display_name: text(region.display_name, 80), kind: oneOf(region.kind, REGION_KINDS), clan: clan(region.clan),
        color: color(region.color), polygon, pvp_policy: oneOf(region.pvp_policy, ['OFF', 'WAR_ONLY', 'ON'] as const),
        build_policy: oneOf(region.build_policy, ['MEMBERS', 'OPEN', 'NONE', 'OWNER_ZONE'] as const) };
    }),
    connections: array(row.connections, 4096).map(value => {
      const connection = object(value), polyline = array(connection.polyline, 4096).map(vec);
      if (polyline.length < 2) return invalid();
      return { id: id(connection.id), kind: oneOf(connection.kind, ['ROAD', 'BRIDGE', 'PASS', 'PORT', 'RAIL'] as const), polyline };
    }),
    markers: array(row.markers, 4096).map(value => {
      const marker = object(value);
      return { id: id(marker.id), layer: oneOf(marker.layer, ['capitals', 'points', 'sites', 'roads', 'events', 'labels'] as const),
        kind: text(marker.kind, 16), label: text(marker.label, 80), icon: text(marker.icon, 32), x: int(marker.x), z: int(marker.z) };
    }),
    // Absent on servers before stage 6: no underlay.
    relief: row.relief === undefined || row.relief === null ? null : reliefBounds(row.relief),
  };
}

function reliefBounds(value: unknown): WorldReliefBounds {
  const row = object(value), size = (v: unknown) => { const n = int(v); return n >= 256 && n <= 60000 ? n : invalid(); };
  return { revision: version(row.revision), x0: int(row.x0), z0: int(row.z0), width_blocks: size(row.width_blocks), height_blocks: size(row.height_blocks) };
}

/** Relief image: only JPEG/PNG base64 whose magic bytes match the declared type, so the data: URL cannot carry anything else. */
export function parseWorldRelief(value: unknown): WorldReliefResponse {
  const row = object(value), bounds = reliefBounds(value), mime = oneOf(row.mime, ['image/jpeg', 'image/png'] as const);
  const pixels = (v: unknown) => { const n = int(v); return n >= 64 && n <= 8192 ? n : invalid(); };
  const image = text(row.image_base64, 4194304, 4), sha256 = text(row.sha256, 64, 64);
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(image) || !/^[0-9a-f]{64}$/.test(sha256)) return invalid();
  if (!(mime === 'image/jpeg' ? image.startsWith('/9j/') : image.startsWith('iVBORw0KGgo'))) return invalid();
  return { ...bounds, mime, width_px: pixels(row.width_px), height_px: pixels(row.height_px), sha256, image_base64: image };
}

export function parseWorldPoints(value: unknown): WorldPointsResponse {
  const row = object(value);
  return { version: version(row.version), items: array(row.items, 4096).map((value): WorldPoint => {
    const point = object(value), resource = point.resource === null ? null : object(point.resource);
    const tier = resource ? int(resource.tier) : 0;
    if (resource && (tier < 1 || tier > 4)) return invalid();
    if (typeof point.capturable !== 'boolean') return invalid();
    return { id: id(point.id), name: text(point.name, 80), type: oneOf(point.type, POINT_TYPES), x: int(point.x), z: int(point.z), radius: int(point.radius),
      capturable: point.capturable, region_id: point.region_id === null ? null : id(point.region_id), owner: clan(point.owner),
      state: oneOf(point.state, CAPTURE_STATES), protection_until: date(point.protection_until), lease_until: date(point.lease_until),
      map_icon: text(point.map_icon, 32),
      resource: resource ? { family: oneOf(resource.family, FAMILIES), tier, kind: oneOf(resource.kind, ['PIT', 'CACHE'] as const), next_refresh: date(resource.next_refresh) } : null,
      event: captureEvent(point.event) };
  }) };
}

/** Running capture of a point; absent on servers before stage 4. */
function captureEvent(value: unknown): WorldPoint['event'] {
  if (value === undefined || value === null) return null;
  const row = object(value), declared = clan(row.declared_by);
  if (!declared) return invalid();
  return { phase: oneOf(row.phase, ['PREPARING', 'ACTIVE', 'OVERTIME'] as const), active_start: date(row.active_start) ?? invalid(),
    active_end: date(row.active_end) ?? invalid(), declared_by: declared };
}

export const EVENT_KINDS = ['DECLARED', 'CAPTURED', 'RENEWED', 'DEFENDED', 'NEUTRALIZED', 'RELEASED', 'ADMIN_TRANSFER', 'ADMIN_RESET'] as const satisfies readonly WorldEventKind[];
export function parseWorldEvents(value: unknown): WorldEventsResponse {
  const row = object(value);
  return { items: array(row.items, 50).map((value): WorldEvent => {
    const item = object(value), point = object(item.point);
    const seq = text(item.id, 20);
    if (!/^[1-9][0-9]*$/.test(seq)) return invalid();
    return { id: seq, at: date(item.at) ?? invalid(), kind: oneOf(item.kind, EVENT_KINDS), point: { id: id(point.id), name: text(point.name, 80) }, clan: clan(item.clan) };
  }) };
}
