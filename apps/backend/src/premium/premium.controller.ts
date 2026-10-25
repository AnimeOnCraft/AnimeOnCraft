import {Body,Controller,Header,HttpCode,HttpException,Inject,Module,Post,Req} from '@nestjs/common';
import type {FastifyRequest} from 'fastify';
import {InfrastructureModule} from '../infrastructure/infrastructure.module.js';
import {RateLimitStoreService} from '../infrastructure/rate-limit-store.service.js';
import {AuthService} from '../modules/auth/auth.service.js';
import {GameTicketService} from '../modules/auth/game-ticket.service.js';
import {CRYSTALS,GIFT_MAX,earnGuild,earnLevels,grantAll,readWallet,validRef} from './crystals.js';
import {gachaState,roll,secureRng,validCount,type Source} from './gacha.js';

type Action='READ'|'LEVEL'|'GUILD'|'GUILD_TEAM';
interface Input {server_id:string;connection_id:string;minecraft_uuid:string;action:Action;level?:number;rank?:string;ref?:string}
const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const fields:Record<Action,string[]>={READ:[],LEVEL:['level'],GUILD:['rank','ref'],GUILD_TEAM:['rank','ref']};
/** The game server reports what happened; the amount is always decided by the backend. */
export function premiumInput(body:unknown,server:string):Input {
 if(!body||typeof body!=='object'||Array.isArray(body))throw new HttpException('Invalid crystal input',400);
 const value=body as Input;
 if(value.server_id!==server||typeof value.connection_id!=='string'||!uuid.test(value.connection_id)||
    typeof value.minecraft_uuid!=='string'||!uuid.test(value.minecraft_uuid)||!Object.hasOwn(fields,value.action as string))throw new HttpException('Invalid crystal identity',400);
 const allowed=['server_id','connection_id','minecraft_uuid','action',...fields[value.action]];
 if(Object.keys(value).some(key=>!allowed.includes(key))||fields[value.action].some(key=>!(key in value)))throw new HttpException('Unexpected crystal fields',400);
 if(value.action==='LEVEL'&&(!Number.isInteger(value.level)||value.level!<1||value.level!>100))throw new HttpException('Invalid level',400);
 if((value.action==='GUILD'||value.action==='GUILD_TEAM')&&(typeof value.rank!=='string'||!(value.rank in CRYSTALS.guildQuest)||!validRef(value.ref)||!value.ref.startsWith('guild:')))throw new HttpException('Invalid guild reward',400);
 return value;
}
@Controller('integration/v1/premium')
export class GamePremiumController {
 constructor(@Inject(AuthService) private readonly auth:AuthService,
  @Inject(GameTicketService) private readonly tickets:GameTicketService,
  @Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Post() @HttpCode(200) @Header('Cache-Control','no-store')
 async premium(@Req() request:FastifyRequest,@Body() body:unknown){
  const server=this.tickets.authenticateServer(request),input=premiumInput(body,server);
  await this.rate.consume([{key:'aoc:premium:'+server+':'+input.minecraft_uuid,limit:120,seconds:60}]);
  return this.auth.transaction(async tx=>{
   const user=await this.tickets.admittedPlayer(tx,server,input);
   let granted=0;
   if(input.action==='LEVEL')granted=await earnLevels(tx,user,input.level!);
   else if(input.action!=='READ')granted=await earnGuild(tx,user,input.action,input.rank!,input.ref!);
   return {granted,wallet:await readWallet(tx,user)};
  });
 }
 /** Operator gift from the in-game command. The operator must be an admitted player, recorded in the audit log. */
 @Post('grant-all') @HttpCode(200) @Header('Cache-Control','no-store')
 async giftAll(@Req() request:FastifyRequest,@Body() body:unknown){
  const server=this.tickets.authenticateServer(request),input=giftInput(body,server);
  await this.rate.consume([{key:'aoc:premium-gift:'+server,limit:5,seconds:60}]);
  return this.auth.transaction(async tx=>{
   const actor=await this.tickets.admittedPlayer(tx,server,input);
   const result=await grantAll(tx,input.amount,input.gift_id);
   await this.auth.audit(tx,actor,'PREMIUM_GIFT_ALL',request.id,actor);
   return result;
  });
 }
}
interface GiftInput {server_id:string;connection_id:string;minecraft_uuid:string;amount:number;gift_id:string}
export function giftInput(body:unknown,server:string):GiftInput {
 if(!body||typeof body!=='object'||Array.isArray(body))throw new HttpException('Invalid gift input',400);
 const value=body as GiftInput,allowed=['server_id','connection_id','minecraft_uuid','amount','gift_id'];
 if(value.server_id!==server||typeof value.connection_id!=='string'||!uuid.test(value.connection_id)||
    typeof value.minecraft_uuid!=='string'||!uuid.test(value.minecraft_uuid)||typeof value.gift_id!=='string'||!uuid.test(value.gift_id)||
    Object.keys(value).some(key=>!allowed.includes(key))||!Number.isInteger(value.amount)||value.amount<1||value.amount>GIFT_MAX)throw new HttpException('Invalid gift',400);
 return value;
}
interface GachaInput {server_id:string;connection_id:string;minecraft_uuid:string;action:'READ'|'ROLL';count?:1|10;request_id?:string;source?:Source}
export function gachaInput(body:unknown,server:string):GachaInput {
 if(!body||typeof body!=='object'||Array.isArray(body))throw new HttpException('Invalid gacha input',400);
 const value=body as GachaInput;
 if(value.server_id!==server||typeof value.connection_id!=='string'||!uuid.test(value.connection_id)||
    typeof value.minecraft_uuid!=='string'||!uuid.test(value.minecraft_uuid)||!['READ','ROLL'].includes(value.action))throw new HttpException('Invalid gacha identity',400);
 const allowed=['server_id','connection_id','minecraft_uuid','action',...(value.action==='ROLL'?['count','request_id','source']:[])];
 if(Object.keys(value).some(key=>!allowed.includes(key)))throw new HttpException('Unexpected gacha fields',400);
 if(value.action==='ROLL'&&(!validCount(value.count)||typeof value.request_id!=='string'||!uuid.test(value.request_id)||
    value.source!==undefined&&value.source!=='CRYSTALS'&&value.source!=='FRAGMENTS'))throw new HttpException('Invalid roll',400);
 return value;
}
@Controller('integration/v1/gacha')
export class GameGachaController {
 constructor(@Inject(AuthService) private readonly auth:AuthService,
  @Inject(GameTicketService) private readonly tickets:GameTicketService,
  @Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Post() @HttpCode(200) @Header('Cache-Control','no-store')
 async gacha(@Req() request:FastifyRequest,@Body() body:unknown){
  const server=this.tickets.authenticateServer(request),input=gachaInput(body,server);
  await this.rate.consume([{key:'aoc:gacha:'+server+':'+input.minecraft_uuid,limit:60,seconds:60}]);
  return this.auth.transaction(async tx=>{
   const user=await this.tickets.admittedPlayer(tx,server,input);
   if(input.action==='READ')return gachaState(tx,user);
   const result=await roll(tx,user,input.request_id!,input.count!,secureRng,input.source??'CRYSTALS');
   if(!result.replayed)await this.auth.audit(tx,user,'GACHA_ROLL',request.id,user);
   return result;
  });
 }
}
@Module({imports:[InfrastructureModule],controllers:[GamePremiumController,GameGachaController],providers:[AuthService,GameTicketService]})
export class GamePremiumModule {}
