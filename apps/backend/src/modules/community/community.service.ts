import { ForbiddenException, HttpException, Inject, Injectable, NotFoundException, ServiceUnavailableException, UnauthorizedException } from '@nestjs/common';
import { createHash } from 'node:crypto';
import { Prisma, type Clan, type clan_applications, type clan_members, type User } from '../../generated/prisma/client.js';
import { PaginationService, type PageInput } from '../../infrastructure/pagination.js';
import { AuthService, type Principal } from '../auth/auth.service.js';
import type { ClanInput } from '../development/clan-input.js';
import type { BanInput, GrantInput, ManagerInput } from './community-input.js';

type Tx = Prisma.TransactionClient;
type Result = Record<string, unknown>;
type Scope = 'own' | 'manager' | 'owner' | 'remove' | 'launcher_owner';
export interface Command { route: string; method: string; key: string | undefined; match: string | undefined; body: object; request_id: string }
interface Context { actor: User; clan: Clan | null }
const conflict = () => new HttpException('Domain conflict', 409);
export function expectedVersion(header: string | undefined): bigint {
  if (!header) throw new HttpException('If-Match required', 428);
  if (!/^"(?:0|[1-9][0-9]{0,18})"$/.test(header) || BigInt(header.slice(1,-1)) > 9223372036854775807n) throw new HttpException('Invalid If-Match', 400);
  return BigInt(header.slice(1,-1));
}
export const checkVersion = (version: bigint, command: Command) => { if (version !== expectedVersion(command.match)) throw new HttpException('Stale resource version', 412); };
export const clanView = (clan: Clan) => ({ id: clan.id, name: clan.name, short_code: clan.shortCode, description: clan.description,
  discord_url: clan.discordUrl, logo_url: clan.logoUrl, enabled: clan.enabled, archived_at: clan.archivedAt?.toISOString() ?? null, version: clan.version.toString() });
const appView = (row: clan_applications) => ({ id: row.id, user_id: row.user_id, clan_id: row.clan_id, message: row.message,
  status: row.status, version: row.version.toString(), created_at: row.created_at.toISOString(), decided_at: row.decided_at?.toISOString() ?? null, reason: row.reason });
const memberView = (row: clan_members) => ({ id: row.id, user_id: row.user_id, clan_id: row.clan_id, joined_at: row.joined_at.toISOString(), version: row.version.toString() });

@Injectable()
export class CommunityService {
  constructor(@Inject(AuthService) private readonly auth: AuthService, @Inject(PaginationService) private readonly pagination: PaginationService) {}

  private async execute(principal: Principal, clanId: string | null, targets: string[], scope: Scope, command: Command | null,
    work: (tx: Tx, context: Context) => Promise<Result>, archive = false, redact?: (result: Result) => Result): Promise<Result> {
    if (command && (!command.key || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(command.key))) throw new HttpException('Idempotency-Key UUID required', 400);
    const fingerprint = command ? createHash('sha256').update(JSON.stringify([command.method,command.route,Object.fromEntries(Object.entries(command.body).sort(([a],[b])=>a.localeCompare(b))),command.match ?? null])).digest('hex') : null;
    for (let attempt=0; attempt<3; attempt++) {
      try {
        const outcome = await this.auth.transaction(async tx => {
          if (clanId) await tx.$queryRaw`SELECT id FROM clans WHERE id=${clanId}::uuid FOR UPDATE`;
          const clan = clanId ? await tx.clan.findUnique({ where: { id: clanId } }) : null;
          if (clanId && !clan) throw new NotFoundException();
          if (archive && clanId) {
            const affected = await Promise.all([
              tx.clan_members.findMany({where:{clan_id:clanId,ended_at:null},select:{user_id:true}}),
              tx.clan_applications.findMany({where:{clan_id:clanId,status:'PENDING'},select:{user_id:true}}),
              tx.clan_manager_assignments.findMany({where:{clan_id:clanId,ended_at:null},select:{user_id:true}})
            ]);
            targets = affected.flat().map(row=>row.user_id);
          }
          const ids = [...new Set([principal.user.id,...targets.map(id=>id.toLowerCase())])].sort();
          for (const id of ids) await this.auth.lockUser(tx,id);
          const actor = await tx.user.findUnique({where:{id:principal.user.id}});
          const session = await tx.sessions.findUnique({where:{id:principal.session.id}});
          const token = await tx.auth_tokens.findUnique({where:{id:principal.access_token_id}});
          if (!actor || !(await this.auth.usable(tx,actor)) || !session || !token || token.session_id !== session.id || token.kind !== 'ACCESS'
            || token.revoked_at || token.expires_at <= new Date() || !(await this.auth.sessionValid(tx,actor,session,principal.session.client_kind))) return null;
          if (actor.passwordChangeRequired) throw new HttpException({code:'PASSWORD_CHANGE_REQUIRED',message:'Change the temporary password first'},403);
          if (scope === 'launcher_owner') {
            if (session.client_kind !== 'LAUNCHER' || actor.systemRole !== 'OWNER') throw new ForbiddenException();
          } else if (scope !== 'own') {
            if (session.client_kind !== 'WEB_ADMIN') throw new ForbiddenException('Management requires MFA browser session');
            if (scope === 'owner' && actor.systemRole !== 'OWNER') throw new ForbiddenException();
            if (actor.systemRole !== 'OWNER' && clanId) {
              const assignment = await tx.clan_manager_assignments.findFirst({where:{user_id:actor.id,clan_id:clanId,ended_at:null}});
              if (!assignment || clan?.archivedAt) throw new NotFoundException();
              if (scope === 'remove' && !assignment.can_remove_members) throw new ForbiddenException();
            }
            if (command && (!session.last_step_up_at || session.last_step_up_at.getTime() < Date.now()-600000)) throw new HttpException({code:'MFA_REQUIRED',message:'Recent step-up required'},403);
          }
          if (command) {
            const where = {principal_id_route_key:{principal_id:actor.id,route:command.route,key:command.key!}};
            const prior = await tx.idempotency_keys.findUnique({where});
            if (prior && prior.expires_at > new Date()) {
              if (prior.request_digest !== fingerprint) throw conflict();
              return prior.result as Result;
            }
            const result = await work(tx,{actor,clan});
            const data = {request_digest:fingerprint!,result:(redact ? redact(result) : result) as Prisma.InputJsonObject,created_at:new Date(),expires_at:new Date(Date.now()+86400000)};
            await tx.idempotency_keys.upsert({where,create:{principal_id:actor.id,route:command.route,key:command.key!,...data},update:data});
            return result;
          }
          return work(tx,{actor,clan});
        });
        if (!outcome) throw new UnauthorizedException();
        return outcome;
      } catch (error) {
        if (error instanceof HttpException) throw error;
        if (error instanceof Prisma.PrismaClientKnownRequestError) {
          if (command && (error.code === 'P2034' || (error.code === 'P2010' && ['40P01','40001'].includes(String(error.meta?.code)))) && attempt<2) { await new Promise(resolve=>setTimeout(resolve,20+Math.random()*50)); continue; }
          if (['P2002','P2003'].includes(error.code)) throw conflict();
        }
        throw new ServiceUnavailableException();
      }
    }
    throw new ServiceUnavailableException();
  }
  private async audit(tx: Tx, actor: string, action: string, target: string, command: Command, state: Prisma.InputJsonObject = {}) {
    await tx.audit_logs.create({data:{actor_kind:'USER',actor_user_id:actor,action,target_type:'community',target_id:target,
      request_id:command.request_id,reason:'reason' in command.body ? String(command.body.reason) : 'Clan workflow',after_redacted:state}});
  }
  private async touch(tx: Tx, id: string) { return tx.clan.update({where:{id},data:{version:{increment:1}}}); }
  private async event(tx: Tx, id: string, type: string) {
    const user = await tx.user.update({where:{id},data:{accessEpoch:{increment:1},version:{increment:1}}});
    await tx.outbox_events.create({data:{type,schema_version:1,aggregate_id:id,aggregate_version:user.version,
      payload:{user_id:id,access_epoch:user.accessEpoch.toString()},occurred_at:new Date()}});
  }
  private async eligible(tx: Tx, id: string) {
    const user = await tx.user.findUnique({where:{id}});
    if (!user || user.state !== 'ACTIVE') throw conflict();
    if (await tx.bans.count({where:{user_id:id,revoked_at:null,OR:[{expires_at:null},{expires_at:{gt:new Date()}}]}})) throw conflict();
    if (!(await tx.game_profiles.count({where:{user_id:id}}))) throw conflict();
    return user;
  }
  private open(clan: Clan | null) { if (!clan?.enabled || clan.archivedAt) throw conflict(); }
  private async admit(tx: Tx, actor: string, clan: string, user: string, reason: string, application?: string) {
    await this.eligible(tx,user);
    if (await tx.clan_members.count({where:{user_id:user,ended_at:null}})) throw conflict();
    const member = await tx.clan_members.create({data:{user_id:user,clan_id:clan,accepted_application_id:application ?? null}});
    await tx.whitelist.create({data:{user_id:user,kind:'MEMBERSHIP',membership_id:member.id,granted_by:actor,reason}});
    await this.event(tx,user,'CLAN_MEMBERSHIP_GRANTED');
    return member;
  }
  private async endMember(tx: Tx, member: clan_members, reason: string) {
    await tx.clan_members.update({where:{id:member.id},data:{ended_at:new Date(),end_reason:reason,version:{increment:1}}});
    await tx.whitelist.updateMany({where:{membership_id:member.id,revoked_at:null},data:{revoked_at:new Date(),version:{increment:1}}});
    await this.event(tx,member.user_id,'CLAN_MEMBERSHIP_REVOKED');
  }

  create(principal: Principal, body: ClanInput, command: Command) {
    return this.execute(principal,null,[],'owner',command,async(tx,{actor})=>{
      const clan = await tx.clan.create({data:{name:body.name,shortCode:body.short_code,codeKey:body.short_code,description:body.description,discordUrl:body.discord_url,enabled:body.enabled}});
      await this.audit(tx,actor.id,'CLAN_CREATED',clan.id,command,{version:'0'}); return clanView(clan);
    });
  }
  edit(principal: Principal, id: string, body: ClanInput, command: Command) {
    return this.execute(principal,id,[],'owner',command,async(tx,{actor,clan})=>{
      checkVersion(clan!.version,command); if (clan!.archivedAt) throw conflict();
      const updated = await tx.clan.update({where:{id},data:{name:body.name,shortCode:body.short_code,codeKey:body.short_code,description:body.description,discordUrl:body.discord_url,enabled:body.enabled,version:{increment:1}}});
      await this.audit(tx,actor.id,'CLAN_UPDATED',id,command,{version:updated.version.toString(),enabled:updated.enabled}); return clanView(updated);
    });
  }
  apply(principal: Principal, id: string, message: string, command: Command) {
    return this.execute(principal,id,[],'own',command,async(tx,{actor,clan})=>{
      this.open(clan); await this.eligible(tx,actor.id);
      if (await tx.clan_members.count({where:{user_id:actor.id,ended_at:null}})) throw conflict();
      const application = await tx.clan_applications.create({data:{user_id:actor.id,clan_id:id,message}});
      await tx.application_history.create({data:{application_id:application.id,status:'PENDING',actor_user_id:actor.id}});
      await this.touch(tx,id); await this.audit(tx,actor.id,'APPLICATION_CREATED',application.id,command,{clan_id:id,status:'PENDING'});
      return appView(application);
    });
  }
  async decide(principal: Principal, id: string, status: 'ACCEPTED'|'REJECTED'|'WITHDRAWN', reason: string, command: Command, launcher = false) {
    const initial = await this.auth.db.clan_applications.findUnique({where:{id}}); if (!initial) throw new NotFoundException();
    return this.execute(principal,initial.clan_id,[initial.user_id],launcher?'launcher_owner':status==='WITHDRAWN'?'own':'manager',command,async(tx,{actor,clan})=>{
      const application = await tx.clan_applications.findUniqueOrThrow({where:{id}});
      const ownerSelfReview=launcher && actor.systemRole==='OWNER' && application.user_id===actor.id;
      if (status==='WITHDRAWN' ? application.user_id !== actor.id : application.user_id === actor.id && !ownerSelfReview) throw new ForbiddenException();
      checkVersion(application.version,command); if (application.status !== 'PENDING') throw conflict();
      if (status === 'ACCEPTED') {
        this.open(clan); await this.admit(tx,actor.id,application.clan_id,application.user_id,reason,id);
      }
      const updated = await tx.clan_applications.update({where:{id},data:{status,reason,decided_by:actor.id,decided_at:new Date(),version:{increment:1}}});
      await tx.application_history.create({data:{application_id:id,status,actor_user_id:actor.id,reason}});
      await this.touch(tx,application.clan_id); await this.audit(tx,actor.id,`APPLICATION_${status}`,id,command,{status,user_id:application.user_id,clan_id:application.clan_id});
      return {...appView(updated),sync_status:status==='ACCEPTED'?'PENDING':'NOT_REQUIRED'};
    });
  }
  direct(principal: Principal, id: string, user: string, reason: string, command: Command, launcher = false) {
    return this.execute(principal,id,[user],launcher?'launcher_owner':'owner',command,async(tx,{actor,clan})=>{
      checkVersion(clan!.version,command); this.open(clan);
      const pending=await tx.clan_applications.findMany({where:{user_id:user,status:'PENDING'}});
      const ownerSelfJoin=launcher && actor.systemRole==='OWNER' && actor.id===user;
      if (pending.length && !ownerSelfJoin) throw conflict();
      const same=pending.find(application=>application.clan_id===id);
      const member = await this.admit(tx,actor.id,id,user,reason,same?.id);
      for(const application of pending){
        const status=application.id===same?.id?'ACCEPTED':'WITHDRAWN';
        await tx.clan_applications.update({where:{id:application.id},data:{status,reason,decided_by:actor.id,decided_at:new Date(),version:{increment:1}}});
        await tx.application_history.create({data:{application_id:application.id,status,actor_user_id:actor.id,reason}});
        await this.audit(tx,actor.id,`APPLICATION_${status}`,application.id,command,{status,user_id:user,clan_id:application.clan_id});
      }
      await this.touch(tx,id); await this.audit(tx,actor.id,'MEMBER_ADMITTED',member.id,command,{user_id:user,clan_id:id});
      return {...memberView(member),sync_status:'PENDING'};
    });
  }
  async remove(principal: Principal, clan: string, user: string, reason: string, command: Command, own = false) {
    return this.execute(principal,clan,[user],own?'own':'remove',command,async(tx,{actor})=>{
      if (own && actor.id !== user) throw new ForbiddenException();
      if (!own && actor.systemRole !== 'OWNER' && (user === actor.id || await tx.clan_manager_assignments.count({where:{user_id:user,clan_id:clan,ended_at:null}}))) throw new ForbiddenException();
      const member = await tx.clan_members.findFirst({where:{user_id:user,clan_id:clan,ended_at:null}});
      if (!member) throw new NotFoundException(); checkVersion(member.version,command);
      await this.endMember(tx,member,reason); await this.touch(tx,clan);
      await this.audit(tx,actor.id,own?'MEMBER_LEFT':'MEMBER_REMOVED',member.id,command,{user_id:user,clan_id:clan});
      return {id:member.id,status:'ENDED',version:(member.version+1n).toString(),sync_status:'PENDING'};
    });
  }
  manager(principal: Principal, clan: string, user: string, body: ManagerInput | {reason:string}, command: Command) {
    return this.execute(principal,clan,[user],'owner',command,async(tx,{actor,clan:current})=>{
      checkVersion(current!.version,command); if (current!.archivedAt) throw conflict();
      const target = await tx.user.findUnique({where:{id:user}});
      if (!target || target.state !== 'ACTIVE' || await tx.bans.count({where:{user_id:user,scope:'ACCOUNT',revoked_at:null,OR:[{expires_at:null},{expires_at:{gt:new Date()}}]}})) throw conflict();
      const prior = await tx.clan_manager_assignments.findFirst({where:{clan_id:clan,user_id:user,ended_at:null}});
      if ('can_remove_members' in body) {
        const role = await tx.clan_roles.upsert({where:{clan_id_code:{clan_id:clan,code:'MANAGER'}},create:{clan_id:clan,code:'MANAGER',label:'Manager'},update:{}});
        if (prior) await tx.clan_manager_assignments.update({where:{id:prior.id},data:{can_remove_members:body.can_remove_members,version:{increment:1}}});
        else await tx.clan_manager_assignments.create({data:{clan_id:clan,user_id:user,role_id:role.id,can_remove_members:body.can_remove_members}});
      } else {
        if (!prior) throw new NotFoundException();
        await tx.clan_manager_assignments.update({where:{id:prior.id},data:{ended_at:new Date(),version:{increment:1}}});
      }
      await tx.user.update({where:{id:user},data:{authEpoch:{increment:1},version:{increment:1}}}); await this.auth.revoke(tx,user);
      const updated = await this.touch(tx,clan);
      await this.audit(tx,actor.id,'MANAGER_CHANGED',user,command,{clan_id:clan,active:'can_remove_members' in body});
      return {clan_id:clan,user_id:user,active:'can_remove_members' in body,version:updated.version.toString()};
    });
  }
  archive(principal: Principal, id: string, reason: string, command: Command, restore = false) {
    return this.execute(principal,id,[],'owner',command,async(tx,{actor,clan})=>{
      checkVersion(clan!.version,command);
      if (restore ? !clan!.archivedAt : !!clan!.archivedAt) throw conflict();
      if (!restore) {
        const members = await tx.clan_members.findMany({where:{clan_id:id,ended_at:null}});
        for (const member of members) await this.endMember(tx,member,reason);
        const pending = await tx.clan_applications.findMany({where:{clan_id:id,status:'PENDING'}});
        for (const application of pending) {
          await tx.clan_applications.update({where:{id:application.id},data:{status:'CANCELLED',decided_by:actor.id,decided_at:new Date(),reason,version:{increment:1}}});
          await tx.application_history.create({data:{application_id:application.id,status:'CANCELLED',actor_user_id:actor.id,reason}});
        }
        const managers = await tx.clan_manager_assignments.findMany({where:{clan_id:id,ended_at:null}});
        await tx.clan_manager_assignments.updateMany({where:{clan_id:id,ended_at:null},data:{ended_at:new Date(),version:{increment:1}}});
        for (const user of [...new Set([...members.map(row=>row.user_id),...managers.map(row=>row.user_id)])]) {
          await tx.user.update({where:{id:user},data:{authEpoch:{increment:1},version:{increment:1}}}); await this.auth.revoke(tx,user);
          if (!members.some(row=>row.user_id===user)) await this.event(tx,user,'CLAN_ARCHIVED');
        }
      }
      const updated = await tx.clan.update({where:{id},data:{enabled:false,archivedAt:restore?null:new Date(),version:{increment:1}}});
      await this.audit(tx,actor.id,restore?'CLAN_UNARCHIVED':'CLAN_ARCHIVED',id,command,{version:updated.version.toString()});
      return {...clanView(updated),sync_status:'PENDING'};
    },!restore);
  }
  private expiry(value: string | null) { const date=value?new Date(value):null; if (date && (date.getTime()<=Date.now()+1000 || date.getTime()>Date.now()+365*86400000)) throw new HttpException('Expiry must be within one year',400); return date; }
  grant(principal: Principal, user: string, body: GrantInput, command: Command) {
    return this.execute(principal,null,[user],'owner',command,async(tx,{actor})=>{
      await this.eligible(tx,user);
      await tx.whitelist.updateMany({where:{user_id:user,kind:'OWNER',revoked_at:null,expires_at:{lte:new Date()}},data:{revoked_at:new Date(),version:{increment:1}}});
      const grant=await tx.whitelist.create({data:{user_id:user,kind:'OWNER',granted_by:actor.id,reason:body.reason,expires_at:this.expiry(body.expires_at)}});
      await this.event(tx,user,'OWNER_GRANT_CREATED'); await this.audit(tx,actor.id,'OWNER_GRANT_CREATED',grant.id,command,{user_id:user});
      return {id:grant.id,user_id:user,kind:'OWNER',version:grant.version.toString(),sync_status:'PENDING'};
    });
  }
  ban(principal: Principal, user: string, body: BanInput, command: Command) {
    return this.execute(principal,null,[user],'owner',command,async(tx,{actor})=>{
      const target=await tx.user.findUnique({where:{id:user}});if(!target)throw new NotFoundException();
      if(body.scope==='ACCOUNT'&&target.systemRole==='OWNER')throw new ForbiddenException('OWNER account cannot be banned');
      const ban=await tx.bans.create({data:{user_id:user,scope:body.scope,issued_by:actor.id,reason_private:body.reason,message_public:body.message_public,expires_at:this.expiry(body.expires_at)}});
      if (body.scope==='ACCOUNT') { await tx.user.update({where:{id:user},data:{authEpoch:{increment:1}}}); await this.auth.revoke(tx,user); }
      await this.event(tx,user,'BAN_ISSUED'); await this.audit(tx,actor.id,'BAN_ISSUED',ban.id,command,{user_id:user,scope:body.scope});
      return {id:ban.id,user_id:user,scope:body.scope,version:'0',sync_status:'PENDING'};
    });
  }
  async revoke(principal: Principal, id: string, type: 'ban'|'grant', command: Command) {
    const initial = type==='ban'?await this.auth.db.bans.findUnique({where:{id}}):await this.auth.db.whitelist.findUnique({where:{id}});
    if (!initial) throw new NotFoundException();
    return this.execute(principal,null,[initial.user_id],'owner',command,async(tx,{actor})=>{
      const row=type==='ban'?await tx.bans.findUniqueOrThrow({where:{id}}):await tx.whitelist.findUniqueOrThrow({where:{id}});
      if ('kind' in row && row.kind !== 'OWNER') throw new ForbiddenException();
      checkVersion(row.version,command); if (row.revoked_at) throw conflict();
      const data={revoked_at:new Date(),version:{increment:1}};
      if (type==='ban') await tx.bans.update({where:{id},data}); else await tx.whitelist.update({where:{id},data});
      await this.event(tx,row.user_id,type==='ban'?'BAN_REVOKED':'OWNER_GRANT_REVOKED');
      await this.audit(tx,actor.id,type==='ban'?'BAN_REVOKED':'OWNER_GRANT_REVOKED',id,command,{user_id:row.user_id});
      return {id,version:(row.version+1n).toString(),sync_status:'PENDING'};
    });
  }

  read(principal: Principal, clan: string | null, scope: Scope, work: (tx:Tx, context:Context)=>Promise<Result>) { return this.execute(principal,clan,[],scope,null,work); }
  // Administrative workflows share the same current-session, governance, user-lock and retry checks.
  ownerCommand(principal: Principal, targets: string[], command: Command, work: (tx:Tx, context:Context)=>Promise<Result>) {
    return this.execute(principal,null,targets,'owner',command,work);
  }
  ownerSecretCommand(principal: Principal, targets: string[], command: Command, work: (tx:Tx, context:Context)=>Promise<Result>) {
    return this.execute(principal,null,targets,'owner',command,work,false,result=>({...result,temporary_password:null}));
  }
  dashboard(principal: Principal) {
    return this.read(principal,null,'manager',async(tx,{actor})=>{
      const ids = actor.systemRole==='OWNER'?undefined:(await this.auth.managers(tx,actor.id)).map(row=>row.clan_id);
      return {system_role:actor.systemRole,items:(await tx.clan.findMany({where:ids?{id:{in:ids}}:{},orderBy:{codeKey:'asc'},take:100})).map(clanView)};
    });
  }
  preview(principal: Principal, id: string) {
    return this.read(principal,id,'owner',async(tx,{clan})=>{
      const members=await tx.clan_members.findMany({where:{clan_id:id,ended_at:null},select:{user_id:true}});
      return {clan:clanView(clan!),members:members.length,managers:await tx.clan_manager_assignments.count({where:{clan_id:id,ended_at:null}}),
        pending_applications:await tx.clan_applications.count({where:{clan_id:id,status:'PENDING'}}),
        independent_owner_grants:await tx.whitelist.count({where:{user_id:{in:members.map(row=>row.user_id)},kind:'OWNER',revoked_at:null,OR:[{expires_at:null},{expires_at:{gt:new Date()}}]}})};
    });
  }
  applications(principal: Principal, clan: string | null, page: PageInput, launcher = false) {
    const resource=`applications:${clan ?? principal.user.id}`; const position=this.pagination.read(resource,page.cursor);
    return this.read(principal,clan,launcher?'launcher_owner':clan?'manager':'own',async(tx)=>{
      const rows=await tx.clan_applications.findMany({where:{...(clan?{clan_id:clan}:{user_id:principal.user.id}),...(launcher?{status:'PENDING' as const}:{}),
        ...(position?{OR:[{created_at:{gt:new Date(position.time)}},{created_at:new Date(position.time),id:{gt:position.id}}]}:{})},include:{users_clan_applications_user_idTousers:{select:{login:true,game_profile:{select:{game_name:true}}}}},orderBy:[{created_at:'asc'},{id:'asc'}],take:page.limit+1});
      const last=rows.slice(0,page.limit).at(-1);
      return {items:rows.slice(0,page.limit).map(row=>({...appView(row),...(launcher?{nickname:row.users_clan_applications_user_idTousers.game_profile?.game_name??row.users_clan_applications_user_idTousers.login}:{})})),next_cursor:rows.length>page.limit&&last?this.pagination.sign(resource,{id:last.id,time:last.created_at.toISOString()}):null};
    });
  }
  roster(principal: Principal, clan: string, page: PageInput, management = true) {
    const resource=`members:${clan}:${management?'management':'player'}`; const position=this.pagination.read(resource,page.cursor);
    return this.read(principal,clan,management?'manager':'own',async(tx,{clan:current})=>{
      if (!management && (!current!.enabled || current!.archivedAt)) throw new NotFoundException();
      const rows=await tx.clan_members.findMany({where:{clan_id:clan,ended_at:null,...(position?{OR:[{joined_at:{gt:new Date(position.time)}},{joined_at:new Date(position.time),id:{gt:position.id}}]}:{})},
        include:{users:{select:{game_profile:{select:{game_name:true}},clan_manager_assignments:{where:{clan_id:clan,ended_at:null},select:{id:true},take:1}}}},orderBy:[{joined_at:'asc'},{id:'asc'}],take:page.limit+1});
      const userIds=rows.slice(0,page.limit).map(row=>row.user_id);
      const presence=userIds.length?await tx.$queryRaw<Array<{user_id:string;online:boolean;updated_at:Date}>>`
        SELECT p.user_id,bool_or(p.online) AS online,max(p.updated_at) AS updated_at FROM clan_online_presence p
        JOIN clan_server_presence s ON s.server_id=p.server_id AND s.snapshot_at>now()-interval '90 seconds'
        WHERE p.user_id IN (${Prisma.join(userIds.map(id=>Prisma.sql`${id}::uuid`))}) AND p.updated_at>now()-interval '90 seconds' GROUP BY p.user_id`:[];
      const live=new Map(presence.map(row=>[row.user_id,row]));
      const snapshots=await tx.$queryRaw<Array<{updated_at:Date|null}>>`SELECT max(snapshot_at) AS updated_at FROM clan_server_presence WHERE snapshot_at>now()-interval '90 seconds'`;
      const checkedAt=snapshots[0]?.updated_at;
      const last=rows.slice(0,page.limit).at(-1);
      return {items:rows.slice(0,page.limit).map(row=>({...management?memberView(row):{joined_at:row.joined_at.toISOString()},nickname:row.users.game_profile?.game_name??null,
        role:row.users.clan_manager_assignments.length?'MANAGER':'MEMBER',online:checkedAt?(live.get(row.user_id)?.online??false):null,online_updated_at:checkedAt?.toISOString()??null})),next_cursor:rows.length>page.limit&&last?this.pagination.sign(resource,{id:last.id,time:last.joined_at.toISOString()}):null};
    });
  }
  access(principal: Principal) {
    return this.read(principal,null,'own',async(tx,{actor})=>{
      const now=new Date(); const activeTime={revoked_at:null,OR:[{expires_at:null},{expires_at:{gt:now}}]};
      const bans=await tx.bans.count({where:{user_id:actor.id,...activeTime}});
      const identity=await tx.game_profiles.count({where:{user_id:actor.id}});
      const grants=await tx.whitelist.count({where:{user_id:actor.id,revoked_at:null,AND:[{OR:activeTime.OR},{OR:[{kind:'OWNER'},{kind:'MEMBERSHIP',clan_members:{ended_at:null,clans:{archivedAt:null}}}]}]}});
      const member=await tx.clan_members.findFirst({where:{user_id:actor.id,ended_at:null}});
      const reason=bans?'BANNED':!identity?'GAME_PROFILE_REQUIRED':!grants?'GRANT_REQUIRED':'ALLOWED';
      return {effective_access:reason==='ALLOWED'?'ALLOWED':'DENIED',reason,access_epoch:actor.accessEpoch.toString(),membership:member?memberView(member):null,
        sync_status:'UNKNOWN',checked_at:now.toISOString(),game_admission_available:false};
    });
  }
  launcherClans(principal: Principal) {
    return this.read(principal,null,'launcher_owner',async(tx)=>({items:(await tx.clan.findMany({where:{enabled:true,archivedAt:null},orderBy:{shortCode:'asc'},take:100})).map(clanView)}));
  }
  ownClan(principal: Principal) {
    return this.read(principal,null,'own',async(tx,{actor})=>{
      const member=await tx.clan_members.findFirst({where:{user_id:actor.id,ended_at:null},include:{clans:true}});
      return {clan:member?clanView(member.clans):null,membership:member?memberView(member):null};
    });
  }
}
