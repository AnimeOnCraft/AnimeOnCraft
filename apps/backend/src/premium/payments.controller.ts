import {Body,Controller,Get,HttpCode,HttpException,Inject,Module,Post,Req,UseInterceptors} from '@nestjs/common';
import type {FastifyRequest} from 'fastify';
import {InfrastructureModule} from '../infrastructure/infrastructure.module.js';
import {RateLimitStoreService} from '../infrastructure/rate-limit-store.service.js';
import {AuthModule} from '../modules/auth/auth.module.js';
import {AuthHttp} from '../modules/auth/auth-http.js';
import {AuthNoStore} from '../modules/auth/auth-no-store.js';
import {AuthService} from '../modules/auth/auth.service.js';
import {readWallet} from './crystals.js';
import {PACKS,createOrder,orders,pack,settle,yookassa} from './payments.js';

/** Launcher shop: the signed-in account buys crystals for itself only. */
@Controller('api/v1/premium')
@UseInterceptors(AuthNoStore)
export class PremiumShopController {
 constructor(@Inject(AuthHttp) private readonly http:AuthHttp,@Inject(AuthService) private readonly auth:AuthService,@Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Get() async read(@Req() req:FastifyRequest){
  const user=(await this.http.principal(req)).user.id;
  return this.auth.transaction(async tx=>({wallet:await readWallet(tx,user),packs:PACKS.map(p=>({code:p.code,crystals:p.crystals,rubles:p.kopecks/100})),
   payments_enabled:yookassa()!==null,orders:(await orders(tx,user)).map(o=>({...o,created_at:o.created_at.toISOString()}))}));
 }
 @Post('orders') @HttpCode(200) async order(@Req() req:FastifyRequest,@Body() body:unknown){
  await this.http.unsafe(req);const user=(await this.http.principal(req)).user.id;
  const code=body&&typeof body==='object'&&!Array.isArray(body)&&Object.keys(body).length===1?(body as {pack?:unknown}).pack:undefined;
  if(!pack(code))throw new HttpException('Unknown pack',400);
  await this.rate.consume([{key:'aoc:premium-order:'+user,limit:10,seconds:600}]);
  const result=await this.auth.transaction(tx=>createOrder(tx,yookassa(),user,code as string,process.env.AOC_PAYMENT_RETURN_URL??'https://animeoncraft.github.io/'));
  await this.auth.transaction(tx=>this.auth.audit(tx,user,'PREMIUM_ORDER_CREATED',req.id,user));
  return result;
 }
}
/** YooKassa HTTP notifications. The body only names a payment; settle() re-reads it from YooKassa before crediting. */
@Controller('api/v1/payments')
export class PaymentWebhookController {
 constructor(@Inject(AuthService) private readonly auth:AuthService,@Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Post('yookassa') @HttpCode(200) async yookassa(@Req() req:FastifyRequest,@Body() body:unknown){
  const api=yookassa();if(!api)throw new HttpException('Payments disabled',503);
  await this.rate.consume([{key:'aoc:yookassa-webhook:'+req.ip,limit:300,seconds:60}]);
  const event=body as {event?:unknown;object?:{id?:unknown}};
  if(!event||typeof event.event!=='string'||!['payment.succeeded','payment.canceled'].includes(event.event)||typeof event.object?.id!=='string')return {ok:true};
  const id=event.object.id;
  const result=await this.auth.transaction(tx=>settle(tx,api,id));
  if(result==='CREDITED')console.log('AOC_PREMIUM_PAID payment='+id);
  return {ok:true};
 }
}
@Module({imports:[InfrastructureModule,AuthModule],controllers:[PremiumShopController,PaymentWebhookController]})
export class PremiumShopModule {}
