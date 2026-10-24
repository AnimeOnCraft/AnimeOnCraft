/**
 * World catalog: the validated, data-only description of a world (regions, strategic points, resource
 * sites, connections, markers). Built from the blueprint TOML in minecraft/server/world-config/ and, for
 * the current rectangle-territory world, from minecraft/server/clan-world.json. No clan is named in code.
 * See docs/WORLD_DESIGN.md and docs/operations/WORLD_MODEL_STAGE2.md.
 */
import { createHash } from 'node:crypto';
import { parseRules, type CaptureRules } from '../capture/rules.js';

export type Vec2 = [number, number];
export type RegionKind = 'CORE' | 'MARKET' | 'STATE' | 'BORDERLAND' | 'RIM' | 'FRONTIER_SLOT' | 'FRONTIER_WILD' | 'SEA';
export type PointType = 'CAPITAL' | 'CLAN_CAPITAL' | 'FORT' | 'MINE' | 'VILLAGE' | 'PORT' | 'BRIDGE' | 'PASS'
  | 'WATCHTOWER' | 'RUINS' | 'RESOURCE_SITE' | 'OUTPOST' | 'EVENT_ZONE' | 'BOSS_LAIR';
export type Family = 'FERROUS' | 'NONFERROUS' | 'PRECIOUS' | 'ARCANE' | 'TECH' | 'TIMBER' | 'AGRARIAN' | 'ALCHEMY' | 'MARINE';

export interface CatalogRegion {
  code: string; display_name: string; kind: RegionKind; clan_code: string | null;
  status: 'RESERVED' | 'ACTIVE' | 'ARCHIVED';
  pvp_policy: 'OFF' | 'WAR_ONLY' | 'ON'; build_policy: 'MEMBERS' | 'OPEN' | 'NONE' | 'OWNER_ZONE';
  capital: { x: number; y: number | null; z: number } | null; spawn: { x: number; y: number | null; z: number } | null;
  polygon: Vec2[]; natural_profile: Record<string, unknown>; resource_profile: Record<string, unknown>; color_override: string | null;
}
export interface CatalogResource {
  type: Family; tier: 1 | 2 | 3 | 4; kind: 'PIT' | 'CACHE';
  respawn_interval_seconds: number; capture_cooldown_seconds: number; yield: Record<string, unknown>; pit_template: string | null;
}
export interface CatalogPoint {
  code: string; type: PointType; name: string; region_code: string | null;
  x: number; y: number | null; z: number; radius: number; capturable: boolean; map_icon: string; resource: CatalogResource | null;
}
export interface CatalogConnection { code: string; from: string; to: string; kind: 'ROAD' | 'BRIDGE' | 'PASS' | 'PORT' | 'RAIL'; polyline: Vec2[] }
export interface CatalogMarker {
  code: string; layer: 'capitals' | 'points' | 'sites' | 'roads' | 'events' | 'labels'; kind: string;
  x: number; z: number; label: string; icon: string; visibility: 'PUBLIC' | 'CLAN' | 'OWNER';
}
export interface WorldCatalog {
  world: { id: string; display_name: string; border_half: number | null };
  regions: CatalogRegion[]; points: CatalogPoint[]; connections: CatalogConnection[]; markers: CatalogMarker[];
  /** capture-rules.toml referenced by the profile; null when the world has no capture engine. */
  capture_rules: CaptureRules | null;
}

const CODE = /^[a-z0-9][a-z0-9._-]{1,63}$/, CLAN = /^[A-Z0-9]{2,12}$/, ICON = /^[a-z0-9_-]{1,32}$/, HEX = /^[0-9A-F]{6}$/;
const LIMIT = 30_000_000;
const REGION_KINDS: RegionKind[] = ['CORE', 'MARKET', 'STATE', 'BORDERLAND', 'RIM', 'FRONTIER_SLOT', 'FRONTIER_WILD', 'SEA'];
const POINT_TYPES: PointType[] = ['CAPITAL', 'CLAN_CAPITAL', 'FORT', 'MINE', 'VILLAGE', 'PORT', 'BRIDGE', 'PASS', 'WATCHTOWER',
  'RUINS', 'RESOURCE_SITE', 'OUTPOST', 'EVENT_ZONE', 'BOSS_LAIR'];
const FAMILIES: Family[] = ['FERROUS', 'NONFERROUS', 'PRECIOUS', 'ARCANE', 'TECH', 'TIMBER', 'AGRARIAN', 'ALCHEMY', 'MARINE'];
const DEFAULT_RESPAWN: Record<number, number> = { 1: 7200, 2: 14400, 3: 21600, 4: 43200 };

export class CatalogError extends Error {}
const fail = (message: string): never => { throw new CatalogError(message); };
type Raw = Record<string, unknown>;
const obj = (value: unknown, where: string): Raw =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Raw : fail(`${where}: expected a table`);
const list = (value: unknown, where: string): unknown[] => value === undefined ? [] : Array.isArray(value) ? value : fail(`${where}: expected an array`);
const str = (value: unknown, where: string, max = 80): string => {
  if (typeof value !== 'string' || !value.trim() || value.length > max) fail(`${where}: expected text up to ${max}`);
  return (value as string).trim();
};
const int = (value: unknown, where: string, min = -LIMIT, max = LIMIT): number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max ? value : fail(`${where}: expected integer ${min}..${max}`);
const optInt = (value: unknown, where: string, min?: number, max?: number) => value === undefined || value === null ? null : int(value, where, min, max);
function oneOf<T extends string>(value: unknown, options: readonly T[], where: string, fallback?: T): T {
  if (value === undefined && fallback !== undefined) return fallback;
  return options.includes(value as T) ? value as T : fail(`${where}: expected one of ${options.join(', ')}`);
}
const code = (value: unknown, where: string) => { const text = str(value, where, 64); return CODE.test(text) ? text : fail(`${where}: invalid code`); };
const vec = (value: unknown, where: string): Vec2 => {
  if (!Array.isArray(value) || value.length !== 2) fail(`${where}: expected [x, z]`);
  const pair = value as unknown[];
  return [int(pair[0], where), int(pair[1], where)];
};
const point3 = (value: unknown, where: string) => {
  if (value === undefined || value === null) return null;
  const row = obj(value, where);
  return { x: int(row.x, `${where}.x`), y: optInt(row.y, `${where}.y`, -64, 320), z: int(row.z, `${where}.z`) };
};
const profile = (value: unknown, where: string) => {
  if (value === undefined) return {};
  const row = obj(value, where);
  if (JSON.stringify(row).length > 8192) fail(`${where}: profile too large`);
  return row;
};
function resourceOutput(value: unknown, kind: 'PIT' | 'CACHE', where: string): Record<string, unknown> {
  const output=profile(value,where);
  if(Object.keys(output).length===0)return output; // legacy profiles
  if(Object.keys(output).some(key=>!['version','label','placement','limit','blocks','seed','items'].includes(key)))fail(`${where}: unknown output setting`);
  if(output.version!==2)fail(`${where}: expected output version 2`);
  str(output.label,`${where}.label`,120);
  const placement=oneOf(output.placement,['UNDERGROUND','SURFACE','CACHE'] as const,`${where}.placement`);
  if((kind==='CACHE')!==(placement==='CACHE'))fail(`${where}: output placement must match site kind`);
  if(output.limit!==undefined)int(output.limit,`${where}.limit`,1,384);
  const id=(v:unknown)=>typeof v==='string'&&/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(v)&&v.length<=120;
  const pairs=list(kind==='CACHE'?output.items:output.seed,where);
  if(pairs.length<1||pairs.length>32)fail(`${where}: expected 1..32 resource choices`);
  for(const pair of pairs)if(!Array.isArray(pair)||pair.length!==2||!id(pair[0])||!Number.isInteger(pair[1])||pair[1]<1||pair[1]>384)fail(`${where}: invalid resource choice`);
  if(kind==='PIT') {
    const blocks=list(output.blocks,where);
    if(blocks.length<1||blocks.length>64||!blocks.every(id))fail(`${where}: invalid resource blocks`);
    if(pairs.some(pair=>!blocks.includes((pair as unknown[])[0])))fail(`${where}: seed must be in resource blocks`);
  }
  return output;
}

/** Square territory exactly as the NeoForge mod derives it (TerritoryLayout.read): chunk-aligned centre, half-open bounds. */
export function territoryRectangle(center: { x: number; z: number }, width: number): Vec2[] {
  const minX = Math.floor(center.x / 16) * 16 - width / 2, minZ = Math.floor(center.z / 16) * 16 - width / 2;
  return [[minX, minZ], [minX + width, minZ], [minX + width, minZ + width], [minX, minZ + width]];
}

/**
 * Regions and clan capitals derived from clan-world.json (the file the game server already uses), so the
 * map can never disagree with in-game protection for the current world.
 */
function fromClanWorld(clanWorld: Raw, defaults: Raw): { regions: CatalogRegion[]; points: CatalogPoint[] } {
  if (clanWorld.format_version !== 1) fail('clan-world: unsupported format_version');
  const clans = obj(clanWorld.clans, 'clan-world.clans'), territories = obj(clanWorld.territories, 'clan-world.territories');
  const colors = obj(territories.colors, 'clan-world.territories.colors');
  const width = int(territories.width_blocks, 'clan-world.territories.width_blocks', 64, 4096);
  if (width % 32 !== 0) fail('clan-world: territory width must be a multiple of 32');
  const regions: CatalogRegion[] = [], points: CatalogPoint[] = [];
  for (const [clan, raw] of Object.entries(clans)) {
    if (!CLAN.test(clan) || clan === 'COMMON') fail(`clan-world: invalid clan code ${clan}`);
    const spawn = obj(raw, `clan-world.clans.${clan}`);
    const x = int(spawn.x, `${clan}.x`, -100000, 100000), z = int(spawn.z, `${clan}.z`, -100000, 100000);
    const y = spawn.y === undefined || spawn.y === 0 ? null : int(spawn.y, `${clan}.y`, 1, 319);
    const color = str(colors[clan], `clan-world colour ${clan}`, 6).toUpperCase();
    if (!HEX.test(color)) fail(`clan-world: invalid colour for ${clan}`);
    const regionCode = `state.${clan.toLowerCase()}`;
    regions.push({ code: regionCode, display_name: `Земли ${clan}`, kind: 'STATE', clan_code: clan, status: 'ACTIVE',
      pvp_policy: oneOf(defaults.pvp_policy, ['OFF', 'WAR_ONLY', 'ON'] as const, 'region_defaults.pvp_policy', 'WAR_ONLY'),
      build_policy: oneOf(defaults.build_policy, ['MEMBERS', 'OPEN', 'NONE', 'OWNER_ZONE'] as const, 'region_defaults.build_policy', 'MEMBERS'),
      capital: { x, y, z }, spawn: { x, y, z }, polygon: territoryRectangle({ x, z }, width),
      natural_profile: {}, resource_profile: {}, color_override: null });
    points.push({ code: `capital.${clan.toLowerCase()}`, type: 'CLAN_CAPITAL', name: `Замок ${clan}`, region_code: regionCode,
      x, y, z, radius: 20, capturable: false, map_icon: 'castle', resource: null });
  }
  if (clanWorld.common !== undefined) {
    const common = obj(clanWorld.common, 'clan-world.common');
    points.push({ code: 'spawn.common', type: 'CAPITAL', name: 'Общий спавн', region_code: null,
      x: int(common.x, 'common.x'), y: optInt(common.y, 'common.y', -64, 320), z: int(common.z, 'common.z'),
      radius: 16, capturable: false, map_icon: 'spawn', resource: null });
  }
  return { regions, points };
}

function parseRegion(raw: unknown, index: number): CatalogRegion {
  const row = obj(raw, `regions[${index}]`), where = `regions[${index}]`;
  const kind = oneOf(row.kind, REGION_KINDS, `${where}.kind`);
  const clan = row.clan === undefined || row.clan === null ? null : str(row.clan, `${where}.clan`, 12);
  if (clan !== null && !CLAN.test(clan)) fail(`${where}.clan: invalid code`);
  const color = row.color === undefined ? null : str(row.color, `${where}.color`, 6).toUpperCase();
  if (color !== null && !HEX.test(color)) fail(`${where}.color: expected RRGGBB`);
  return { code: code(row.code, `${where}.code`), display_name: str(row.name, `${where}.name`), kind, clan_code: clan,
    status: oneOf(row.status, ['RESERVED', 'ACTIVE', 'ARCHIVED'] as const, `${where}.status`, 'ACTIVE'),
    pvp_policy: oneOf(row.pvp, ['OFF', 'WAR_ONLY', 'ON'] as const, `${where}.pvp`, kind === 'STATE' ? 'WAR_ONLY' : 'ON'),
    build_policy: oneOf(row.build, ['MEMBERS', 'OPEN', 'NONE', 'OWNER_ZONE'] as const, `${where}.build`, kind === 'STATE' ? 'MEMBERS' : 'OPEN'),
    capital: point3(row.capital, `${where}.capital`), spawn: point3(row.spawn, `${where}.spawn`),
    polygon: list(row.polygon, `${where}.polygon`).map((value, i) => vec(value, `${where}.polygon[${i}]`)),
    natural_profile: profile(row.natural_profile, `${where}.natural_profile`), resource_profile: profile(row.resource_profile, `${where}.resource_profile`),
    color_override: color };
}

function parsePoint(raw: unknown, index: number): CatalogPoint {
  const row = obj(raw, `points[${index}]`), where = `points[${index}]`;
  const type = oneOf(row.type, POINT_TYPES, `${where}.type`);
  let resource: CatalogResource | null = null;
  if (row.resource !== undefined) {
    const site = obj(row.resource, `${where}.resource`);
    const tier = int(site.tier, `${where}.resource.tier`, 1, 4) as 1 | 2 | 3 | 4;
    resource = { type: oneOf(site.family, FAMILIES, `${where}.resource.family`), tier,
      kind: oneOf(site.kind, ['PIT', 'CACHE'] as const, `${where}.resource.kind`, 'PIT'),
      respawn_interval_seconds: site.respawn_seconds === undefined ? DEFAULT_RESPAWN[tier]! : int(site.respawn_seconds, `${where}.resource.respawn_seconds`, 300, 604800),
      capture_cooldown_seconds: site.cooldown_seconds === undefined ? 7200 : int(site.cooldown_seconds, `${where}.resource.cooldown_seconds`, 0, 604800),
      yield: resourceOutput(site.yield, oneOf(site.kind,['PIT','CACHE'] as const,`${where}.resource.kind`,'PIT'), `${where}.resource.yield`),
      pit_template: site.pit_template === undefined ? null : str(site.pit_template, `${where}.resource.pit_template`, 128) };
  }
  if ((type === 'RESOURCE_SITE' || type === 'MINE') !== (resource !== null)) fail(`${where}: resource table is required exactly for RESOURCE_SITE and MINE`);
  const icon = row.icon === undefined ? type.toLowerCase().replaceAll('_', '-') : str(row.icon, `${where}.icon`, 32);
  if (!ICON.test(icon)) fail(`${where}.icon: invalid`);
  return { code: code(row.code, `${where}.code`), type, name: str(row.name, `${where}.name`),
    region_code: row.region === undefined ? null : code(row.region, `${where}.region`),
    x: int(row.x, `${where}.x`), y: optInt(row.y, `${where}.y`, -64, 320), z: int(row.z, `${where}.z`),
    radius: row.radius === undefined ? 16 : int(row.radius, `${where}.radius`, 4, 256),
    capturable: row.capturable === undefined ? !['CAPITAL', 'CLAN_CAPITAL', 'EVENT_ZONE', 'BOSS_LAIR', 'RUINS'].includes(type) : row.capturable === true,
    map_icon: icon, resource };
}

/** Builds and validates a catalog from a parsed profile (world-regions TOML) and, if the profile asks, clan-world.json and capture rules. */
export function buildCatalog(profileRaw: unknown, clanWorld?: unknown, captureRules?: unknown): WorldCatalog {
  const root = obj(profileRaw, 'profile');
  if (root.format_version !== 1) fail('profile: unsupported format_version');
  const world = { id: str(root.world_id, 'world_id', 64), display_name: str(root.display_name, 'display_name'),
    border_half: optInt(root.border_half, 'border_half', 256, LIMIT) };
  if (!/^[a-z0-9][a-z0-9-]{1,63}$/.test(world.id)) fail('world_id: invalid');
  const regions: CatalogRegion[] = [], points: CatalogPoint[] = [];
  if (root.layout !== undefined) {
    if (root.layout !== 'clan-world') fail('layout: only "clan-world" is supported');
    if (clanWorld === undefined) fail('layout clan-world requires clan-world.json');
    const derived = fromClanWorld(obj(clanWorld, 'clan-world'), root.region_defaults === undefined ? {} : obj(root.region_defaults, 'region_defaults'));
    regions.push(...derived.regions); points.push(...derived.points);
  }
  regions.push(...list(root.regions, 'regions').map(parseRegion));
  points.push(...list(root.points, 'points').map(parsePoint));
  const connections = list(root.connections, 'connections').map((raw, i): CatalogConnection => {
    const row = obj(raw, `connections[${i}]`);
    return { code: code(row.code, `connections[${i}].code`), from: code(row.from, `connections[${i}].from`), to: code(row.to, `connections[${i}].to`),
      kind: oneOf(row.kind, ['ROAD', 'BRIDGE', 'PASS', 'PORT', 'RAIL'] as const, `connections[${i}].kind`),
      polyline: list(row.polyline, `connections[${i}].polyline`).map((value, j) => vec(value, `connections[${i}].polyline[${j}]`)) };
  });
  const markers = list(root.markers, 'markers').map((raw, i): CatalogMarker => {
    const row = obj(raw, `markers[${i}]`), kind = str(row.kind, `markers[${i}].kind`, 16), icon = str(row.icon ?? 'marker', `markers[${i}].icon`, 32);
    if (!/^[A-Z_]{2,16}$/.test(kind) || !ICON.test(icon)) fail(`markers[${i}]: invalid kind or icon`);
    return { code: code(row.code, `markers[${i}].code`), kind, icon, label: str(row.label, `markers[${i}].label`),
      layer: oneOf(row.layer, ['capitals', 'points', 'sites', 'roads', 'events', 'labels'] as const, `markers[${i}].layer`),
      x: int(row.x, `markers[${i}].x`), z: int(row.z, `markers[${i}].z`),
      visibility: oneOf(row.visibility, ['PUBLIC', 'CLAN', 'OWNER'] as const, `markers[${i}].visibility`, 'PUBLIC') };
  });
  if ((root.capture_rules !== undefined) !== (captureRules !== undefined)) fail('capture_rules: the profile reference and the rules file must come together');
  const catalog: WorldCatalog = { world, regions, points, connections, markers, capture_rules: captureRules === undefined ? null : parseRules(captureRules) };
  validateCatalog(catalog);
  return catalog;
}

const area2 = (polygon: Vec2[]) => polygon.reduce((sum, [x, z], i) => { const [nx, nz] = polygon[(i + 1) % polygon.length]!; return sum + x * nz - nx * z; }, 0);
/** Point in polygon for half-open integer block coordinates (block centre test). */
export function insidePolygon(polygon: Vec2[], x: number, z: number): boolean {
  const px = x + 0.5, pz = z + 0.5; let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, zi] = polygon[i]!, [xj, zj] = polygon[j]!;
    if ((zi > pz) !== (zj > pz) && px < ((xj - xi) * (pz - zi)) / (zj - zi) + xi) inside = !inside;
  }
  return inside;
}
function properCross(a: Vec2, b: Vec2, c: Vec2, d: Vec2) {
  const o = (p: Vec2, q: Vec2, r: Vec2) => Math.sign((q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]));
  return o(a, b, c) * o(a, b, d) < 0 && o(c, d, a) * o(c, d, b) < 0;
}
/** True when two simple polygons share interior area (touching edges are allowed, like adjacent territories). */
export function polygonsOverlap(a: Vec2[], b: Vec2[]): boolean {
  const box = (p: Vec2[]) => [Math.min(...p.map(v => v[0])), Math.min(...p.map(v => v[1])), Math.max(...p.map(v => v[0])), Math.max(...p.map(v => v[1]))];
  const [ax0, az0, ax1, az1] = box(a), [bx0, bz0, bx1, bz1] = box(b);
  if (ax1! <= bx0! || bx1! <= ax0! || az1! <= bz0! || bz1! <= az0!) return false;
  for (let i = 0; i < a.length; i++) for (let j = 0; j < b.length; j++)
    if (properCross(a[i]!, a[(i + 1) % a.length]!, b[j]!, b[(j + 1) % b.length]!)) return true;
  const centre = (p: Vec2[]): [number, number] => {
    // A strictly interior sample: midpoint of the first edge nudged inward along the winding.
    const [x0, z0] = p[0]!, [x1, z1] = p[1]!, sign = area2(p) > 0 ? 1 : -1;
    return [(x0 + x1) / 2 - sign * (z1 - z0) * 1e-3 - 0.5, (z0 + z1) / 2 + sign * (x1 - x0) * 1e-3 - 0.5];
  };
  const [ca, cz] = centre(a), [cb, czb] = centre(b);
  return insidePolygon(b, ca, cz) || insidePolygon(a, cb, czb)
    || JSON.stringify([...a].sort()) === JSON.stringify([...b].sort());
}

/** Markets are small rectangular build plots inside a protected, peaceful core; never another state. */
function marketInsideCore(market: CatalogRegion, core: CatalogRegion): boolean {
  if (market.kind !== 'MARKET' || core.kind !== 'CORE' || core.clan_code !== null
    || core.build_policy !== 'NONE' || core.pvp_policy !== 'OFF' || core.status !== 'ACTIVE') return false;
  const xs = market.polygon.map(p => p[0]), zs = market.polygon.map(p => p[1]);
  const x0 = Math.min(...xs), x1 = Math.max(...xs), z0 = Math.min(...zs), z1 = Math.max(...zs);
  if (market.polygon.length !== 4 || x1 - x0 < 8 || x1 - x0 > 36 || z1 - z0 < 8 || z1 - z0 > 36
    || new Set(market.polygon.map(p => p.join(','))).size !== 4
    || market.polygon.some(([x,z]) => ![x0,x1].includes(x) || ![z0,z1].includes(z))) return false;
  // All block columns, including the perimeter: a concave core cannot leave an unprotected gap.
  for (let x = x0; x < x1; x++) for (let z = z0; z < z1; z++)
    if (!insidePolygon(core.polygon, x, z)) return false;
  return true;
}

export function validateCatalog(catalog: WorldCatalog): void {
  const unique = (codes: string[], what: string) => { const seen = new Set<string>(); for (const c of codes) { if (seen.has(c)) fail(`duplicate ${what} code ${c}`); seen.add(c); } };
  unique(catalog.regions.map(r => r.code), 'region'); unique(catalog.points.map(p => p.code), 'point');
  unique(catalog.connections.map(c => c.code), 'connection'); unique(catalog.markers.map(m => m.code), 'marker');
  if (catalog.regions.length > 512 || catalog.points.length > 4096 || catalog.markers.length > 4096) fail('catalog too large');
  const clans = new Set<string>();
  for (const region of catalog.regions) {
    if (region.polygon.length < 3 || region.polygon.length > 4096) fail(`${region.code}: polygon needs 3..4096 vertices`);
    if (area2(region.polygon) === 0) fail(`${region.code}: polygon has no area`);
    for (let i = 0; i < region.polygon.length; i++) for (let j = i + 2; j < region.polygon.length; j++) {
      if (i === 0 && j === region.polygon.length - 1) continue;
      if (properCross(region.polygon[i]!, region.polygon[i + 1]!, region.polygon[j]!, region.polygon[(j + 1) % region.polygon.length]!)) fail(`${region.code}: polygon self-intersects`);
    }
    if (region.kind === 'STATE' && !region.clan_code) fail(`${region.code}: a STATE region needs a clan`);
    if (region.clan_code && !['STATE', 'FRONTIER_SLOT', 'MARKET'].includes(region.kind)) fail(`${region.code}: only STATE, FRONTIER_SLOT or MARKET regions belong to a clan`);
    if (region.clan_code && region.status === 'ACTIVE' && region.kind !== 'MARKET') { if (clans.has(region.clan_code)) fail(`clan ${region.clan_code} has two active regions`); clans.add(region.clan_code); }
    for (const [label, spot] of [['capital', region.capital], ['spawn', region.spawn]] as const)
      if (spot && !insidePolygon(region.polygon, spot.x, spot.z)) fail(`${region.code}: ${label} lies outside the region`);
  }
  const live = catalog.regions.filter(r => r.status !== 'ARCHIVED');
  const marketClans = new Set<string>();
  for (const market of live.filter(r => r.kind === 'MARKET')) {
    if (!market.clan_code || !clans.has(market.clan_code) || market.status !== 'ACTIVE'
      || market.build_policy !== 'MEMBERS' || market.pvp_policy !== 'OFF' || market.capital || market.spawn)
      fail(`${market.code}: market needs an active clan territory, MEMBERS build, OFF PvP and no capital/spawn`);
    const marketClan=market.clan_code ?? fail(`${market.code}: market needs a clan`);
    if (marketClans.has(marketClan)) fail(`${market.code}: duplicate market for clan ${marketClan}`);
    marketClans.add(marketClan);
    if (live.filter(core => marketInsideCore(market, core)).length !== 1)
      fail(`${market.code}: market must be an 8..36 block rectangle wholly inside one protected core`);
  }
  for (let i = 0; i < live.length; i++) for (let j = 0; j < i; j++)
    if (polygonsOverlap(live[i]!.polygon, live[j]!.polygon)
      && !marketInsideCore(live[i]!, live[j]!) && !marketInsideCore(live[j]!, live[i]!))
      fail(`regions ${live[j]!.code} and ${live[i]!.code} overlap`);
  const regionByCode = new Map(catalog.regions.map(r => [r.code, r]));
  for (const point of catalog.points) {
    if (point.capturable && live.some(r => r.kind === 'MARKET' && insidePolygon(r.polygon,point.x,point.z)))
      fail(`${point.code}: markets cannot contain capturable points`);
    if (point.region_code === null) {
      if (point.type !== 'CAPITAL' && live.some(r => r.kind === 'STATE' && insidePolygon(r.polygon, point.x, point.z)))
        fail(`${point.code}: lies inside a state but declares no region`);
      if (point.capturable && live.some(r => r.kind === 'STATE' && insidePolygon(r.polygon, point.x, point.z))) fail(`${point.code}: capturable point inside a state`);
      continue;
    }
    const region = regionByCode.get(point.region_code) ?? fail(`${point.code}: unknown region ${point.region_code}`);
    if (!insidePolygon(region.polygon, point.x, point.z)) fail(`${point.code}: lies outside region ${region.code}`);
    if (point.capturable && region.kind === 'STATE') fail(`${point.code}: capturable points are never inside a state (design rule)`);
  }
  for (const connection of catalog.connections) {
    if (!regionByCode.has(connection.from) || !regionByCode.has(connection.to) || connection.from === connection.to) fail(`${connection.code}: invalid regions`);
    if (connection.polyline.length < 2) fail(`${connection.code}: polyline needs two points`);
  }
}

/** Stable digest of the catalog content; an unchanged import is a no-op. */
export function catalogDigest(catalog: WorldCatalog): string {
  const stable = (value: unknown): unknown => Array.isArray(value) ? value.map(stable)
    : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable((value as Raw)[key])])) : value;
  return createHash('sha256').update(JSON.stringify(stable(catalog))).digest('hex');
}
