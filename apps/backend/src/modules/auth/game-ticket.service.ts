import { ForbiddenException, Inject, Injectable, ServiceUnavailableException, UnauthorizedException } from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';
import type { FastifyRequest } from 'fastify';
import type { Prisma } from '../../generated/prisma/client.js';
import { AuthService, type Principal } from './auth.service.js';
import { digest, opaque } from './auth-crypto.js';
import { skinView } from './player-skin.service.js';
import {awardBetaCape,equippedCape} from './player-cape.service.js';
import {launcherReleasePolicy,requireLauncherRelease} from './launcher-release.js';

export const GAME_TICKET_PATTERN = /^gt_[A-Za-z0-9_-]{43}(?![\s\S])/;
const denied = () => new ForbiddenException('Game admission denied');

function configuration() {
  const server = process.env.AOC_GAME_SERVER_ID ?? '';
  const secret = process.env.AOC_GAME_SERVER_KEY ?? '';
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}(?![\s\S])/.test(server) ||
    !/^[a-f0-9]{64}(?![\s\S])/.test(secret)) throw new ServiceUnavailableException('Game server is not configured');
  return { server, secret };
}

@Injectable()
export class GameTicketService {
  constructor(@Inject(AuthService) private readonly auth: AuthService) {}

  authenticateServer(request: FastifyRequest): string {
    const config = configuration();
    // A server credential is independent of player tokens and never sent to the client.
    const supplied = /^Bearer ([a-f0-9]{64})(?![\s\S])/.exec(request.headers.authorization ?? '')?.[1] ?? '';
    if (request.headers.origin || request.headers.cookie || request.headers['sec-fetch-site'] ||
      supplied.length !== config.secret.length || !timingSafeEqual(Buffer.from(supplied), Buffer.from(config.secret))) throw new UnauthorizedException();
    return config.server;
  }

  private async serverReady(tx: Prisma.TransactionClient, id: string): Promise<boolean> {
    await tx.$queryRaw`SELECT id FROM game_servers WHERE id = ${id}::uuid FOR SHARE`;
    const server = await tx.game_servers.findUnique({ where: { id } });
    return !!server && !server.maintenance;
  }

  private async allowed(tx: Prisma.TransactionClient, user: string, now: Date): Promise<boolean> {
    if (await tx.bans.count({ where: { user_id: user, revoked_at: null, OR: [{ expires_at: null }, { expires_at: { gt: now } }] } })) return false;
    return !!(await tx.whitelist.count({ where: {
      user_id: user, revoked_at: null,
      AND: [{ OR: [{ expires_at: null }, { expires_at: { gt: now } }] },
        { OR: [{ kind: 'OWNER' }, { kind: 'MEMBERSHIP', clan_members: { ended_at: null, clans: { archivedAt: null } } }] }]
    } }));
  }

  async issue(principal: Principal, launcherVersion?:string) {
    const config = configuration();
    if(requireLauncherRelease())await launcherReleasePolicy.assertCurrent(launcherVersion);
    const ticket = opaque('gt');
    const result = await this.auth.transaction(async tx => {
      const user = await this.auth.lockUser(tx, principal.user.id);
      const session = await tx.sessions.findUnique({ where: { id: principal.session.id } });
      const token = await tx.auth_tokens.findUnique({ where: { id: principal.access_token_id } });
      if (!user || !(await this.auth.usable(tx, user)) || !session || session.user_id !== user.id || session.client_kind !== 'LAUNCHER' ||
        !(await this.auth.sessionValid(tx, user, session, 'LAUNCHER')) || !token || token.session_id !== session.id ||
        token.kind !== 'ACCESS' || token.revoked_at || token.expires_at <= new Date()) return null;
      if (user.passwordChangeRequired) return null;
      const profile = await tx.game_profiles.findUnique({ where: { user_id: user.id } });
      const now = new Date();
      if (!profile || !(await this.serverReady(tx, config.server)) || !(await this.allowed(tx, user.id, now))) return null;
      await tx.game_tickets.updateMany({ where: { user_id: user.id, server_id: config.server, consumed_at: null, revoked_at: null }, data: { revoked_at: now } });
      const expires = new Date(now.getTime() + 60000);
      await tx.game_tickets.create({ data: { token_hash: digest(ticket), user_id: user.id, session_id: session.id, server_id: config.server, created_at: now, expires_at: expires } });
      await tx.$executeRaw`UPDATE game_tickets SET launcher_version=${launcherVersion??null} WHERE token_hash=${digest(ticket)}`;
      return { ticket, server_id: config.server, game_name: profile.game_name, minecraft_uuid: profile.minecraft_uuid, expires_at: expires.toISOString() };
    });
    if (!result) throw denied();
    return result;
  }

  /** Bind private in-game account operations to the latest joined connection and a usable launcher session. */
  async admittedPlayer(tx:Prisma.TransactionClient,server:string,data:{connection_id:string;minecraft_uuid:string}):Promise<string>{
    const initial=await tx.game_tickets.findFirst({where:{server_id:server,connection_id:data.connection_id}});
    if(!initial)throw denied();
    const user=await this.auth.lockUser(tx,initial.user_id);
    const ticket=await tx.game_tickets.findUnique({where:{token_hash:initial.token_hash}});
    const profile=await tx.game_profiles.findUnique({where:{user_id:initial.user_id}});
    const session=ticket?await tx.sessions.findUnique({where:{id:ticket.session_id}}):null;
    const latest=await tx.game_tickets.findFirst({where:{user_id:initial.user_id,server_id:server,joined_at:{not:null}},orderBy:[{joined_at:'desc'},{consumed_at:'desc'},{created_at:'desc'}]});
    if(!user||!ticket?.consumed_at||!ticket.joined_at||ticket.revoked_at||ticket.connection_id!==data.connection_id||
       latest?.connection_id!==data.connection_id||profile?.minecraft_uuid!==data.minecraft_uuid||!session||
       session.user_id!==user.id||!(await this.auth.sessionValid(tx,user,session,'LAUNCHER'))||
       user.passwordChangeRequired||!(await this.auth.usable(tx,user))||!(await this.serverReady(tx,server))||
       !(await this.allowed(tx,user.id,new Date())))throw denied();
    return user.id;
  }

  async redeem(server: string, data: { ticket: string; game_name: string; minecraft_uuid: string; connection_id: string }) {
    // Find the owner, then use the same user lock as account/grant/ban mutations.
    const hash = digest(data.ticket);
    const initial = await this.auth.db.game_tickets.findUnique({ where: { token_hash: hash } });
    if (!initial || initial.server_id !== server) throw denied();
    if(requireLauncherRelease()){
      const rows=await this.auth.db.$queryRaw<{launcher_version:string|null}[]>`SELECT launcher_version FROM game_tickets WHERE token_hash=${hash}`;
      await launcherReleasePolicy.assertCurrent(rows[0]?.launcher_version??undefined);
    }
    const result = await this.auth.transaction(async tx => {
      const user = await this.auth.lockUser(tx, initial.user_id);
      const ticket = await tx.game_tickets.findUnique({ where: { token_hash: hash } });
      if (!user || !ticket || ticket.server_id !== server || ticket.consumed_at || ticket.revoked_at || ticket.expires_at <= new Date()) return null;
      const session = await tx.sessions.findUnique({ where: { id: ticket.session_id } });
      if (!(await this.auth.usable(tx, user)) || user.passwordChangeRequired || !session || session.user_id !== user.id ||
        !(await this.auth.sessionValid(tx, user, session, 'LAUNCHER'))) return null;
      const profile = await tx.game_profiles.findUnique({ where: { user_id: user.id } });
      const now = new Date();
      if (!profile || profile.game_name !== data.game_name || profile.minecraft_uuid !== data.minecraft_uuid ||
        !(await this.serverReady(tx, server)) || !(await this.allowed(tx, user.id, now))) return null;
      const member = await tx.clan_members.findFirst({ where: { user_id: user.id, ended_at: null, clans: { archivedAt: null } }, include: { clans: true } });
      const consumed = await tx.game_tickets.updateMany({ where: { token_hash: hash, consumed_at: null, revoked_at: null, expires_at: { gt: new Date() } }, data: { consumed_at: new Date(), connection_id:data.connection_id } });
      if (consumed.count !== 1) return null;
      return { accepted: true as const, connection_id: data.connection_id, user_id: user.id,
        game_name: profile.game_name, minecraft_uuid: profile.minecraft_uuid,
        clan: member ? { id: member.clan_id, short_code: member.clans.shortCode } : null,
        cape_id:await equippedCape(tx,user.id),
        ...skinView(await tx.player_skins.findUnique({where:{user_id:user.id}})) };
    });
    if (!result) throw denied();
    return result;
  }

  async joined(server:string,data:{connection_id:string;minecraft_uuid:string}){
    const initial=await this.auth.db.game_tickets.findFirst({where:{server_id:server,connection_id:data.connection_id}});
    if(!initial)throw denied();
    const result=await this.auth.transaction(async tx=>{
      const user=await this.auth.lockUser(tx,initial.user_id);
      const ticket=await tx.game_tickets.findUnique({where:{token_hash:initial.token_hash}});
      const profile=await tx.game_profiles.findUnique({where:{user_id:initial.user_id}});
      const now=new Date();
      if(!user||!ticket?.consumed_at||ticket.revoked_at||ticket.connection_id!==data.connection_id||ticket.server_id!==server||profile?.minecraft_uuid!==data.minecraft_uuid||!(await this.auth.usable(tx,user))||!(await this.serverReady(tx,server))||!(await this.allowed(tx,user.id,now)))return null;
      if(!ticket.joined_at&&now.getTime()-ticket.consumed_at.getTime()>300000)return null;
      const joined=ticket.joined_at??now;
      if(!ticket.joined_at)await tx.game_tickets.update({where:{token_hash:ticket.token_hash},data:{joined_at:joined}});
      const awarded=await awardBetaCape(tx,user.id,joined);
      return {joined:true as const,awarded,cape_id:await equippedCape(tx,user.id)};
    });
    if(!result)throw denied();
    return result;
  }
}
