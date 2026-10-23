import { cancelCompetition, cancelWar } from './clan-gameplay-admin.js';
import {currentWorldBoss,expireWorldBosses,finishWorldBoss,startWorldBoss,worldBossProgress} from './world-boss.js';
import { recordPresence } from './clan-presence.js';
import { scheduleWar, captureTerritory, advanceWars } from './clan-wars.js';
import { settleBossCompetitions } from './clan-competitions.js';
import { recordBossKill } from './clan-gameplay.js';
import { Body, Controller, Get, Headers, HttpCode, HttpException, HttpStatus, Inject, Module, Post } from '@nestjs/common';
import { INTEGRATION_PROTOCOL_MAJOR } from '@animeoncraft/contracts';
import { authenticateServer, authenticateServiceKey } from './integration-auth.js';
import { CaptureIntegrationController } from './capture/capture.controller.js';
import { WorldIntegrationController } from './modules/world-map/world-integration.controller.js';
import { ServerMenuController } from './server-menu/server-menu.controller.js';
import { GameWardrobeModule } from './wardrobe/wardrobe.controller.js';
import { InfrastructureModule } from './infrastructure/infrastructure.module.js';
import { DatabaseService } from './infrastructure/database.service.js';

@Controller('integration/v1')
class IntegrationHealthController {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService) {}
  @Get('health/live') live() { return { status: 'READY', protocol_major: INTEGRATION_PROTOCOL_MAJOR, service_auth_available: Boolean(process.env.AOC_GAME_SERVER_KEY), database: Boolean(this.database.client) } as const; }

  @Post('servers/heartbeat') @HttpCode(200) async heartbeat(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    this.authenticate(authorization, body);
    if (!this.database.client) throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE);
    const input = body as { server_id: string; snapshot_at: string; players?: { user_id: string }[] };
    if (!Array.isArray(input.players) || input.players.length > 200 || input.players.some(player => !player || typeof player.user_id !== 'string' || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(player.user_id))) throw new HttpException('Invalid presence snapshot', HttpStatus.BAD_REQUEST);
    const observedAt=new Date(input.snapshot_at);
    if (!Number.isFinite(observedAt.getTime())||Math.abs(Date.now()-observedAt.getTime())>90000) throw new HttpException('Invalid snapshot time',HttpStatus.BAD_REQUEST);
    const players = input.players;
    try {
      const memberships=await this.database.client.$transaction(async tx => {
        await settleBossCompetitions(tx);
        await expireWorldBosses(tx);
        await advanceWars(tx);
        await recordPresence(tx,input.server_id,observedAt,players);
        return tx.$queryRaw<Array<{user_id:string;clan_code:string|null}>>`SELECT p.user_id,c.code_key AS clan_code
          FROM clan_online_presence p JOIN users u ON u.id=p.user_id
          LEFT JOIN clan_members m ON m.user_id=u.id AND m.ended_at IS NULL
          LEFT JOIN clans c ON c.id=m.clan_id AND c.enabled=true AND c.archived_at IS NULL
          WHERE p.server_id=${input.server_id}::uuid AND p.online=true AND u.state='ACTIVE'`;
      });
      return { accepted: true, server_id: input.server_id, players: players.length, memberships, protocol_major: INTEGRATION_PROTOCOL_MAJOR };
    } catch { throw new HttpException('Heartbeat rejected', HttpStatus.BAD_REQUEST); }
  }

  @Get('territories/control') async controls(@Headers('authorization') authorization: string | undefined) {
    this.authenticateHeader(authorization);
    if (!this.database.client) throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE);
    try {
      const rows = await this.database.client.$queryRaw<Array<{ territory_code: string; short_code: string }>>`SELECT t.territory_code,c.code_key AS short_code FROM clan_territory_control t JOIN clans c ON c.id=t.controller_clan_id`;
      const wars = await this.database.client.$queryRaw<Array<{id:string;attacker:string;defender:string;starts_at:Date;ends_at:Date}>>`
        SELECT w.id,a.code_key AS attacker,d.code_key AS defender,w.active_starts_at AS starts_at,w.active_ends_at AS ends_at
        FROM clan_war_windows w JOIN clans a ON a.id=w.attacker_clan_id JOIN clans d ON d.id=w.defender_clan_id
        WHERE w.status<>'CANCELLED' AND w.active_ends_at>now() ORDER BY w.active_starts_at LIMIT 100`;
      return { items: rows, wars: wars.map(war => ({...war,starts_at:war.starts_at.toISOString(),ends_at:war.ends_at.toISOString()})) };
    } catch { throw new HttpException('Territory control unavailable', HttpStatus.SERVICE_UNAVAILABLE); }
  }

  @Post('events') @HttpCode(200) async event(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    this.authenticate(authorization, body);
    if (!this.database.client) throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE);
    const input = body as { server_id: string; event_id: string; clan_id?: string; clan_code?: string; kind: string; xp_delta?: number; reputation_delta?: number; occurred_at?: string; payload?: unknown };
    if (!/^[0-9a-f-]{36}$/.test(input.event_id) || (!input.clan_id && !/^[A-Z0-9]{2,12}$/.test(input.clan_code ?? '')) || (input.clan_id && !/^[0-9a-f-]{36}$/.test(input.clan_id)) || !/^[A-Z0-9_.:-]{1,64}$/.test(input.kind)) throw new HttpException('Invalid gameplay event', HttpStatus.BAD_REQUEST);
    if (Boolean(input.clan_id) === Boolean(input.clan_code) || (input.occurred_at !== undefined && typeof input.occurred_at !== 'string')) throw new HttpException('Ambiguous event identity', HttpStatus.BAD_REQUEST);
    if (input.kind !== 'BOSS_KILL' || (input.xp_delta !== undefined && input.xp_delta !== 100) || (input.reputation_delta !== undefined && input.reputation_delta !== 5)) throw new HttpException('Unsupported gameplay reward', HttpStatus.BAD_REQUEST);
    const occurredAt = input.occurred_at ? new Date(input.occurred_at) : new Date();
    if (!Number.isFinite(occurredAt.getTime()) || occurredAt.getTime() > Date.now() + 60000) throw new HttpException('Invalid event time', HttpStatus.BAD_REQUEST);
    try {
      const clans = await this.database.client.$queryRaw<Array<{ id: string }>>`SELECT id FROM clans WHERE
        (${input.clan_id ?? null}::uuid IS NOT NULL AND id=${input.clan_id ?? null}::uuid OR code_key=${input.clan_code ?? null})
        AND enabled=true AND archived_at IS NULL LIMIT 1`;
      const clanId = clans[0]?.id; if (!clanId) throw new Error('Unknown clan');
      const result = await this.database.client.$transaction(tx => recordBossKill(tx, {
        eventId: input.event_id, serverId: input.server_id, clanId, occurredAt,
      }));
      return { accepted: true, ...result, event_id: input.event_id, clan_id: clanId };
    } catch { throw new HttpException('Gameplay event rejected', HttpStatus.BAD_REQUEST); }
  }

  @Post('competitions/start') @HttpCode(200) async startCompetition(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    this.authenticate(authorization, body);
    if (!this.database.client) throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE);
    const input = body as { actor_user_id: string; duration_minutes: number };
    if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(input.actor_user_id)) throw new HttpException('Invalid actor', HttpStatus.BAD_REQUEST);
    try {
      return await this.database.client.$transaction(async tx => {
        await settleBossCompetitions(tx);
        return startWorldBoss(tx, input.actor_user_id, (body as {server_id:string}).server_id, input.duration_minutes);
      });
    } catch { throw new HttpException('Competition start rejected: owner or duration invalid, or another competition is active', HttpStatus.CONFLICT); }
  }

  @Get('competitions/world-boss') async worldBoss(@Headers('authorization') authorization:string|undefined){
    this.authenticateHeader(authorization);
    if(!this.database.client)throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    return {item:await this.database.client.$transaction(tx=>currentWorldBoss(tx,process.env.AOC_GAME_SERVER_ID??''))};
  }
  @Post('competitions/world-boss/progress') @HttpCode(200) async bossProgress(@Headers('authorization') authorization:string|undefined,@Body() body:unknown){
    this.authenticate(authorization,body);
    if(!this.database.client)throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    try{return await this.database.client.$transaction(tx=>worldBossProgress(tx,body as Parameters<typeof worldBossProgress>[1]));}
    catch{throw new HttpException('World boss progress rejected',HttpStatus.CONFLICT);}
  }
  @Post('competitions/world-boss/result') @HttpCode(200) async bossResult(@Headers('authorization') authorization:string|undefined,@Body() body:unknown){
    this.authenticate(authorization,body);
    if(!this.database.client)throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    try{return await this.database.client.$transaction(tx=>finishWorldBoss(tx,body as Parameters<typeof finishWorldBoss>[1]));}
    catch{throw new HttpException('World boss result rejected',HttpStatus.CONFLICT);}
  }
  @Post('competitions/cancel') @HttpCode(200) async cancelCompetition(@Headers('authorization') authorization:string|undefined,@Body() body:unknown){
    this.authenticate(authorization,body);
    if(!this.database.client)throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    const input=body as {actor_user_id:string};
    try{return await this.database.client.$transaction(tx=>cancelCompetition(tx,input.actor_user_id));}
    catch{throw new HttpException('Competition cancellation rejected',HttpStatus.CONFLICT);}
  }
  @Post('wars/cancel') @HttpCode(200) async cancelWar(@Headers('authorization') authorization:string|undefined,@Body() body:unknown){
    this.authenticate(authorization,body);
    if(!this.database.client)throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    const input=body as {actor_user_id:string;attacker:string;defender:string};
    try{return await this.database.client.$transaction(tx=>cancelWar(tx,input.actor_user_id,input.attacker,input.defender));}
    catch{throw new HttpException('War cancellation rejected',HttpStatus.CONFLICT);}
  }

  @Post('wars/schedule') @HttpCode(200) async warSchedule(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    this.authenticate(authorization,body);
    if (!this.database.client) throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    const input=body as {actor_user_id:string;attacker:string;defender:string;preparation_minutes:number;raid_minutes:number};
    if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(input.actor_user_id)) throw new HttpException('Invalid actor',HttpStatus.BAD_REQUEST);
    try { return await this.database.client.$transaction(tx=>scheduleWar(tx,{actorId:input.actor_user_id,attacker:input.attacker,defender:input.defender,preparationMinutes:input.preparation_minutes,raidMinutes:input.raid_minutes})); }
    catch { throw new HttpException('War schedule rejected',HttpStatus.CONFLICT); }
  }

  @Post('territories/capture') @HttpCode(200) async capture(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    this.authenticate(authorization,body);
    if (!this.database.client) throw new HttpException('Database unavailable',HttpStatus.SERVICE_UNAVAILABLE);
    const input=body as {server_id:string;event_id:string;war_window_id:string;completed_at:string;territory_code:string;attacker_clan_code:string;defender_clan_code:string};
    const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
    if (!uuid.test(input.event_id)||!uuid.test(input.war_window_id)) throw new HttpException('Invalid capture identity',HttpStatus.BAD_REQUEST);
    try {
      const result=await this.database.client.$transaction(tx=>captureTerritory(tx,{eventId:input.event_id,serverId:input.server_id,warId:input.war_window_id,territory:input.territory_code,attacker:input.attacker_clan_code,defender:input.defender_clan_code,completedAt:new Date(input.completed_at)}));
      return {accepted:true,...result,event_id:input.event_id,territory_code:input.territory_code,controller_clan_code:input.attacker_clan_code};
    } catch { throw new HttpException('Territory capture rejected',HttpStatus.CONFLICT); }
  }

  private authenticate(authorization: string | undefined, body: unknown): void { authenticateServer(authorization, body); }
  private authenticateHeader(authorization: string | undefined): void { authenticateServiceKey(authorization); }
}

@Module({ imports: [InfrastructureModule,GameWardrobeModule], controllers: [IntegrationHealthController, CaptureIntegrationController, WorldIntegrationController, ServerMenuController] })
export class IntegrationAppModule {}
