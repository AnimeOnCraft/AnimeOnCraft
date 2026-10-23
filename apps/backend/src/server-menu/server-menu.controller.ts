import {Body,Controller,Header,Headers,HttpCode,HttpException,Inject,Post} from '@nestjs/common';
import {DatabaseService} from '../infrastructure/database.service.js';
import {RateLimitStoreService} from '../infrastructure/rate-limit-store.service.js';
import {authenticateServer} from '../integration-auth.js';
import type {Prisma} from '../generated/prisma/client.js';
import {CLAN_ACHIEVEMENTS} from '../clan-achievements.js';

/** Read-only, service-authenticated roster. No account identifiers or private coordinates. */
export async function readServerMenu(tx:Prisma.TransactionClient){
 const clans=await tx.$queryRaw<Array<{code:string;name:string;color:string|null;xp:bigint;level:number;reputation:number;points:bigint;members:bigint}>>`
  SELECT c.code_key AS code,c.name,c.color,COALESCE(p.xp,0) AS xp,COALESCE(p.level,1) AS level,
   COALESCE(p.reputation,0) AS reputation,
   (SELECT count(*) FROM strategic_points s JOIN world_catalog w ON w.world_id=s.world_id
     WHERE w.active AND s.enabled AND s.capturable AND s.owner_clan_id=c.id) AS points,
   (SELECT count(*) FROM clan_members m JOIN users u ON u.id=m.user_id JOIN game_profiles g ON g.user_id=u.id
     WHERE m.clan_id=c.id AND m.ended_at IS NULL AND u.state='ACTIVE') AS members
  FROM clans c LEFT JOIN clan_progression p ON p.clan_id=c.id
  WHERE c.enabled AND c.archived_at IS NULL ORDER BY c.created_at,c.id LIMIT 65`;
 const members=await tx.$queryRaw<Array<{uuid:string;name:string;clan:string;role:string}>>`
  SELECT g.minecraft_uuid::text AS uuid,g.game_name AS name,c.code_key AS clan,
   CASE WHEN u.system_role='OWNER' THEN 'OWNER' WHEN EXISTS
    (SELECT 1 FROM clan_manager_assignments a WHERE a.user_id=u.id AND a.clan_id=c.id AND a.ended_at IS NULL)
    THEN 'MANAGER' ELSE 'MEMBER' END AS role
  FROM clan_members m JOIN clans c ON c.id=m.clan_id JOIN users u ON u.id=m.user_id JOIN game_profiles g ON g.user_id=u.id
  WHERE m.ended_at IS NULL AND c.enabled AND c.archived_at IS NULL AND u.state='ACTIVE'
  ORDER BY c.created_at,c.id,lower(g.game_name),g.minecraft_uuid LIMIT 513`;
 if(clans.length>64||members.length>512)throw new HttpException('Menu roster exceeds supported size',503);
 const achievements=await tx.$queryRaw<Array<{clan:string;code:string;progress:bigint;unlocked_at:Date|null}>>`
  SELECT c.code_key AS clan,a.code,a.progress,a.unlocked_at FROM clan_achievements a JOIN clans c ON c.id=a.clan_id
   WHERE c.enabled AND c.archived_at IS NULL ORDER BY c.code_key,a.code LIMIT 2049`;
 if(achievements.length>2048)throw new HttpException('Too many clan achievements',503);
 return {clans:clans.map(c=>({code:c.code,name:c.name.replace(/[\p{Cc}\p{Cf}§]/gu,'').slice(0,64),color:c.color??'A970FF',xp:c.xp.toString(),level:c.level,reputation:c.reputation,points:Number(c.points),members:Number(c.members)})),members,
  achievement_definitions:CLAN_ACHIEVEMENTS.map(a=>({code:a.code,name:a.name,metric:a.metric,target:a.target,xp:a.xp,reputation:a.reputation})),
  clan_achievements:clans.map(c=>({clan:c.code,rows:CLAN_ACHIEVEMENTS.map(a=>{const row=achievements.find(r=>r.clan===c.code&&r.code===a.code);return {code:a.code,progress:Number(row?.progress??0n),unlocked:row?.unlocked_at!=null};})}))};
}

@Controller('integration/v1/server-menu')
export class ServerMenuController {
 constructor(@Inject(DatabaseService) private readonly database:DatabaseService,@Inject(RateLimitStoreService) private readonly rate:RateLimitStoreService){}
 @Post('snapshot') @HttpCode(200) @Header('Cache-Control','no-store')
 async snapshot(@Headers('authorization') authorization:string|undefined,@Body() body:unknown){
  authenticateServer(authorization,body);
  if(Object.keys(body as object).some(key=>key!=='server_id'))throw new HttpException('Unexpected menu input',400);
  await this.rate.consume([{key:'aoc:server-menu:'+process.env.AOC_GAME_SERVER_ID,limit:120,seconds:60}]);
  const client=this.database.client;if(!client)throw new HttpException('Database unavailable',503);
  return client.$transaction(tx=>readServerMenu(tx),{isolationLevel:'RepeatableRead',timeout:5000});
 }
}
