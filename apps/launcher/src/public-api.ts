import { rememberClanColor } from './clan-brand.js';
import type { ClanListResponse, ClanSummary, CompatibilityResponse, NewsDetail, NewsListResponse, NewsSummary, ReadyResponse } from '@animeoncraft/contracts';

export type PublicResource = 'ready' | 'compatibility' | 'clans' | 'clan' | 'clan_progression' | 'clan_competitions' | 'clan_wars' | 'news' | 'article' | 'world_map' | 'world_points' | 'world_events' | 'world_relief';
export interface PublicRequest { resource: PublicResource; key?: string; cursor?: string }
export interface PublicReply { status: number; body: unknown }
export type PublicTransport = (request: PublicRequest, signal: AbortSignal) => Promise<PublicReply>;
export class PublicApiError extends Error {
  constructor(public readonly code: 'UNAVAILABLE' | 'NOT_FOUND' | 'INVALID_DATA') { super(code); }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const slug = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
function invalid(): never { throw new PublicApiError('INVALID_DATA'); }
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid();
  return value as Record<string, unknown>;
}
function text(value: unknown, max: number, min = 0): string {
  if (typeof value !== 'string' || value.length > max || value.length < min) return invalid();
  return value;
}
function nullableText(value: unknown, max: number): string | null { return value === null ? null : text(value, max); }
function count(value: unknown): number | null {
  if (value === null) return null;
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) return invalid();
  return value;
}
function date(value: unknown): string { const result = text(value, 64, 1); if (!Number.isFinite(Date.parse(result))) return invalid(); return result; }
function id(value: unknown): string { const result = text(value, 36); if (!uuid.test(result)) return invalid(); return result; }
export function parseClan(value: unknown): ClanSummary {
  const row = object(value), code = text(row.short_code, 12, 2);
  if (!/^[A-Z0-9]+$/.test(code)) return invalid();
  const color = row.color === undefined || row.color === null ? null : text(row.color, 6, 6);
  if (color !== null && !/^[0-9A-F]{6}$/.test(color)) return invalid();
  rememberClanColor(code, color);
  return { id: id(row.id), name: text(row.name, 64, 2), short_code: code, description: text(row.description, 4000),
    logo_url: nullableText(row.logo_url, 2048), discord_url: nullableText(row.discord_url, 2048), color,
    member_count: count(row.member_count), online_count: count(row.online_count), online_updated_at: row.online_updated_at === null ? null : date(row.online_updated_at) };
}
function parseNews(value: unknown): NewsSummary {
  const row = object(value), key = text(row.slug, 100, 1), version = text(row.version, 30, 1);
  if (!slug.test(key) || !/^(0|[1-9][0-9]*)$/.test(version)) return invalid();
  return { id: id(row.id), slug: key, title: text(row.title, 160, 1), published_at: date(row.published_at), version };
}
function list<T>(value: unknown, parse: (row: unknown) => T): { items: T[]; next_cursor: string | null } {
  const row = object(value); if (!Array.isArray(row.items) || row.items.length > 100) return invalid();
  return { items: row.items.map(parse), next_cursor: nullableText(row.next_cursor, 512) };
}
export const parseClans = (value: unknown): ClanListResponse => list(value, parseClan);
export function parseClanWars(value:unknown){
  const row=object(value);
  if(!Array.isArray(row.items)||row.items.length>50)return invalid();
  return {items:row.items.map(value=>{
    const item=object(value),status=text(item.status,16);
    if(!['PREPARATION','ACTIVE','COOLDOWN','FINISHED','CANCELLED'].includes(status))return invalid();
    return {id:id(item.id),attacker:text(item.attacker,12,2),defender:text(item.defender,12,2),status,
      starts_at:date(item.starts_at),ends_at:date(item.ends_at),cooldown_ends_at:item.cooldown_ends_at===null?null:date(item.cooldown_ends_at)};
  })};
}
export function parseClanCompetitions(value: unknown) {
  const row = object(value);
  if (!Array.isArray(row.items) || row.items.length > 50) return invalid();
  return { items: row.items.map(value => {
    const item = object(value), score = count(item.score), status = text(item.status, 16);
    if (score === null || !['SCHEDULED', 'ACTIVE', 'FINISHED', 'CANCELLED'].includes(status)) return invalid();
    if(!Array.isArray(item.leaderboard)||item.leaderboard.length>100)return invalid();
    const leaderboard=item.leaderboard.map(value=>{const leader=object(value),score=count(leader.score);if(score===null)return invalid();return {short_code:text(leader.short_code,12,2),score};});
    const kind=item.kind===undefined?'BOSS_HUNT':text(item.kind,32);
    if(!['BOSS_HUNT','WORLD_BOSS'].includes(kind))return invalid();
    const arena=item.arena==null?null:object(item.arena);
    if(arena&&![arena.x,arena.y,arena.z].every(Number.isSafeInteger))return invalid();
    const outcome=item.outcome==null?null:text(item.outcome,16);
    if(outcome!==null&&!['KILLED','EXPIRED'].includes(outcome))return invalid();
    return { leaderboard, id: id(item.event_id), title: text(item.title, 160, 1), status, score,kind,outcome,
      arena:arena?{x:arena.x as number,y:arena.y as number,z:arena.z as number}:null,
      starts_at: date(item.starts_at), ends_at: date(item.ends_at),
      reward_granted_at: item.reward_granted_at === null ? null : date(item.reward_granted_at) };
  }) };
}
export function parseClanProgression(value: unknown) {
  const row = object(value);
  const xp = count(row.xp), level = count(row.level);
  if (xp === null || level === null || level < 1 || level > 100 || !Number.isSafeInteger(row.reputation)
    || typeof row.reputation !== 'number' || Math.abs(row.reputation) > 1000000
    || !Array.isArray(row.achievements) || row.achievements.length > 100) return invalid();
  const statistics = object(row.statistics);
  const kills = count(statistics.BOSS_KILL ?? 0);
  if (kills === null) return invalid();
  return { clan_id: id(row.clan_id), xp, level, reputation: row.reputation, kills,
    achievements: row.achievements.map(value => {
      const item = object(value), progress = count(item.progress), target = count(item.target);
      if (progress === null || target === null || target === 0 || progress > target) return invalid();
      return { code: text(item.code, 64, 1), progress, target,
        unlocked_at: item.unlocked_at === null ? null : date(item.unlocked_at) };
    }) };
}
export const parseNewsList = (value: unknown): NewsListResponse => list(value, parseNews);
export function parseArticle(value: unknown): NewsDetail { return { ...parseNews(value), body_markdown: text(object(value).body_markdown, 20000) }; }
export function parseReady(value: unknown): ReadyResponse {
  const row = object(value), dependencies = object(row.dependencies);
  function status(value: unknown): 'ok' | 'degraded' | 'unavailable' { if (value !== 'ok' && value !== 'degraded' && value !== 'unavailable') return invalid(); return value; }
  return { status: status(row.status), dependencies: { database: status(dependencies.database), rate_limits: status(dependencies.rate_limits) } };
}
export function parseCompatibility(value: unknown): CompatibilityResponse {
  const row = object(value);
  if (row.public_api_major !== 1 || row.integration_protocol_major !== 1 || row.launcher_status !== 'UNAVAILABLE' || row.reason !== 'SKELETON_ONLY') return invalid();
  return { public_api_major: 1, integration_protocol_major: 1, launcher_status: 'UNAVAILABLE', reason: 'SKELETON_ONLY' };
}
export function publicPath(request: PublicRequest): string {
  const paths = { ready: '/health/ready', compatibility: '/launcher/compatibility', clans: '/clans', clan: '/clans', clan_progression: '/clans', clan_competitions: '/clans', clan_wars: '/clans', news: '/news', article: '/news', world_map: '/world/map', world_points: '/world/points', world_events: '/world/events', world_relief: '/world/relief' };
  let path = paths[request.resource]; if (!path) return invalid();
  if (request.resource === 'clan' || request.resource === 'clan_progression' || request.resource === 'clan_competitions' || request.resource === 'clan_wars' || request.resource === 'article') {
    if (!request.key || (request.resource !== 'article' ? !uuid.test(request.key) : request.key.length > 100 || !slug.test(request.key))) return invalid();
    path += `/${encodeURIComponent(request.key)}`;
    if (request.resource === 'clan_progression') path += '/progression';
    if (request.resource === 'clan_competitions') path += '/competitions';
    if (request.resource === 'clan_wars') path += '/wars';
  } else if (request.key !== undefined) return invalid();
  if (request.resource === 'clans' || request.resource === 'news') {
    const query = new URLSearchParams({ limit: '20' });
    if (request.cursor !== undefined) { query.set('cursor', text(request.cursor, 512, 1)); }
    path += `?${query}`;
  } else if (request.cursor !== undefined) return invalid();
  return `/api/v1${path}`;
}
export const browserTransport: PublicTransport = async (request, signal) => {
  const response = await fetch(publicPath(request), { signal, credentials: 'omit', redirect: 'error', cache: 'no-store' });
  if (response.status !== 200 && !(request.resource === 'ready' && response.status === 503)) return { status: response.status, body: null };
  // The relief underlay is one base64 image (≤ 3 MiB raw); every other response stays small.
  const raw = await response.text(); if (new TextEncoder().encode(raw).length > (request.resource === 'world_relief' ? 4718592 : 524288)) return invalid();
  try { return { status: response.status, body: JSON.parse(raw) as unknown }; } catch { return invalid(); }
};
export class PublicApi {
  constructor(private readonly transport: PublicTransport = browserTransport) {}
  async read<T>(request: PublicRequest, parse: (value: unknown) => T, signal: AbortSignal): Promise<T> {
    publicPath(request);
    let reply: PublicReply;
    try { reply = await this.transport(request, signal); } catch (error) { if (error instanceof PublicApiError) throw error; throw new PublicApiError('UNAVAILABLE'); }
    if (reply.status !== 200 && !(request.resource === 'ready' && reply.status === 503)) throw new PublicApiError(reply.status === 404 ? 'NOT_FOUND' : 'UNAVAILABLE');
    if (signal.aborted) throw new PublicApiError('UNAVAILABLE');
    return parse(reply.body);
  }
}
export function safeDiscord(value: string | null): string | null {
  if (!value) return null;
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && ['discord.gg', 'discord.com'].includes(url.hostname) ? url.href : null; }
  catch { return null; }
}
