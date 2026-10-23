import { Body, Controller, Delete, Get, Inject, NotFoundException, Param, ParseUUIDPipe, Patch, Post, Put, Query, Req, UseInterceptors } from '@nestjs/common';
import type { FastifyRequest } from 'fastify';
import { AuthHttp, input } from '../auth/auth-http.js';
import { AuthNoStore } from '../auth/auth-no-store.js';
import { AuthService } from '../auth/auth.service.js';
import { ClanInput } from '../development/clan-input.js';
import { PageInput } from '../../infrastructure/pagination.js';
import { CommunityService, type Command } from './community.service.js';
import { AdmissionInput, ApplicationInput, BanInput, GrantInput, ManagerInput, ReasonInput } from './community-input.js';
const uuid = () => new ParseUUIDPipe({version:'4'});
@Controller('api/v1')
@UseInterceptors(AuthNoStore)
export class CommunityController {
  constructor(@Inject(CommunityService) private readonly service: CommunityService, @Inject(AuthHttp) private readonly http: AuthHttp,
    @Inject(AuthService) private readonly auth: AuthService) {}
  private command(request: FastifyRequest, body: object): Command {
    return {route:request.url.split('?')[0]!,method:request.method,key:request.headers['idempotency-key'] as string|undefined,
      match:request.headers['if-match'] as string|undefined,body,request_id:request.id};
  }
  private async actor(request: FastifyRequest) { await this.http.unsafe(request); return this.http.principal(request); }
  @Get('launcher/owner/clans') async launcherClans(@Req() request: FastifyRequest) { return this.service.launcherClans(await this.http.principal(request)); }
  @Get('launcher/owner/clans/:id/applications') async launcherApplications(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Query() query: unknown) { return this.service.applications(await this.http.principal(request),id,input(PageInput,query),true); }
  @Post('launcher/owner/clans/:id/join') async launcherJoin(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body);const actor=await this.actor(request);return this.service.direct(actor,id,actor.user.id,data.reason,this.command(request,data),true); }
  @Post('launcher/owner/applications/:id/approve') async launcherApprove(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body);return this.service.decide(await this.actor(request),id,'ACCEPTED',data.reason,this.command(request,data),true); }
  @Post('launcher/owner/applications/:id/reject') async launcherReject(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body);return this.service.decide(await this.actor(request),id,'REJECTED',data.reason,this.command(request,data),true); }
  @Get('management/dashboard') dashboard(@Req() request: FastifyRequest, @Query() query: unknown) { input(PageInput,query); return this.http.principal(request).then(actor=>this.service.dashboard(actor)); }
  @Post('management/clans') async create(@Req() request: FastifyRequest, @Body() body: unknown) { const data=input(ClanInput,body); return this.service.create(await this.actor(request),data,this.command(request,data)); }
  @Patch('management/clans/:id') async edit(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ClanInput,body); return this.service.edit(await this.actor(request),id,data,this.command(request,data)); }
  @Get('management/clans/:id/archive-preview') async preview(@Req() request: FastifyRequest,@Param('id',uuid()) id: string) { return this.service.preview(await this.http.principal(request),id); }
  @Post('management/clans/:id/archive') async archive(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.archive(await this.actor(request),id,data.reason,this.command(request,data)); }
  @Post('management/clans/:id/unarchive') async unarchive(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.archive(await this.actor(request),id,data.reason,this.command(request,data),true); }
  @Put('management/clans/:id/managers/:user') async manager(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Param('user',uuid()) user: string,@Body() body: unknown) { const data=input(ManagerInput,body); return this.service.manager(await this.actor(request),id,user,data,this.command(request,data)); }
  @Delete('management/clans/:id/managers/:user') async endManager(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Param('user',uuid()) user: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.manager(await this.actor(request),id,user,data,this.command(request,data)); }
  @Get('management/clans/:id/applications') async applications(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Query() query: unknown) { return this.service.applications(await this.http.principal(request),id,input(PageInput,query)); }
  @Get('management/clans/:id/members') async members(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Query() query: unknown) { return this.service.roster(await this.http.principal(request),id,input(PageInput,query)); }
  @Post('management/applications/:id/approve') async approve(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.decide(await this.actor(request),id,'ACCEPTED',data.reason,this.command(request,data)); }
  @Post('management/applications/:id/reject') async reject(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.decide(await this.actor(request),id,'REJECTED',data.reason,this.command(request,data)); }
  @Post('management/clans/:id/members') async admit(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(AdmissionInput,body); return this.service.direct(await this.actor(request),id,data.user_id,data.reason,this.command(request,data)); }
  @Delete('management/clans/:id/members/:user') async remove(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Param('user',uuid()) user: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.remove(await this.actor(request),id,user,data.reason,this.command(request,data)); }
  @Post('clans/:id/applications') async apply(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ApplicationInput,body); return this.service.apply(await this.actor(request),id,data.message,this.command(request,data)); }
  @Get('clans/:id/members') async roster(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Query() query: unknown) { return this.service.roster(await this.http.principal(request),id,input(PageInput,query),false); }
  @Get('me/applications') async ownApplications(@Req() request: FastifyRequest,@Query() query: unknown) { return this.service.applications(await this.http.principal(request),null,input(PageInput,query)); }
  @Post('me/applications/:id/withdraw') async withdraw(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.decide(await this.actor(request),id,'WITHDRAWN',data.reason,this.command(request,data)); }
  @Post('me/clan/leave') async leave(@Req() request: FastifyRequest,@Body() body: unknown) {
    const data=input(ReasonInput,body); const actor=await this.actor(request);
    const member=await this.auth.db.clan_members.findFirst({where:{user_id:actor.user.id},orderBy:[{joined_at:'desc'},{id:'desc'}]}); if(!member) throw new NotFoundException();
    return this.service.remove(actor,member.clan_id,actor.user.id,data.reason,this.command(request,data),true);
  }
  @Get('me/access') async access(@Req() request: FastifyRequest) { return this.service.access(await this.http.principal(request)); }
  @Get('me/clan') async ownClan(@Req() request: FastifyRequest) { return this.service.ownClan(await this.http.principal(request)); }
  @Post('management/users/:user/whitelist-grants') async grant(@Req() request: FastifyRequest,@Param('user',uuid()) user: string,@Body() body: unknown) { const data=input(GrantInput,body); return this.service.grant(await this.actor(request),user,data,this.command(request,data)); }
  @Delete('management/whitelist-grants/:id') async revokeGrant(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.revoke(await this.actor(request),id,'grant',this.command(request,data)); }
  @Post('management/users/:user/bans') async ban(@Req() request: FastifyRequest,@Param('user',uuid()) user: string,@Body() body: unknown) { const data=input(BanInput,body); return this.service.ban(await this.actor(request),user,data,this.command(request,data)); }
  @Post('management/bans/:id/revoke') async revokeBan(@Req() request: FastifyRequest,@Param('id',uuid()) id: string,@Body() body: unknown) { const data=input(ReasonInput,body); return this.service.revoke(await this.actor(request),id,'ban',this.command(request,data)); }
}
