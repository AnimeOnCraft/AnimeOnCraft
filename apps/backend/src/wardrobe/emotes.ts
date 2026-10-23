import type {Prisma} from '../generated/prisma/client.js';
import {ForbiddenException} from '@nestjs/common';

/** Stable cosmetic IDs: future season/shop rewards can grant IDs using this same table.
 * Only server-controlled code grants ownership; there is deliberately no player grant endpoint. */
export const EMOTES=Object.freeze([
 {code:'NEON_DANCE',name:'Неоновый ритм',kind:'DANCE',starter:true},
 {code:'HERO_POSE',name:'Герой',kind:'POSE',starter:true},
 {code:'ZEN_POSE',name:'Медитация',kind:'POSE',starter:true},
]);
export async function grantEmote(tx:Prisma.TransactionClient,user:string,code:string,source:string):Promise<void>{
 if(!EMOTES.some(e=>e.code===code)||!source||source.length>80)throw new ForbiddenException('Unknown emote reward');
 await tx.$executeRaw`INSERT INTO player_emote_grants(user_id,code,source) VALUES (${user}::uuid,${code},${source}) ON CONFLICT DO NOTHING`;
}
export async function readEmotes(tx:Prisma.TransactionClient,user:string):Promise<string[]>{
 await tx.$executeRaw`INSERT INTO player_emote_grants(user_id,code,source)
  SELECT ${user}::uuid,code,'starter' FROM unnest(ARRAY['NEON_DANCE','HERO_POSE','ZEN_POSE']) AS code
  ON CONFLICT DO NOTHING`;
 const grants=await tx.$queryRaw<Array<{code:string}>>`SELECT code FROM player_emote_grants WHERE user_id=${user}::uuid ORDER BY code LIMIT 129`;
 return grants.filter(row=>EMOTES.some(e=>e.code===row.code)).map(row=>row.code);
}
