import { Body, Controller, Get, Inject, Param, ParseUUIDPipe, Patch, Post, Query, Req, UseInterceptors } from '@nestjs/common';
import type { FastifyRequest } from 'fastify';
import { PageInput } from '../../infrastructure/pagination.js';
import { AuthHttp, input } from '../auth/auth-http.js';
import { AuthNoStore } from '../auth/auth-no-store.js';
import { NewsInput, SlotInput, StateInput, UsersInput } from './administration-input.js';
import { AdministrationService } from './administration.service.js';
import { ReasonInput } from './community-input.js';
import type { Command } from './community.service.js';
const uuid=()=>new ParseUUIDPipe({version:'4'});
@Controller('api/v1/management') @UseInterceptors(AuthNoStore)
export class AdministrationController {
  constructor(@Inject(AdministrationService) private readonly service:AdministrationService,@Inject(AuthHttp) private readonly http:AuthHttp){}
  private command(request:FastifyRequest,body:object):Command{return {route:request.url.split('?')[0]!,method:request.method,key:request.headers['idempotency-key'] as string|undefined,match:request.headers['if-match'] as string|undefined,body,request_id:request.id};}
  private async actor(request:FastifyRequest){await this.http.unsafe(request);return this.http.principal(request);}
  @Get('overview') async overview(@Req() request:FastifyRequest){return this.service.overview(await this.http.principal(request));}
  @Get('users') async users(@Req() request:FastifyRequest,@Query() query:unknown){return this.service.users(await this.http.principal(request),input(UsersInput,query));}
  @Get('users/:id') async user(@Req() request:FastifyRequest,@Param('id',uuid()) id:string){return this.service.user(await this.http.principal(request),id);}
  @Patch('users/:id/state') async state(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(StateInput,body);return this.service.state(await this.actor(request),id,data,this.command(request,data));}
  @Post('users/:id/password-reset') async resetPassword(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(ReasonInput,body);return this.service.resetPassword(await this.actor(request),id,this.command(request,data));}
  @Get('users/:id/bans') async bans(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Query() query:unknown){return this.service.records(await this.http.principal(request),id,'bans',input(PageInput,query));}
  @Get('users/:id/whitelist-grants') async grants(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Query() query:unknown){return this.service.records(await this.http.principal(request),id,'grants',input(PageInput,query));}
  @Get('news') async news(@Req() request:FastifyRequest,@Query() query:unknown){return this.service.news(await this.http.principal(request),input(PageInput,query));}
  @Post('news') async createNews(@Req() request:FastifyRequest,@Body() body:unknown){const data=input(NewsInput,body);return this.service.writeNews(await this.actor(request),null,data,this.command(request,data));}
  @Patch('news/:id') async editNews(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(NewsInput,body);return this.service.writeNews(await this.actor(request),id,data,this.command(request,data));}
  @Post('news/:id/publish') async publish(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(ReasonInput,body);return this.service.transitionNews(await this.actor(request),id,'publish',this.command(request,data));}
  @Post('news/:id/unpublish') async unpublish(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(ReasonInput,body);return this.service.transitionNews(await this.actor(request),id,'unpublish',this.command(request,data));}
  @Post('news/:id/archive') async archive(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(ReasonInput,body);return this.service.transitionNews(await this.actor(request),id,'archive',this.command(request,data));}
  @Get('audit') async audit(@Req() request:FastifyRequest,@Query() query:unknown){return this.service.auditLog(await this.http.principal(request),input(PageInput,query));}
  @Get('world/slots') async slots(@Req() request:FastifyRequest){return this.service.slots(await this.http.principal(request));}
  @Post('world/slots/:id/assign') async assignSlot(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(SlotInput,body);return this.service.assignSlot(await this.actor(request),id,data,this.command(request,data));}
  @Post('world/slots/:id/release') async releaseSlot(@Req() request:FastifyRequest,@Param('id',uuid()) id:string,@Body() body:unknown){const data=input(ReasonInput,body);return this.service.releaseSlot(await this.actor(request),id,this.command(request,data));}
  @Get('servers') async servers(@Req() request:FastifyRequest){return this.service.servers(await this.http.principal(request));}
}
