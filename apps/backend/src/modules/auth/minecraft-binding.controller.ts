import { BadRequestException, Body, Controller, HttpCode, Inject, Post, Req, UseInterceptors } from '@nestjs/common';
import type { FastifyRequest } from 'fastify';
import { AuthHttp, input } from './auth-http.js';
import { MinecraftBindingService } from './minecraft-binding.service.js';
import { MinecraftBindInput, PasswordInput } from './auth-input.js';
import { AuthNoStore } from './auth-no-store.js';
@Controller('api/v1/auth/minecraft')
@UseInterceptors(AuthNoStore)
export class MinecraftBindingController {
  constructor(@Inject(AuthHttp) private readonly http:AuthHttp,@Inject(MinecraftBindingService) private readonly binding:MinecraftBindingService) {}
  @Post('challenge') @HttpCode(200)
  async challenge(@Req() req:FastifyRequest,@Body() body:unknown) {
    if(await this.http.unsafe(req)!=='LAUNCHER') throw new BadRequestException('Minecraft binding uses the native transport');
    return this.binding.challenge(await this.http.principal(req),input(PasswordInput,body).password);
  }
  @Post('verify') @HttpCode(200)
  async verify(@Req() req:FastifyRequest,@Body() body:unknown) {
    if(await this.http.unsafe(req)!=='LAUNCHER') throw new BadRequestException('Minecraft binding uses the native transport');
    // A production TLS ingress is not configured yet: real token transfer stays closed on plain HTTP.
    if(req.protocol!=='https' && process.env.NODE_ENV!=='test') throw new BadRequestException('HTTPS is required');
    const data=input(MinecraftBindInput,body);
    return this.binding.bind(await this.http.principal(req),data.challenge,data.minecraft_access_token,req.id);
  }
  @Post('unlink') @HttpCode(204)
  async unlink(@Req() req:FastifyRequest,@Body() body:unknown) {
    await this.http.unsafe(req); await this.binding.unlink(await this.http.principal(req),input(PasswordInput,body).password,req.id);
  }
}
