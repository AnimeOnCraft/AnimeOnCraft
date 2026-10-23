/**
 * Integration API of the capture engine (game server → backend, service credential only).
 * Rejections that players should understand come back as 200 {accepted:false, reason}; malformed or
 * inconsistent requests are 400/409 so the server mod never mistakes them for success.
 */
import { Body, Controller, Headers, HttpCode, HttpException, HttpStatus, Inject, Post } from '@nestjs/common';
import type { Prisma } from '../generated/prisma/client.js';
import { DatabaseService } from '../infrastructure/database.service.js';
import { authenticateServer } from '../integration-auth.js';
import { adminCapture, CaptureError, declareCapture, finishCapture, refreshedSite, snapshotCapture, syncCapture, voidCapture } from './lifecycle.js';

type Body = Record<string, unknown>;
const text = (value: unknown) => typeof value === 'string' ? value : '';
const integer = (value: unknown) => typeof value === 'number' && Number.isInteger(value) ? value : -1;

@Controller('integration/v1/capture')
export class CaptureIntegrationController {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService) {}

  private async run<T>(authorization: string | undefined, body: unknown, work: (tx: Prisma.TransactionClient, input: Body) => Promise<T>) {
    authenticateServer(authorization, body);
    const client = this.database.client;
    if (!client) throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE);
    try { return await client.$transaction(tx => work(tx, body as Body), { timeout: 15000 }); }
    catch (failure) {
      if (failure instanceof HttpException) throw failure;
      throw new HttpException(failure instanceof CaptureError ? failure.message : 'Capture request rejected', failure instanceof CaptureError ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  @Post('sync') @HttpCode(200) sync(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => syncCapture(tx, new Date(),
      Array.isArray(input.finished) ? input.finished.filter((id): id is string => typeof id === 'string') : []));
  }

  @Post('declare') @HttpCode(200) declare(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => {
      if (input.auto_neutral !== undefined && typeof input.auto_neutral !== 'boolean') throw new CaptureError('Invalid auto_neutral');
      return declareCapture(tx, { serverId: text(input.server_id), pointCode: text(input.point_code),
      actorUserId: text(input.actor_user_id), clanCode: text(input.clan_code), playersInZone: integer(input.players_in_zone),
      defendersOnline: integer(input.defenders_online), now: new Date(), autoNeutral: input.auto_neutral === true });
    });
  }

  @Post('snapshot') @HttpCode(200) snapshot(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => snapshotCapture(tx, { serverId: text(input.server_id), eventId: text(input.event_id),
      phase: text(input.phase) as 'ACTIVE', scores: input.scores, now: new Date(),killLimits:input.kill_limits }));
  }

  /** Delivered through the mod's durable outbox; the reply echoes event_id so the outbox can drop the file. */
  @Post('finish') @HttpCode(200) finish(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => finishCapture(tx, { serverId: text(input.server_id), eventId: text(input.event_id),
      winnerCode: input.winner_clan_code === null ? null : text(input.winner_clan_code), scores: input.scores, now: new Date() }));
  }

  @Post('void') @HttpCode(200) void(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => voidCapture(tx, { serverId: text(input.server_id), eventId: text(input.event_id), reason: text(input.reason), now: new Date() }));
  }

  @Post('admin') @HttpCode(200) admin(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => {
      const action = text(input.action);
      if (!['CANCEL', 'RESET', 'TRANSFER', 'REFRESH', 'RELEASE'].includes(action)) throw new CaptureError('Unknown action');
      return adminCapture(tx, { action: action as 'CANCEL', actorUserId: text(input.actor_user_id), pointCode: text(input.point_code),
        clanCode: input.clan_code === undefined ? undefined : text(input.clan_code), reason: input.reason === undefined ? undefined : text(input.reason), now: new Date() });
    });
  }

  @Post('refreshed') @HttpCode(200) refreshed(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    return this.run(authorization, body, (tx, input) => refreshedSite(tx, { pointCode: text(input.point_code), dueAt: new Date(text(input.due_at)),
      restored: integer(input.restored), multiplier: typeof input.multiplier === 'number' ? input.multiplier : -1, now: new Date() }));
  }
}
