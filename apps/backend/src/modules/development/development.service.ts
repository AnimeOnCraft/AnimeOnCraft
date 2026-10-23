import { HttpException, Inject, Injectable, NotFoundException, ServiceUnavailableException } from '@nestjs/common';
import { DatabaseService } from '../../infrastructure/database.service.js';
import { Prisma } from '../../generated/prisma/client.js';
import type { Clan } from '../../generated/prisma/client.js';
import type { ClanInput } from './clan-input.js';

function view(clan: Clan) {
  return { id: clan.id, name: clan.name, short_code: clan.shortCode, description: clan.description,
    discord_url: clan.discordUrl, enabled: clan.enabled, archived_at: clan.archivedAt?.toISOString() ?? null,
    version: clan.version.toString(), created_at: clan.createdAt.toISOString(), updated_at: clan.updatedAt.toISOString() };
}
function precondition(header: string | undefined): bigint {
  if (!header) throw new HttpException('If-Match required', 428);
  if (!/^"(?:0|[1-9][0-9]{0,18})"$/.test(header)) throw new HttpException('Invalid If-Match', 400);
  const version = BigInt(header.slice(1, -1));
  if (version > 9223372036854775807n) throw new HttpException('Invalid If-Match', 400);
  return version;
}

@Injectable()
export class DevelopmentService {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService) {}
  private get client() {
    if (!this.database.client) throw new ServiceUnavailableException();
    return this.database.client;
  }
  private async run<T>(work: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> {
    try { return await this.client.$transaction(work, { timeout: 5000, maxWait: 2000 }); }
    catch (error) {
      if (error instanceof HttpException) throw error;
      if (error instanceof Prisma.PrismaClientKnownRequestError && ['P2002', 'P2003'].includes(error.code)) {
        throw new HttpException('Resource conflict', 409);
      }
      throw new ServiceUnavailableException();
    }
  }
  async list() {
    try { return { items: (await this.client.clan.findMany({ orderBy: { codeKey: 'asc' }, take: 100 })).map(view) }; }
    catch { throw new ServiceUnavailableException(); }
  }
  async get(id: string) {
    return this.run(async (tx) => { const clan = await tx.clan.findUnique({ where: { id } }); if (!clan) throw new NotFoundException(); return view(clan); });
  }
  async create(input: ClanInput, requestId: string) {
    return this.run(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock_shared(171733)`;
      const clan = await tx.clan.create({ data: { name: input.name, shortCode: input.short_code, codeKey: input.short_code,
        description: input.description, discordUrl: input.discord_url, enabled: input.enabled } });
      await this.audit(tx, clan.id, 'CLAN_CREATED', requestId, null, view(clan));
      return view(clan);
    });
  }
  async change(id: string, input: ClanInput | null, header: string | undefined, requestId: string) {
    const expected = precondition(header);
    return this.run(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock_shared(171733)`;
      await tx.$queryRaw`SELECT id FROM clans WHERE id = ${id}::uuid FOR UPDATE`;
      const before = await tx.clan.findUnique({ where: { id } });
      if (!before) throw new NotFoundException();
      if (before.version !== expected) throw new HttpException('Stale resource version', 412);
      if (before.archivedAt) throw new HttpException('Archived resource', 409);
      if (!input) {
        const linked = await Promise.all([tx.clan_members.count({ where: { clan_id: id } }),
          tx.clan_applications.count({ where: { clan_id: id } }), tx.clan_manager_assignments.count({ where: { clan_id: id } })]);
        if (linked.some((count) => count > 0)) throw new HttpException('Linked clan requires the authorized archive workflow', 409);
      }
      const clan = await tx.clan.update({ where: { id }, data: input ? {
        name: input.name, shortCode: input.short_code, codeKey: input.short_code, description: input.description,
        discordUrl: input.discord_url, enabled: input.enabled, version: { increment: 1 }
      } : { enabled: false, archivedAt: new Date(), version: { increment: 1 } } });
      await this.audit(tx, id, input ? 'CLAN_UPDATED' : 'EMPTY_CLAN_ARCHIVED', requestId, view(before), view(clan));
      return view(clan);
    });
  }
  private async audit(tx: Prisma.TransactionClient, id: string, action: string, requestId: string,
    before: ReturnType<typeof view> | null, after: ReturnType<typeof view>) {
    // Audit contains identifiers/state/version only; descriptions and arbitrary input are omitted.
    const redacted = (value: ReturnType<typeof view>) => ({ enabled: value.enabled, archived_at: value.archived_at, version: value.version });
    await tx.audit_logs.create({ data: { actor_kind: 'DEVELOPMENT', action, target_type: 'clan', target_id: id,
      request_id: requestId, reason: 'Explicit local development base API',
      before_redacted: before ? redacted(before) : Prisma.DbNull, after_redacted: redacted(after) } });
  }
}
