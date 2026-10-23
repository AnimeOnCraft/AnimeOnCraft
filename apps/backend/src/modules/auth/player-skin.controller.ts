import {BadRequestException,Body,Controller,Get,HttpCode,Inject,Post,Req,UseInterceptors} from '@nestjs/common';
import {IsIn,IsOptional,IsString,MaxLength} from 'class-validator';
import type {FastifyRequest} from 'fastify';
import {AuthHttp,input} from './auth-http.js';
import {AuthNoStore} from './auth-no-store.js';
import {PlayerSkinService} from './player-skin.service.js';
class SkinInput {
 @IsOptional() @IsString() @MaxLength(175000) png_base64!:string|null;
 @IsIn(['classic','slim']) model!:'classic'|'slim';
}
@Controller('api/v1/auth/me/skin')
@UseInterceptors(AuthNoStore)
export class PlayerSkinController {
 constructor(@Inject(AuthHttp) private readonly http:AuthHttp,@Inject(PlayerSkinService) private readonly skins:PlayerSkinService){}
 @Get() async read(@Req() req:FastifyRequest){return this.skins.read(await this.http.principal(req));}
 @Post() @HttpCode(200) async save(@Req() req:FastifyRequest,@Body() body:unknown){
  await this.http.unsafe(req);const data=input(SkinInput,body);
  if(data.png_base64===undefined)throw new BadRequestException('png_base64 is required');
  return this.skins.save(await this.http.principal(req),data.png_base64,data.model,req.id);
 }
}
