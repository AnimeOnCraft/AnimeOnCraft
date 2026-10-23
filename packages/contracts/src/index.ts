export const PUBLIC_API_MAJOR = 1 as const;
export type { paths as AuthPaths, components as AuthComponents } from './generated/auth.js';
export type { paths as GamePaths, components as GameComponents } from './generated/game.js';
export type { paths as CommunityPaths, components as CommunityComponents } from './generated/community.js';
export type { paths as AdministrationPaths, components as AdministrationComponents } from './generated/administration.js';
import type { components } from './generated/api.js';
export type NewsSummary = components['schemas']['NewsSummary'];
export type NewsDetail = components['schemas']['NewsDetail'];
export type NewsListResponse = components['schemas']['NewsListResponse'];
export const INTEGRATION_PROTOCOL_MAJOR = 1 as const;

export type HealthStatus = 'ok' | 'degraded' | 'unavailable';

export interface LiveResponse {
  status: 'ok';
  service: 'animeoncraft-backend';
  version: string;
}

export interface DependencyStatus {
  database: HealthStatus;
  rate_limits: HealthStatus;
}

export interface ReadyResponse {
  status: HealthStatus;
  dependencies: DependencyStatus;
}

export interface ClanSummary {
  id: string;
  name: string;
  short_code: string;
  description: string;
  logo_url: string | null;
  discord_url: string | null;
  /** Registry colour RRGGBB from `clans.color`; null or absent = neutral fallback. */
  color?: string | null;
  member_count: number | null;
  online_count: number | null;
  online_updated_at: string | null;
}

export interface ClanListResponse {
  items: ClanSummary[];
  next_cursor: string | null;
}

export interface CompatibilityResponse {
  public_api_major: number;
  integration_protocol_major: number;
  launcher_status: 'UNAVAILABLE';
  reason: 'SKELETON_ONLY';
}

export type WorldRegionKind = 'CORE' | 'STATE' | 'BORDERLAND' | 'RIM' | 'FRONTIER_SLOT' | 'FRONTIER_WILD' | 'SEA';
export type WorldPointType = 'CAPITAL' | 'CLAN_CAPITAL' | 'FORT' | 'MINE' | 'VILLAGE' | 'PORT' | 'BRIDGE' | 'PASS'
  | 'WATCHTOWER' | 'RUINS' | 'RESOURCE_SITE' | 'OUTPOST' | 'EVENT_ZONE' | 'BOSS_LAIR';
export type WorldCaptureState = 'NEUTRAL' | 'CONTESTED' | 'CAPTURING' | 'CONTROLLED' | 'PROTECTED' | 'AVAILABLE';
export type ResourceFamily = 'FERROUS' | 'NONFERROUS' | 'PRECIOUS' | 'ARCANE' | 'TECH' | 'TIMBER' | 'AGRARIAN' | 'ALCHEMY' | 'MARINE';
export type WorldVec = [number, number];

export interface WorldClanRef { id: string; name: string; short_code: string; color: string | null }

export interface WorldMapResponse {
  world: { display_name: string; version: string; border_half: number | null };
  regions: Array<{ id: string; display_name: string; kind: WorldRegionKind; clan: WorldClanRef | null; color: string | null;
    polygon: WorldVec[]; pvp_policy: 'OFF' | 'WAR_ONLY' | 'ON'; build_policy: 'MEMBERS' | 'OPEN' | 'NONE' | 'OWNER_ZONE' }>;
  connections: Array<{ id: string; kind: 'ROAD' | 'BRIDGE' | 'PASS' | 'PORT' | 'RAIL'; polyline: WorldVec[] }>;
  markers: Array<{ id: string; layer: 'capitals' | 'points' | 'sites' | 'roads' | 'events' | 'labels'; kind: string; label: string; icon: string; x: number; z: number }>;
  /** Bounds of the relief underlay (GET /world/relief); null when none is imported. */
  relief: WorldReliefBounds | null;
}

export interface WorldReliefBounds { revision: string; x0: number; z0: number; width_blocks: number; height_blocks: number }
export interface WorldReliefResponse extends WorldReliefBounds {
  mime: 'image/jpeg' | 'image/png'; width_px: number; height_px: number; sha256: string; image_base64: string;
}

export interface WorldPoint {
  id: string; name: string; type: WorldPointType; x: number; z: number; radius: number; capturable: boolean; region_id: string | null;
  owner: WorldClanRef | null; state: WorldCaptureState; protection_until: string | null; lease_until: string | null; map_icon: string;
  resource: { family: ResourceFamily; tier: number; kind: 'PIT' | 'CACHE'; next_refresh: string | null } | null;
  /** The running capture of this point (stage 4); null when there is none. */
  event: { phase: 'PREPARING' | 'ACTIVE' | 'OVERTIME'; active_start: string; active_end: string; declared_by: WorldClanRef } | null;
}

export interface WorldPointsResponse { version: string; items: WorldPoint[] }

export type WorldEventKind = 'DECLARED' | 'CAPTURED' | 'RENEWED' | 'DEFENDED' | 'NEUTRALIZED' | 'RELEASED' | 'ADMIN_TRANSFER' | 'ADMIN_RESET';
export interface WorldEvent { id: string; at: string; kind: WorldEventKind; point: { id: string; name: string }; clan: WorldClanRef | null }
export interface WorldEventsResponse { items: WorldEvent[] }
