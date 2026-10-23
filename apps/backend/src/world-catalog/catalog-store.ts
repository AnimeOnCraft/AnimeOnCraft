/**
 * Idempotent import of a validated WorldCatalog and the public read model for the map.
 * Runtime state (owners, capture state, timers) is never overwritten by an import.
 */
import { createHash, randomUUID } from 'node:crypto';
import type { Prisma } from '../generated/prisma/client.js';
import { catalogDigest, type CatalogPoint, type CatalogRegion, type WorldCatalog } from './catalog.js';
import { readReliefBounds, type PublicReliefBounds } from './relief.js';

type Tx = Prisma.TransactionClient;
export interface ImportResult { world_id: string; changed: boolean; version: string; regions: number; points: number; archived_regions: number; disabled_points: number }

const json = (value: unknown) => JSON.stringify(value);
/** Deterministic UUID for audit target of a text-keyed world. */
function worldTarget(worldId: string) {
  const bytes = createHash('sha256').update(`world:${worldId}`).digest().subarray(0, 16);
  bytes[6] = (bytes[6]! & 15) | 80; bytes[8] = (bytes[8]! & 63) | 128;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
function bbox(polygon: [number, number][]) {
  const xs = polygon.map(p => p[0]), zs = polygon.map(p => p[1]);
  return { min_x: Math.min(...xs), min_z: Math.min(...zs), max_x: Math.max(...xs), max_z: Math.max(...zs) };
}
const regionFields = (r: CatalogRegion, clanId: string | null) => json([r.display_name, r.kind, clanId, r.status, r.pvp_policy, r.build_policy,
  r.capital, r.spawn, r.natural_profile, r.resource_profile, r.color_override, r.polygon]);
const pointFields = (p: CatalogPoint, regionId: string | null) => json([p.type, p.name, regionId, p.x, p.y, p.z, p.radius, p.capturable, p.map_icon, p.resource]);

/** Cancels a running capture of a point the import changes, putting the point back into its previous state. */
async function closeRunningCaptures(tx: Tx, pointId: string, reason: string) {
  const running = await tx.$queryRaw<Array<{ id: string; prior_state: string | null; declared_by_clan: string }>>`SELECT id,prior_state,declared_by_clan FROM capture_events
    WHERE point_id=${pointId}::uuid AND state IN ('PREPARING','ACTIVE','OVERTIME') FOR UPDATE`;
  for (const event of running) {
    await tx.$executeRaw`UPDATE capture_events SET state='CANCELLED',outcome='CANCELLED',finished_at=now(),end_reason=${reason},version=version+1 WHERE id=${event.id}::uuid`;
    await tx.$executeRaw`UPDATE strategic_points SET capture_state=${event.prior_state ?? 'NEUTRAL'},capture_end=now() WHERE id=${pointId}::uuid AND capture_state IN ('CONTESTED','CAPTURING')`;
    await tx.$executeRaw`INSERT INTO resource_site_history(point_id,event,clan_id,capture_event_id,actor_kind,payload)
      VALUES (${pointId}::uuid,'ADMIN_CANCEL',${event.declared_by_clan}::uuid,${event.id}::uuid,'OPERATOR',${json({ reason })}::jsonb)`;
  }
}

export async function applyCatalog(tx: Tx, catalog: WorldCatalog, options: { activate: boolean; reason: string }): Promise<ImportResult> {
  const worldId = catalog.world.id, digest = catalogDigest(catalog);
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002620)`;
  // Also the capture engine lock (always in this order): an import never races a running capture.
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(18002640)`;
  const existing = await tx.$queryRaw<Array<{ source_digest: string; version: bigint; active: boolean }>>`
    SELECT source_digest,version,active FROM world_catalog WHERE world_id=${worldId} FOR UPDATE`;
  const prior = existing[0];
  if (prior && prior.source_digest === digest && (!options.activate || prior.active))
    return { world_id: worldId, changed: false, version: prior.version.toString(), regions: catalog.regions.length, points: catalog.points.length, archived_regions: 0, disabled_points: 0 };

  const clanCodes = [...new Set(catalog.regions.map(r => r.clan_code).filter((c): c is string => c !== null))];
  const clans = new Map<string, string>();
  for (const code of clanCodes) {
    const rows = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE code_key=${code} AND archived_at IS NULL`;
    if (!rows[0]) throw new Error(`Clan ${code} referenced by the world catalog does not exist`);
    clans.set(code, rows[0].id);
  }

  const rules = catalog.capture_rules ? json(catalog.capture_rules) : null;
  if (prior) await tx.$executeRaw`UPDATE world_catalog SET display_name=${catalog.world.display_name},border_half=${catalog.world.border_half},
      source_digest=${digest},capture_rules=${rules}::jsonb,version=version+1,imported_at=now() WHERE world_id=${worldId}`;
  else await tx.$executeRaw`INSERT INTO world_catalog(world_id,display_name,border_half,source_digest,capture_rules)
      VALUES (${worldId},${catalog.world.display_name},${catalog.world.border_half},${digest},${rules}::jsonb)`;
  if (options.activate) {
    await tx.$executeRaw`UPDATE world_catalog SET active=false WHERE active AND world_id<>${worldId}`;
    await tx.$executeRaw`UPDATE world_catalog SET active=true WHERE world_id=${worldId}`;
  }

  // Regions
  const oldRegions = await tx.$queryRaw<Array<{ id: string; code: string; status: string; fields: string; assignment: string; clan_id: string | null; pvp_policy: string; build_policy: string }>>`
    SELECT r.id,r.code,r.status,r.assignment,r.clan_id,r.pvp_policy,r.build_policy,json_build_array(r.display_name,r.kind,r.clan_id,r.status,r.pvp_policy,r.build_policy,
      CASE WHEN r.capital_x IS NULL THEN NULL ELSE json_build_object('x',r.capital_x,'y',r.capital_y,'z',r.capital_z) END,
      CASE WHEN r.spawn_x IS NULL THEN NULL ELSE json_build_object('x',r.spawn_x,'y',r.spawn_y,'z',r.spawn_z) END,
      r.natural_profile,r.resource_profile,r.color_override,b.polygon)::text AS fields
    FROM world_regions r LEFT JOIN region_borders b ON b.region_id=r.id WHERE r.world_id=${worldId}`;
  const regionIds = new Map<string, string>();
  for (const source of catalog.regions) {
    const before = oldRegions.find(r => r.code === source.code);
    // A frontier slot assigned in the admin panel keeps its clan, status and policies (stage 8); the rest follows the catalog.
    const admin = before?.assignment === 'ADMIN' ? before : null;
    const region: CatalogRegion = admin ? { ...source, status: admin.status as CatalogRegion['status'], pvp_policy: admin.pvp_policy as CatalogRegion['pvp_policy'], build_policy: admin.build_policy as CatalogRegion['build_policy'] } : source;
    const clanId = admin ? admin.clan_id : region.clan_code ? clans.get(region.clan_code)! : null;
    const box = bbox(region.polygon), cap = region.capital, spawn = region.spawn;
    let id: string;
    if (!before) {
      id = randomUUID();
      await tx.$executeRaw`INSERT INTO world_regions(id,world_id,code,display_name,kind,clan_id,status,natural_profile,resource_profile,
          capital_x,capital_y,capital_z,spawn_x,spawn_y,spawn_z,pvp_policy,build_policy,color_override)
        VALUES (${id}::uuid,${worldId},${region.code},${region.display_name},${region.kind},${clanId}::uuid,${region.status},
          ${json(region.natural_profile)}::jsonb,${json(region.resource_profile)}::jsonb,${cap?.x ?? null},${cap?.y ?? null},${cap?.z ?? null},
          ${spawn?.x ?? null},${spawn?.y ?? null},${spawn?.z ?? null},${region.pvp_policy},${region.build_policy},${region.color_override})`;
    } else {
      id = before.id;
      // Compare through the same JSON normalisation PostgreSQL applies.
      const canonical = JSON.parse(before.fields) as unknown;
      if (json(canonical) !== json(JSON.parse(regionFields(region, clanId)))) {
        await tx.$executeRaw`UPDATE world_regions SET display_name=${region.display_name},kind=${region.kind},clan_id=${clanId}::uuid,status=${region.status},
            natural_profile=${json(region.natural_profile)}::jsonb,resource_profile=${json(region.resource_profile)}::jsonb,
            capital_x=${cap?.x ?? null},capital_y=${cap?.y ?? null},capital_z=${cap?.z ?? null},spawn_x=${spawn?.x ?? null},spawn_y=${spawn?.y ?? null},spawn_z=${spawn?.z ?? null},
            pvp_policy=${region.pvp_policy},build_policy=${region.build_policy},color_override=${region.color_override},version=version+1,updated_at=now()
          WHERE id=${id}::uuid`;
      }
    }
    await tx.$executeRaw`INSERT INTO region_borders(region_id,polygon,min_x,min_z,max_x,max_z,source)
        VALUES (${id}::uuid,${json(region.polygon)}::jsonb,${box.min_x},${box.min_z},${box.max_x},${box.max_z},'BLUEPRINT')
      ON CONFLICT (region_id) DO UPDATE SET polygon=EXCLUDED.polygon,min_x=EXCLUDED.min_x,min_z=EXCLUDED.min_z,max_x=EXCLUDED.max_x,max_z=EXCLUDED.max_z,
        source='BLUEPRINT',valid_from=CASE WHEN region_borders.polygon=EXCLUDED.polygon THEN region_borders.valid_from ELSE now() END`;
    regionIds.set(region.code, id);
  }
  let archivedRegions = 0;
  for (const old of oldRegions) if (!regionIds.has(old.code) && old.status !== 'ARCHIVED') {
    await tx.$executeRaw`UPDATE world_regions SET status='ARCHIVED',version=version+1,updated_at=now() WHERE id=${old.id}::uuid`; archivedRegions++;
  }

  // Points and resource sites (runtime ownership columns are untouched).
  const oldPoints = await tx.$queryRaw<Array<{ id: string; code: string; enabled: boolean; fields: string }>>`
    SELECT p.id,p.code,p.enabled,json_build_array(p.type,p.name,p.region_id,p.x,p.y,p.z,p.radius,p.capturable,p.map_icon,
      CASE WHEN s.point_id IS NULL THEN NULL ELSE json_build_object('type',s.resource_type,'tier',s.resource_tier,'kind',s.kind,
        'respawn_interval_seconds',s.respawn_interval_seconds,'capture_cooldown_seconds',s.capture_cooldown_seconds,'yield',s.yield,'pit_template',s.pit_template) END)::text AS fields
    FROM strategic_points p LEFT JOIN resource_sites s ON s.point_id=p.id WHERE p.world_id=${worldId}`;
  const seen = new Set<string>();
  for (const point of catalog.points) {
    const regionId = point.region_code ? regionIds.get(point.region_code)! : null, before = oldPoints.find(p => p.code === point.code);
    let id: string;
    if (!before) {
      id = randomUUID();
      await tx.$executeRaw`INSERT INTO strategic_points(id,world_id,code,type,name,region_id,x,y,z,radius,capturable,map_icon)
        VALUES (${id}::uuid,${worldId},${point.code},${point.type},${point.name},${regionId}::uuid,${point.x},${point.y},${point.z},${point.radius},${point.capturable},${point.map_icon})`;
      await tx.$executeRaw`INSERT INTO resource_site_history(point_id,event,actor_kind,payload) VALUES (${id}::uuid,'IMPORTED','OPERATOR',${json({ digest })}::jsonb)`;
    } else {
      id = before.id;
      const normalized = json(JSON.parse(pointFields(point, regionId)));
      if (json(JSON.parse(before.fields)) !== normalized || !before.enabled) {
        if (!point.capturable) {
          // A point that stops being capturable returns to neutral; the open ownership interval and any running capture are closed.
          await closeRunningCaptures(tx, id, 'NOT_CAPTURABLE');
          await tx.$executeRaw`UPDATE resource_site_ownership SET released_at=now(),release_reason='NOT_CAPTURABLE' WHERE point_id=${id}::uuid AND released_at IS NULL`;
          await tx.$executeRaw`UPDATE strategic_points SET owner_clan_id=NULL,capture_state='NEUTRAL',protection_until=NULL,lease_until=NULL
            WHERE id=${id}::uuid AND (owner_clan_id IS NOT NULL OR capture_state<>'NEUTRAL')`;
        }
        await tx.$executeRaw`UPDATE strategic_points SET type=${point.type},name=${point.name},region_id=${regionId}::uuid,x=${point.x},y=${point.y},z=${point.z},
            radius=${point.radius},capturable=${point.capturable},map_icon=${point.map_icon},enabled=true,config_version=config_version+1,updated_at=now()
          WHERE id=${id}::uuid`;
        if (!before.enabled) await tx.$executeRaw`INSERT INTO resource_site_history(point_id,event,actor_kind,payload) VALUES (${id}::uuid,'ENABLED','OPERATOR',${json({ digest })}::jsonb)`;
      }
    }
    if (point.resource) {
      const r = point.resource;
      await tx.$executeRaw`INSERT INTO resource_sites(point_id,resource_type,resource_tier,kind,capture_cooldown_seconds,respawn_interval_seconds,yield,pit_template)
          VALUES (${id}::uuid,${r.type},${r.tier},${r.kind},${r.capture_cooldown_seconds},${r.respawn_interval_seconds},${json(r.yield)}::jsonb,${r.pit_template})
        ON CONFLICT (point_id) DO UPDATE SET resource_type=EXCLUDED.resource_type,resource_tier=EXCLUDED.resource_tier,kind=EXCLUDED.kind,
          next_refresh=CASE WHEN resource_sites.yield IS DISTINCT FROM EXCLUDED.yield OR resource_sites.kind<>EXCLUDED.kind THEN now() ELSE resource_sites.next_refresh END,
          capture_cooldown_seconds=EXCLUDED.capture_cooldown_seconds,respawn_interval_seconds=EXCLUDED.respawn_interval_seconds,yield=EXCLUDED.yield,pit_template=EXCLUDED.pit_template`;
    }
    seen.add(point.code);
  }
  let disabledPoints = 0;
  for (const old of oldPoints) if (!seen.has(old.code) && old.enabled) {
    await closeRunningCaptures(tx, old.id, 'POINT_DISABLED');
    await tx.$executeRaw`UPDATE strategic_points SET enabled=false,config_version=config_version+1,updated_at=now() WHERE id=${old.id}::uuid`;
    await tx.$executeRaw`INSERT INTO resource_site_history(point_id,event,actor_kind,payload) VALUES (${old.id}::uuid,'DISABLED','OPERATOR',${json({ digest })}::jsonb)`;
    disabledPoints++;
  }

  // Connections and markers carry no runtime state: replace them.
  await tx.$executeRaw`DELETE FROM region_connections WHERE world_id=${worldId}`;
  for (const c of catalog.connections)
    await tx.$executeRaw`INSERT INTO region_connections(world_id,code,from_region,to_region,kind,polyline)
      VALUES (${worldId},${c.code},${regionIds.get(c.from)!}::uuid,${regionIds.get(c.to)!}::uuid,${c.kind},${json(c.polyline)}::jsonb)`;
  await tx.$executeRaw`DELETE FROM map_markers WHERE world_id=${worldId}`;
  for (const m of catalog.markers)
    await tx.$executeRaw`INSERT INTO map_markers(world_id,code,layer,kind,x,z,label,icon,visibility)
      VALUES (${worldId},${m.code},${m.layer},${m.kind},${m.x},${m.z},${m.label},${m.icon},${m.visibility})`;

  const version = (await tx.$queryRaw<Array<{ version: bigint }>>`SELECT version FROM world_catalog WHERE world_id=${worldId}`)[0]!.version;
  await tx.$executeRaw`INSERT INTO audit_logs(actor_kind,action,target_type,target_id,after_redacted,reason,request_id)
    VALUES ('OPERATOR','WORLD_CATALOG_IMPORTED','world_catalog',${worldTarget(worldId)}::uuid,
      ${json({ world_id: worldId, version: version.toString(), digest, regions: catalog.regions.length, points: catalog.points.length, archived_regions: archivedRegions, disabled_points: disabledPoints, activated: options.activate })}::jsonb,
      ${options.reason},${randomUUID()}::uuid)`;
  return { world_id: worldId, changed: true, version: version.toString(), regions: catalog.regions.length, points: catalog.points.length, archived_regions: archivedRegions, disabled_points: disabledPoints };
}

// ---------------------------------------------------------------------------
// Public read model (no technical codes, only active world, active regions, enabled points, PUBLIC markers)
// ---------------------------------------------------------------------------
export interface PublicClanRef { id: string; name: string; short_code: string; color: string | null }
export interface PublicWorldMap {
  world: { display_name: string; version: string; border_half: number | null };
  regions: Array<{ id: string; display_name: string; kind: string; clan: PublicClanRef | null; color: string | null; polygon: [number, number][]; pvp_policy: string; build_policy: string }>;
  connections: Array<{ id: string; kind: string; polyline: [number, number][] }>;
  markers: Array<{ id: string; layer: string; kind: string; label: string; icon: string; x: number; z: number }>;
  /** Bounds of the relief underlay (GET /api/v1/world/relief), null when none is imported. */
  relief: PublicReliefBounds | null;
}
export interface PublicWorldPoints {
  version: string;
  items: Array<{ id: string; name: string; type: string; x: number; z: number; radius: number; capturable: boolean; region_id: string | null;
    owner: PublicClanRef | null; state: string; protection_until: string | null; lease_until: string | null; map_icon: string;
    resource: { family: string; tier: number; kind: string; next_refresh: string | null } | null;
    event: { phase: string; active_start: string; active_end: string; declared_by: PublicClanRef } | null }>;
}
export interface PublicWorldEvents {
  items: Array<{ id: string; at: string; kind: string; point: { id: string; name: string }; clan: PublicClanRef | null }>;
}
const iso = (value: Date | null) => value ? value.toISOString() : null;
const clanRef = (id: string | null, name: string | null, code: string | null, color: string | null): PublicClanRef | null =>
  id && name && code ? { id, name, short_code: code, color: color ?? null } : null;

export async function readWorldMap(db: Tx): Promise<PublicWorldMap | null> {
  const world = (await db.$queryRaw<Array<{ world_id: string; display_name: string; version: bigint; border_half: number | null }>>`
    SELECT world_id,display_name,version,border_half FROM world_catalog WHERE active LIMIT 1`)[0];
  if (!world) return null;
  const regions = await db.$queryRaw<Array<{ id: string; display_name: string; kind: string; clan_id: string | null; clan_name: string | null; clan_code: string | null;
    clan_color: string | null; color_override: string | null; polygon: [number, number][]; pvp_policy: string; build_policy: string }>>`
    SELECT r.id,r.display_name,r.kind,c.id AS clan_id,c.name AS clan_name,c.short_code AS clan_code,c.color AS clan_color,r.color_override,
      b.polygon,r.pvp_policy,r.build_policy
    FROM world_regions r JOIN region_borders b ON b.region_id=r.id LEFT JOIN clans c ON c.id=r.clan_id AND c.archived_at IS NULL
    WHERE r.world_id=${world.world_id} AND r.status='ACTIVE' ORDER BY r.kind,r.display_name,r.id`;
  const connections = await db.$queryRaw<Array<{ id: string; kind: string; polyline: [number, number][] }>>`
    SELECT c.id,c.kind,c.polyline FROM region_connections c JOIN world_regions a ON a.id=c.from_region AND a.status='ACTIVE'
      JOIN world_regions b ON b.id=c.to_region AND b.status='ACTIVE' WHERE c.world_id=${world.world_id} ORDER BY c.code`;
  const markers = await db.$queryRaw<Array<{ id: string; layer: string; kind: string; label: string; icon: string; x: number; z: number }>>`
    SELECT id,layer,kind,label,icon,x,z FROM map_markers WHERE world_id=${world.world_id} AND visibility='PUBLIC'
      AND (visible_from IS NULL OR visible_from<=now()) AND (visible_until IS NULL OR visible_until>now()) ORDER BY layer,label,id`;
  return {
    world: { display_name: world.display_name, version: world.version.toString(), border_half: world.border_half },
    regions: regions.map(r => {
      const clan = clanRef(r.clan_id, r.clan_name, r.clan_code, r.clan_color);
      return { id: r.id, display_name: r.display_name, kind: r.kind, clan, color: r.color_override ?? clan?.color ?? null, polygon: r.polygon, pvp_policy: r.pvp_policy, build_policy: r.build_policy };
    }),
    connections, markers, relief: await readReliefBounds(db, world.world_id),
  };
}

export async function readWorldPoints(db: Tx): Promise<PublicWorldPoints | null> {
  const world = (await db.$queryRaw<Array<{ world_id: string; version: bigint }>>`SELECT world_id,version FROM world_catalog WHERE active LIMIT 1`)[0];
  if (!world) return null;
  const rows = await db.$queryRaw<Array<{ id: string; name: string; type: string; x: number; z: number; radius: number; capturable: boolean; region_id: string | null;
    owner_id: string | null; owner_name: string | null; owner_code: string | null; owner_color: string | null; capture_state: string;
    protection_until: Date | null; lease_until: Date | null; map_icon: string; resource_type: string | null; resource_tier: number | null; site_kind: string | null; next_refresh: Date | null;
    event_state: string | null; active_start: Date | null; active_end: Date | null; dc_id: string | null; dc_name: string | null; dc_code: string | null; dc_color: string | null }>>`
    SELECT p.id,p.name,p.type,p.x,p.z,p.radius,p.capturable,r.id AS region_id,c.id AS owner_id,c.name AS owner_name,c.short_code AS owner_code,c.color AS owner_color,
      p.capture_state,p.protection_until,p.lease_until,p.map_icon,s.resource_type,s.resource_tier,s.kind AS site_kind,s.next_refresh,
      e.state AS event_state,e.active_start,e.active_end,dc.id AS dc_id,dc.name AS dc_name,dc.short_code AS dc_code,dc.color AS dc_color
    FROM strategic_points p LEFT JOIN world_regions r ON r.id=p.region_id AND r.status='ACTIVE'
      LEFT JOIN clans c ON c.id=p.owner_clan_id LEFT JOIN resource_sites s ON s.point_id=p.id
      LEFT JOIN capture_events e ON e.point_id=p.id AND e.state IN ('PREPARING','ACTIVE','OVERTIME') LEFT JOIN clans dc ON dc.id=e.declared_by_clan
    WHERE p.world_id=${world.world_id} AND p.enabled ORDER BY p.type,p.name,p.id`;
  return {
    version: world.version.toString(),
    items: rows.map(row => ({ id: row.id, name: row.name, type: row.type, x: row.x, z: row.z, radius: row.radius, capturable: row.capturable, region_id: row.region_id,
      owner: clanRef(row.owner_id, row.owner_name, row.owner_code, row.owner_color), state: row.capture_state,
      protection_until: iso(row.protection_until), lease_until: iso(row.lease_until), map_icon: row.map_icon,
      resource: row.resource_type ? { family: row.resource_type, tier: Number(row.resource_tier), kind: row.site_kind!, next_refresh: iso(row.next_refresh) } : null,
      event: row.event_state && row.active_start && row.active_end && row.dc_id ? { phase: row.event_state, active_start: row.active_start.toISOString(), active_end: row.active_end.toISOString(),
        declared_by: clanRef(row.dc_id, row.dc_name, row.dc_code, row.dc_color)! } : null })),
  };
}

/** Latest public capture events of the active world, newest first (the launcher feed). */
export async function readWorldEvents(db: Tx): Promise<PublicWorldEvents | null> {
  const world = (await db.$queryRaw<Array<{ world_id: string }>>`SELECT world_id FROM world_catalog WHERE active LIMIT 1`)[0];
  if (!world) return null;
  const rows = await db.$queryRaw<Array<{ id: bigint; at: Date; event: string; point_id: string; point_name: string; clan_id: string | null; clan_name: string | null; clan_code: string | null; clan_color: string | null }>>`
    SELECT h.id,h.at,h.event,p.id AS point_id,p.name AS point_name,c.id AS clan_id,c.name AS clan_name,c.short_code AS clan_code,c.color AS clan_color
    FROM resource_site_history h JOIN strategic_points p ON p.id=h.point_id AND p.enabled LEFT JOIN clans c ON c.id=h.clan_id
    WHERE p.world_id=${world.world_id} AND h.event IN ('DECLARED','CAPTURED','RENEWED','DEFENDED','NEUTRALIZED','RELEASED','ADMIN_TRANSFER','ADMIN_RESET')
    ORDER BY h.at DESC,h.id DESC LIMIT 30`;
  return { items: rows.map(row => ({ id: row.id.toString(), at: row.at.toISOString(), kind: row.event, point: { id: row.point_id, name: row.point_name },
    clan: clanRef(row.clan_id, row.clan_name, row.clan_code, row.clan_color) })) };
}
