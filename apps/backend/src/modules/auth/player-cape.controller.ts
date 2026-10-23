import {BadRequestException,Body,Controller,Get,HttpCode,Inject,Post,Req,UseInterceptors} from '@nestjs/common';
import {IsIn,IsOptional,Matches} from 'class-validator';
import type {FastifyRequest} from 'fastify';
import {AuthHttp,input} from './auth-http.js';
import {AuthNoStore} from './auth-no-store.js';
import {PlayerCapeService} from './player-cape.service.js';
class CapeInput { @IsOptional() @IsIn(['beta']) cape_id!:string|null; }
class WardrobeInput { @IsOptional() @Matches(/^[a-z][a-z0-9-]{0,31}$/) cape_id!:string|null; }
@Controller('api/v1/auth/me/capes')
@UseInterceptors(AuthNoStore)
export class PlayerCapeController {
 constructor(@Inject(AuthHttp) private readonly http:AuthHttp,@Inject(PlayerCapeService) private readonly capes:PlayerCapeService){}
 @Get() async read(@Req() req:FastifyRequest){return this.capes.read(await this.http.principal(req));}
 @Get('wardrobe') async wardrobe(@Req() req:FastifyRequest){return this.capes.read(await this.http.principal(req),true);}
 @Post('wardrobe') @HttpCode(200) async saveWardrobe(@Req() req:FastifyRequest,@Body() body:unknown){
  await this.http.unsafe(req);const data=input(WardrobeInput,body);
  if(data.cape_id===undefined)throw new BadRequestException('cape_id is required');
  return this.capes.save(await this.http.principal(req),data.cape_id,req.id,true);
 }
 @Post() @HttpCode(200) async save(@Req() req:FastifyRequest,@Body() body:unknown){
  await this.http.unsafe(req);const data=input(CapeInput,body);
  if(data.cape_id===undefined)throw new BadRequestException('cape_id is required');
  return this.capes.save(await this.http.principal(req),data.cape_id,req.id);
 }
}
