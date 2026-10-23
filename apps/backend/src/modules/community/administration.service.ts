import { ForbiddenException, HttpException, Inject, Injectable, NotFoundException } from '@nestjs/common';
import { createHash, randomBytes } from 'node:crypto';
import { Prisma, type news, type User } from '../../generated/prisma/client.js';
import { PaginationService, type PageInput } from '../../infrastructure/pagination.js';
import { AuthService, type Principal } from '../auth/auth.service.js';
import { CommunityService, checkVersion, type Command } from './community.service.js';
import type { NewsInput, SlotInput, StateInput, UsersInput } from './administration-input.js';
import { assignSlot, listSlots, releaseSlot } from './region-assignment.js';
type Tx=Prisma.TransactionClient;
const userView=(row:User)=>({id:row.id,login:row.login,password_change_required:row.passwordChangeRequired,email:row.email,email_verified:!!row.emailVerifiedAt,system_role:row.systemRole,state:row.state,version:row.version.toString(),created_at:row.createdAt.toISOString()});
const newsView=(row:news)=>({id:row.id,slug:row.slug,title:row.title,body_markdown:row.body_markdown,published_at:row.published_at?.toISOString()??null,archived_at:row.archived_at?.toISOString()??null,version:row.version.toString(),created_at:row.created_at.toISOString()});
@Injectable()
export class AdministrationService {
  constructor(@Inject(CommunityService) private readonly community:CommunityService,@Inject(AuthService) private readonly auth:AuthService,@Inject(PaginationService) private readonly pagination:PaginationService){}
  private after(resource:string,page:PageInput,field='created_at') {
    const position=this.pagination.read(resource,page.cursor);
    return position?{OR:[{[field]:{gt:new Date(position.time)}},{[field]:new Date(position.time),id:{gt:position.id}}]}:{};
  }
  private page<T extends {id:string}>(resource:string,page:PageInput,rows:T[],time:(row:T)=>Date,view:(row:T)=>unknown){
    const items=rows.slice(0,page.limit);const last=items.at(-1);
    return {items:items.map(view),next_cursor:rows.length>page.limit&&last?this.pagination.sign(resource,{id:last.id,time:time(last).toISOString()}):null};
  }
  overview(principal:Principal) {
    return this.community.read(principal,null,'manager',async(tx,{actor})=>{
      const owner=actor.systemRole==='OWNER';const ids=owner?undefined:(await this.auth.managers(tx,actor.id)).map(row=>row.clan_id);
      const clanFilter=ids?{clan_id:{in:ids}}:{};
      return {system_role:actor.systemRole,clans:await tx.clan.count({where:ids?{id:{in:ids}}:{}}),members:await tx.clan_members.count({where:{...clanFilter,ended_at:null}}),pending_applications:await tx.clan_applications.count({where:{...clanFilter,status:'PENDING'}}),
        users:owner?await tx.user.count():null,active_bans:owner?await tx.bans.count({where:{revoked_at:null,OR:[{expires_at:null},{expires_at:{gt:new Date()}}]}}):null,
        news_drafts:owner?await tx.news.count({where:{published_at:null,archived_at:null}}):null,online:null,game_admission_available:false,sync_status:'UNKNOWN',checked_at:new Date().toISOString()};
    });
  }
  users(principal:Principal,page:UsersInput){
    const resource=`admin-users:${createHash('sha256').update(page.q??'').digest('hex').slice(0,16)}`;const after=this.after(resource,page,'createdAt');
    return this.community.read(principal,null,'owner',async tx=>{
      const rows=await tx.user.findMany({where:{AND:[after,...page.q?[{OR:[{loginKey:{contains:page.q}},{emailKey:{contains:page.q}},{game_profile:{is:{game_name_key:{contains:page.q}}}}]}]:[]]},orderBy:[{createdAt:'asc'},{id:'asc'}],take:page.limit+1});
      return this.page(resource,page,rows,row=>row.createdAt,userView);
    });
  }
  user(principal:Principal,id:string){
    return this.community.read(principal,null,'owner',async tx=>{
      const row=await tx.user.findUnique({where:{id}});if(!row)throw new NotFoundException();
      const profile=await tx.game_profiles.findUnique({where:{user_id:id},select:{minecraft_uuid:true,game_name:true}});
      const identity=profile?{minecraft_uuid:profile.minecraft_uuid,nickname:profile.game_name}:null;
      const member=await tx.clan_members.findFirst({where:{user_id:id,ended_at:null},include:{clans:{select:{name:true}}}});
      const managers=await tx.clan_manager_assignments.findMany({where:{user_id:id,ended_at:null},select:{clan_id:true,can_remove_members:true},take:100});
      const active={revoked_at:null,OR:[{expires_at:null},{expires_at:{gt:new Date()}}]};
      const banned=!!await tx.bans.count({where:{user_id:id,...active}});
      const grants=await tx.whitelist.count({where:{user_id:id,...active,AND:[{OR:[{kind:'OWNER'},{kind:'MEMBERSHIP',clan_members:{ended_at:null,clans:{archivedAt:null}}}]}]}});
      const access=row.state!=='ACTIVE'?'SUSPENDED':banned?'BANNED':!identity?'GAME_PROFILE_REQUIRED':!grants?'GRANT_REQUIRED':'ALLOWED';
      const stats=await tx.player_statistics.findMany({where:{user_id:id},select:{server_id:true,playtime_seconds:true,last_seen_at:true,updated_at:true},take:100,orderBy:{server_id:'asc'}});
      return {...userView(row),minecraft:identity,membership:member?{clan_id:member.clan_id,clan_name:member.clans.name,joined_at:member.joined_at.toISOString()}:null,managers,
        effective_access:access==='ALLOWED'?'ALLOWED':'DENIED',access_reason:access,game_admission_available:false,sync_status:'UNKNOWN',statistics:stats.map(row=>({...row,playtime_seconds:row.playtime_seconds.toString(),last_seen_at:row.last_seen_at?.toISOString()??null,updated_at:row.updated_at.toISOString()}))};
    });
  }
  state(principal:Principal,id:string,body:StateInput,command:Command){
    return this.community.ownerCommand(principal,[id],command,async(tx,{actor})=>{
      const target=await tx.user.findUnique({where:{id}});if(!target)throw new NotFoundException();
      if(target.systemRole==='OWNER'||id===actor.id)throw new ForbiddenException('OWNER state requires governance recovery');
      checkVersion(target.version,command);if(target.state===body.state)throw new HttpException('State unchanged',409);
      const updated=await tx.user.update({where:{id},data:{state:body.state,authEpoch:{increment:1},accessEpoch:{increment:1},version:{increment:1}}});
      await this.auth.revoke(tx,id);
      await tx.outbox_events.create({data:{type:'ACCOUNT_STATE_CHANGED',aggregate_id:id,aggregate_version:updated.version,payload:{user_id:id,state:body.state,access_epoch:updated.accessEpoch.toString()}}});
      await this.audit(tx,actor.id,'USER_STATE_CHANGED',id,command,{state:target.state},{state:body.state,version:updated.version.toString()});
      return {...userView(updated),sync_status:'PENDING'};
    });
  }
  async resetPassword(principal:Principal,id:string,command:Command){
    const temporary = randomBytes(18).toString('base64url');
    const hash = await this.auth.crypto.hash(temporary);
    return this.community.ownerSecretCommand(principal,[id],command,async(tx,{actor})=>{
      const target=await tx.user.findUnique({where:{id}});if(!target)throw new NotFoundException();
      if(target.systemRole==='OWNER'||id===actor.id)throw new ForbiddenException('OWNER recovery uses the operator procedure');
      checkVersion(target.version,command);
      const updated=await tx.user.update({where:{id},data:{passwordHash:hash,passwordChangeRequired:true,authEpoch:{increment:1},version:{increment:1}}});
      await this.auth.revoke(tx,id);
      await this.audit(tx,actor.id,'PLAYER_PASSWORD_RESET',id,command,{version:target.version.toString()},{version:updated.version.toString(),password_change_required:true});
      // Never accept a recipient in the request or store cleartext in the idempotency result.
      return {user_id:id,login:updated.login,delivery_email:updated.email,temporary_password:temporary,version:updated.version.toString()};
    });
  }
  records(principal:Principal,id:string,kind:'bans'|'grants',page:PageInput){
    const resource=`admin-${kind}:${id}`;const after=this.after(resource,page);
    return this.community.read(principal,null,'owner',async tx=>{
      if(!await tx.user.count({where:{id}}))throw new NotFoundException();
      if(kind==='bans')return this.page(resource,page,await tx.bans.findMany({where:{user_id:id,...after},orderBy:[{created_at:'asc'},{id:'asc'}],take:page.limit+1}),row=>row.created_at,row=>({id:row.id,user_id:row.user_id,scope:row.scope,reason:row.reason_private,message_public:row.message_public,created_at:row.created_at.toISOString(),expires_at:row.expires_at?.toISOString()??null,revoked_at:row.revoked_at?.toISOString()??null,version:row.version.toString()}));
      return this.page(resource,page,await tx.whitelist.findMany({where:{user_id:id,...after},orderBy:[{created_at:'asc'},{id:'asc'}],take:page.limit+1}),row=>row.created_at,row=>({id:row.id,user_id:row.user_id,kind:row.kind,reason:row.reason,created_at:row.created_at.toISOString(),expires_at:row.expires_at?.toISOString()??null,revoked_at:row.revoked_at?.toISOString()??null,version:row.version.toString()}));
    });
  }
  news(principal:Principal,page:PageInput){const resource='admin-news';const after=this.after(resource,page);return this.community.read(principal,null,'owner',async tx=>this.page(resource,page,await tx.news.findMany({where:after,orderBy:[{created_at:'asc'},{id:'asc'}],take:page.limit+1}),row=>row.created_at,newsView));}
  writeNews(principal:Principal,id:string|null,body:NewsInput,command:Command){
    if(Buffer.byteLength(body.body_markdown,'utf8')>48000)throw new HttpException('News body exceeds byte limit',400);
    return this.community.ownerCommand(principal,[],command,async(tx,{actor})=>{
      let prior:news|null=null;if(id){await tx.$queryRaw`SELECT id FROM news WHERE id=${id}::uuid FOR UPDATE`;prior=await tx.news.findUnique({where:{id}});if(!prior)throw new NotFoundException();checkVersion(prior.version,command);if(prior.archived_at||prior.published_at)throw new HttpException('Unpublish before editing news',409);}
      const data={slug:body.slug,title:body.title,body_markdown:body.body_markdown,updated_at:new Date()};
      const row=id?await tx.news.update({where:{id},data:{...data,version:{increment:1}}}):await tx.news.create({data:{...data,author_id:actor.id}});
      await this.audit(tx,actor.id,prior?'NEWS_UPDATED':'NEWS_CREATED',row.id,command,prior?{version:prior.version.toString()}:{},{version:row.version.toString()});return newsView(row);
    });
  }
  transitionNews(principal:Principal,id:string,action:'publish'|'unpublish'|'archive',command:Command){
    return this.community.ownerCommand(principal,[],command,async(tx,{actor})=>{
      await tx.$queryRaw`SELECT id FROM news WHERE id=${id}::uuid FOR UPDATE`;const prior=await tx.news.findUnique({where:{id}});if(!prior)throw new NotFoundException();checkVersion(prior.version,command);
      if(prior.archived_at||(action==='publish'&&prior.published_at)||(action==='unpublish'&&!prior.published_at))throw new HttpException('Invalid news transition',409);
      const row=await tx.news.update({where:{id},data:{...(action==='archive'?{archived_at:new Date()}: {published_at:action==='publish'?new Date():null}),version:{increment:1},updated_at:new Date()}});
      await this.audit(tx,actor.id,`NEWS_${action.toUpperCase()}`,id,command,{version:prior.version.toString()},{version:row.version.toString()});return newsView(row);
    });
  }
  auditLog(principal:Principal,page:PageInput){const resource='admin-audit';const after=this.after(resource,page,'time');return this.community.read(principal,null,'owner',async tx=>this.page(resource,page,await tx.audit_logs.findMany({where:after,orderBy:[{time:'asc'},{id:'asc'}],take:page.limit+1}),row=>row.time,row=>({id:row.id,actor_kind:row.actor_kind,actor_user_id:row.actor_user_id,action:row.action,target_type:row.target_type,target_id:row.target_id,reason:row.reason,request_id:row.request_id,time:row.time.toISOString(),before:row.before_redacted,after:row.after_redacted})));}
  servers(principal:Principal){return this.community.read(principal,null,'owner',async tx=>({items:(await tx.game_servers.findMany({take:100,orderBy:{id:'asc'}})).map(row=>({id:row.id,name:row.name,maintenance:row.maintenance,online:null,status:'UNKNOWN',last_observed_at:null})),game_admission_available:false,sync_status:'UNKNOWN'}));}
  /** Stage 8: frontier slots of the active world, assigned to new clans (docs/FRONTIER_EXPANSION.md). */
  slots(principal:Principal){return this.community.read(principal,null,'owner',async tx=>listSlots(tx));}
  assignSlot(principal:Principal,id:string,body:SlotInput,command:Command){
    return this.community.ownerCommand(principal,[],command,async(tx,{actor})=>assignSlot(tx,id,body.clan_id,command,(t,action,target,before,after)=>this.audit(t,actor.id,action,target,command,before,after)));
  }
  releaseSlot(principal:Principal,id:string,command:Command){
    return this.community.ownerCommand(principal,[],command,async(tx,{actor})=>releaseSlot(tx,id,command,(t,action,target,before,after)=>this.audit(t,actor.id,action,target,command,before,after)));
  }
  private audit(tx:Tx,actor:string,action:string,id:string,command:Command,before:Prisma.InputJsonObject,after:Prisma.InputJsonObject){return tx.audit_logs.create({data:{actor_kind:'USER',actor_user_id:actor,action,target_type:'administration',target_id:id,reason:String('reason' in command.body?command.body.reason:'Administrative change'),request_id:command.request_id,before_redacted:before,after_redacted:after}});}
}
