import { Body, Controller, ForbiddenException, Get, HttpCode, Inject, Post, Req, Res, UseInterceptors } from '@nestjs/common';
import type { FastifyReply,FastifyRequest } from 'fastify';
import { AuthHttp,input } from '../modules/auth/auth-http.js';
import { LoginInput,ProofInput } from '../modules/auth/auth-input.js';
import { AuthNoStore } from '../modules/auth/auth-no-store.js';
import { MobileOwnerSetup } from './owner-setup.js';
const cookie='__Host-aoc-mobile-setup';
@Controller('api/v1/mobile-setup')
@UseInterceptors(AuthNoStore)
export class MobileOwnerSetupController {
  constructor(@Inject(MobileOwnerSetup) private readonly setup:MobileOwnerSetup,@Inject(AuthHttp) private readonly http:AuthHttp){}
  @Get('health') health(){return {status:'ok'};}
  @Post('start') @HttpCode(200)
  async start(@Req() req:FastifyRequest,@Body() body:unknown,@Res({passthrough:true}) reply:FastifyReply){
    if(await this.http.unsafe(req)!=='WEB_ADMIN')throw new ForbiddenException();
    const data=input(LoginInput,body),result=await this.setup.start(data.login,data.password,req.ip,req.id);
    reply.header('set-cookie',`${cookie}=${result.token}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=300`);
    return {secret:result.secret,otpauth_uri:result.otpauth_uri,expires_in:result.expires_in};
  }
  @Post('confirm') @HttpCode(200)
  async confirm(@Req() req:FastifyRequest,@Body() body:unknown,@Res({passthrough:true}) reply:FastifyReply){
    if(await this.http.unsafe(req)!=='WEB_ADMIN')throw new ForbiddenException();
    const data=input(ProofInput,body),result=await this.setup.confirm(this.http.cookies(req).get(cookie)??'',data.password,data.proof,req.ip,req.id);
    this.http.clear(reply);const headers=reply.getHeader('set-cookie');
    reply.header('set-cookie',[...(Array.isArray(headers)?headers:typeof headers==='string'?[headers]:[]),`${cookie}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0`]);return result;
  }
}
