import {Body,Controller,Header,HttpCode,HttpException,Inject,Module,Post,Req} from '@nestjs/common';
import type {FastifyRequest} from 'fastify';
import {InfrastructureModule} from '../infrastructure/infrastructure.module.js';
import {RateLimitStoreService} from '../infrastructure/rate-limit-store.service.js';
import {AuthService} from '../modules/auth/auth.service.js';
import {GameTicketService} from '../modules/auth/game-ticket.service.js';
import {readWardrobe,selectBack,validBack,type BackChoice} from './back-equipment.js';

interface Input {server_id:string;connection_id:string;minecraft_uuid:string;action:'READ'|'BACK';kind?:BackChoice['kind'];code?:string;expected_revision?:string}
const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
export function wardrobeInput(body:unknown,server:string):Input {
 if(!body||typeof body!=='object'||Array.isArray(body))throw new HttpException('Invalid wardrobe input',400);
 const value=body as Input;
 if(Object.keys(value).some(key=>!['server_id','connection_id','minecraft_uuid','action','kind','code','expected_revision'].includes(key))||
    value.server_id!==server||typeof value.connection_id!=='string'||!uuid.test(value.connection_id)||
    typeof value.minecraft_uuid!=='string'||!uuid.test(value.minecraft_uuid)||!['READ','BACK'].includes(value.action))throw new HttpException('Invalid wardrobe identity',400);
 if(value.action==='BACK'&&(typeof value.kind!=='string'||typeof value.code!=='string'||!validBack({kind:value.kind,code:value.code})||
    typeof value.expected_revision!=='string'||!/^(0|[1-9][0-9]{0,17})$/.test(value.expected_revision)))throw new HttpException('Invalid back selection',400);
 if(value.action==='READ'&&['kind','code','expected_revision'].some(key=>key in value))throw new HttpException('Unexpected read selection',400);
 return value;
}
@Controller('integration/v1/wardrobe')
export class GameWardrobeController {
 constructor(@Inject(AuthService) private readonly auth:AuthService,
  @Inject(GameTicketService) private readonly tickets:GameTicketService,
  @Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Post() @HttpCode(200) @Header('Cache-Control','no-store')
 async wardrobe(@Req() request:FastifyRequest,@Body() body:unknown){
  const server=this.tickets.authenticateServer(request),input=wardrobeInput(body,server);
  await this.rate.consume([{key:'aoc:wardrobe:'+server+':'+input.minecraft_uuid,limit:120,seconds:60}]);
  return this.auth.transaction(async tx=>{
   const user=await this.tickets.admittedPlayer(tx,server,input);
   if(input.action==='BACK'){
    await selectBack(tx,user,{kind:input.kind!,code:input.code!},input.expected_revision);
    await this.auth.audit(tx,user,'PLAYER_BACK_CHANGED',request.id);
   }
   return readWardrobe(tx,user);
  });
 }
}
@Module({imports:[InfrastructureModule],controllers:[GameWardrobeController],providers:[AuthService,GameTicketService]})
export class GameWardrobeModule {}
