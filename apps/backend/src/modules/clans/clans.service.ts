import { CLAN_ACHIEVEMENTS } from '../../clan-achievements.js';
import { Inject, Injectable, NotFoundException, ServiceUnavailableException } from '@nestjs/common';
import type { ClanListResponse } from '@animeoncraft/contracts';
import { Prisma, type Clan } from '../../generated/prisma/client.js';
import { DatabaseService } from '../../infrastructure/database.service.js';
import { PaginationService, type PageInput } from '../../infrastructure/pagination.js';

@Injectable()
export class ClansService {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService, @Inject(PaginationService) private readonly pagination: PaginationService) {}

  async list(input: PageInput): Promise<ClanListResponse> {
    if (!this.database.client) throw new ServiceUnavailableException('Database unavailable');
    const position = this.pagination.read('clans', input.cursor);
    try {
      const clans = await this.database.client.clan.findMany({ where: { enabled: true, archivedAt: null,
        ...(position ? { OR: [{ createdAt: { gt: new Date(position.time) } }, { createdAt: new Date(position.time), id: { gt: position.id } }] } : {}) },
        orderBy: [{ createdAt: 'asc' }, { id: 'asc' }], take: input.limit + 1,
        include: { _count: { select: { clan_members: { where: { ended_at: null } } } } } });
      const page = clans.slice(0, input.limit);
      const ids = page.map(clan => clan.id);
      const presence = ids.length ? await this.database.client.$queryRaw<Array<{ clan_id: string; online: bigint; updated_at: Date | null }>>`
        SELECT c.id AS clan_id,count(DISTINCT p.user_id) FILTER (WHERE p.online) AS online,
          (SELECT max(snapshot_at) FROM clan_server_presence WHERE snapshot_at>now()-interval '90 seconds') AS updated_at
        FROM clans c LEFT JOIN clan_online_presence p ON p.clan_id=c.id AND p.last_seen_at>now()-interval '90 seconds'
          AND EXISTS (SELECT 1 FROM clan_server_presence s WHERE s.server_id=p.server_id AND s.snapshot_at>now()-interval '90 seconds')
        WHERE c.id IN (${Prisma.join(ids.map(id => Prisma.sql`${id}::uuid`))}) GROUP BY c.id` : [];
      const live = new Map(presence.map(row => [row.clan_id, row]));
      const last = page.at(-1);
      return {
        items: page.map(clan => this.summary(clan, live.get(clan.id))),
        next_cursor: clans.length > input.limit && last ? this.pagination.sign('clans', { id: last.id, time: last.createdAt.toISOString() }) : null
      };
    } catch {
      throw new ServiceUnavailableException('Database unavailable');
    }
  }
  async detail(id: string) {
    if (!this.database.client) throw new ServiceUnavailableException('Database unavailable');
    let clan: (Clan & { _count: { clan_members: number } }) | null;
    try { clan = await this.database.client.clan.findFirst({ where: { id, enabled: true, archivedAt: null }, include: { _count: { select: { clan_members: { where: { ended_at: null } } } } } }); }
    catch { throw new ServiceUnavailableException('Database unavailable'); }
    if (!clan) throw new NotFoundException();
    const live = await this.live(id);
    return this.summary(clan, { clan_id: id, online: BigInt(live.online_count), updated_at: live.online_updated_at ? new Date(live.online_updated_at) : null });
  }
  async progression(id: string) {
    await this.detail(id);
    if (!this.database.client) throw new ServiceUnavailableException('Database unavailable');
    try {
      const rows = await this.database.client.$queryRaw<Array<{ clan_id: string; xp: bigint; level: number; reputation: number; statistics: unknown; version: bigint }>>`
        SELECT clan_id, xp, level, reputation, statistics, version FROM clan_progression WHERE clan_id=${id}::uuid`;
      const achievements = await this.database.client.$queryRaw<Array<{ code: string; progress: bigint; target: bigint; unlocked_at: Date | null }>>`
        SELECT code, progress, target, unlocked_at FROM clan_achievements WHERE clan_id=${id}::uuid ORDER BY code ASC`;
      const row = rows[0];
      return { clan_id: id, xp: Number(row?.xp ?? 0n), level: row?.level ?? 1, reputation: row?.reputation ?? 0,
        statistics: row?.statistics ?? {}, version: String(row?.version ?? 0n), achievements: CLAN_ACHIEVEMENTS.map(definition => {
          const value = achievements.find(item => item.code === definition.code);
          return { code: definition.code, progress: Number(value?.progress ?? 0n), target: definition.target, unlocked_at: value?.unlocked_at?.toISOString() ?? null };
        }) };
    } catch { throw new ServiceUnavailableException('Clan progression unavailable'); }
  }
  async live(id: string) {
    if (!this.database.client) throw new ServiceUnavailableException('Database unavailable');
    try {
      const rows = await this.database.client.$queryRaw<Array<{ online: bigint; updated_at: Date | null }>>`
        SELECT count(DISTINCT p.user_id) FILTER (WHERE p.online) AS online,
          (SELECT max(snapshot_at) FROM clan_server_presence WHERE snapshot_at>now()-interval '90 seconds') AS updated_at
        FROM clan_online_presence p WHERE p.clan_id=${id}::uuid AND p.last_seen_at > now() - interval '90 seconds'
          AND EXISTS (SELECT 1 FROM clan_server_presence s WHERE s.server_id=p.server_id AND s.snapshot_at>now()-interval '90 seconds')`;
      const row = rows[0];
      return { clan_id: id, online_count: Number(row?.online ?? 0n), online_updated_at: row?.updated_at?.toISOString() ?? null, freshness_seconds: 90 };
    } catch { throw new ServiceUnavailableException('Clan online data unavailable'); }
  }
  async events(id: string) {
    if (!this.database.client) throw new ServiceUnavailableException('Database unavailable');
    try {
      const rows = await this.database.client.$queryRaw<Array<{ event_id: string; kind: string; xp_delta: bigint; reputation_delta: number; occurred_at: Date }>>`
        SELECT event_id, kind, xp_delta, reputation_delta, occurred_at FROM clan_gameplay_events
        WHERE clan_id=${id}::uuid ORDER BY occurred_at DESC LIMIT 100`;
      return { clan_id: id, items: rows.map(row => ({ event_id: row.event_id, kind: row.kind, xp_delta: Number(row.xp_delta), reputation_delta: row.reputation_delta, occurred_at: row.occurred_at.toISOString() })) };
    } catch { throw new ServiceUnavailableException('Clan events unavailable'); }
  }
  async competitions(id: string) {
    await this.detail(id);
    if (!this.database.client) throw new ServiceUnavailableException('Database unavailable');
    try {
      const rows = await this.database.client.$queryRaw<Array<{ event_id: string; code: string; title: string; status: string; starts_at: Date; ends_at: Date; score: bigint; reward_granted_at: Date | null;kind:string;outcome:string|null;arena:unknown }>>`
        SELECT e.id AS event_id,e.code,e.title,e.status,e.starts_at,e.ends_at,COALESCE(s.score,0) AS score,s.reward_granted_at,
          COALESCE(e.payload->>'kind','BOSS_HUNT') AS kind,e.payload->>'outcome' AS outcome,e.payload->'arena' AS arena
        FROM clan_events e LEFT JOIN clan_event_scores s ON s.event_id=e.id AND s.clan_id=${id}::uuid
        WHERE s.clan_id IS NOT NULL OR e.status IN ('SCHEDULED','ACTIVE','FINISHED') ORDER BY e.starts_at DESC LIMIT 50`;
      const leaders=rows.length?await this.database.client.$queryRaw<Array<{event_id:string;short_code:string;score:bigint}>>`
        SELECT s.event_id,c.short_code,s.score FROM clan_event_scores s JOIN clans c ON c.id=s.clan_id
        WHERE s.event_id IN (${Prisma.join(rows.map(row=>Prisma.sql`${row.event_id}::uuid`))}) AND c.enabled=true AND c.archived_at IS NULL
        ORDER BY s.event_id,s.score DESC,c.short_code`:[];
      return { clan_id: id, items: rows.map(row => ({ event_id: row.event_id, code: row.code, title: row.title, status: row.status,kind:row.kind,outcome:row.outcome,arena:row.arena,
        starts_at: row.starts_at.toISOString(), ends_at: row.ends_at.toISOString(), score: Number(row.score), reward_granted_at: row.reward_granted_at?.toISOString() ?? null, leaderboard: leaders.filter(item=>item.event_id===row.event_id).map(item=>({short_code:item.short_code,score:Number(item.score)})) })) };
    } catch { throw new ServiceUnavailableException('Clan competitions unavailable'); }
  }
  async wars(id:string){
    await this.detail(id);
    if(!this.database.client)throw new ServiceUnavailableException('Database unavailable');
    const rows=await this.database.client.$queryRaw<Array<{id:string;attacker:string;defender:string;status:string;starts_at:Date;ends_at:Date;cooldown_ends_at:Date|null}>>`
      SELECT w.id,a.short_code AS attacker,d.short_code AS defender,
        CASE WHEN w.status='CANCELLED' THEN 'CANCELLED' WHEN now()<w.active_starts_at THEN 'PREPARATION'
          WHEN now()<w.active_ends_at THEN 'ACTIVE' WHEN now()<COALESCE(w.cooldown_ends_at,w.active_ends_at) THEN 'COOLDOWN' ELSE 'FINISHED' END AS status,
        w.active_starts_at AS starts_at,w.active_ends_at AS ends_at,w.cooldown_ends_at
      FROM clan_war_windows w JOIN clans a ON a.id=w.attacker_clan_id JOIN clans d ON d.id=w.defender_clan_id
      WHERE w.attacker_clan_id=${id}::uuid OR w.defender_clan_id=${id}::uuid ORDER BY w.created_at DESC LIMIT 50`;
    return {items:rows.map(row=>({...row,starts_at:row.starts_at.toISOString(),ends_at:row.ends_at.toISOString(),cooldown_ends_at:row.cooldown_ends_at?.toISOString()??null}))};
  }
  private summary(clan: Clan & { _count: { clan_members: number } }, live?: { clan_id?: string; online: bigint; updated_at: Date | null }) {
    return { id: clan.id, name: clan.name, short_code: clan.shortCode, description: clan.description,
      logo_url: clan.logoUrl, discord_url: clan.discordUrl, color: clan.color ?? null, member_count: clan._count.clan_members,
      online_count: live && live.updated_at && Date.now() - live.updated_at.getTime() <= 90_000 ? Number(live.online) : null,
      online_updated_at: live?.updated_at?.toISOString() ?? null };
  }
}
