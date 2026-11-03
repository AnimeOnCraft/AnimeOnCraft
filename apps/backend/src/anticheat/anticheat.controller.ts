import { Body, Controller, Header, Headers, HttpCode, HttpException, Inject, Module, Post } from '@nestjs/common';
import { InfrastructureModule } from '../infrastructure/infrastructure.module.js';
import { DatabaseService } from '../infrastructure/database.service.js';
import { RateLimitStoreService } from '../infrastructure/rate-limit-store.service.js';
import { authenticateServer } from '../integration-auth.js';
import { notifyOwner, reportInput, storeReport } from './anticheat.js';
/** Game server → backend: one suspicion report. Service key + server identity, like other integration routes. */
@Controller('integration/v1/anticheat')
export class AnticheatIntegrationController {
 constructor(@Inject(DatabaseService) private readonly database:DatabaseService,@Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Post('reports') @HttpCode(200) @Header('Cache-Control','no-store')
 async report(@Headers('authorization') authorization:string|undefined,@Body() body:unknown){
  authenticateServer(authorization,body);
  const input=reportInput(body,process.env.AOC_GAME_SERVER_ID!);
  if(!this.database.client)throw new HttpException('Database unavailable',503);
  await this.rate.consume([{key:'aoc:anticheat:'+input.server_id,limit:240,seconds:60},{key:'aoc:anticheat:'+input.server_id+':'+input.minecraft_uuid,limit:30,seconds:60}]);
  const created=await this.database.client.$transaction(tx=>storeReport(tx,input));
  const notified=created?await notifyOwner(input):false;
  return {accepted:true,created,notified};
 }
}
@Module({imports:[InfrastructureModule],controllers:[AnticheatIntegrationController]})
export class GameAnticheatModule {}
