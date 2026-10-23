import {ConflictException,ForbiddenException,HttpException} from '@nestjs/common';
import type {Prisma} from '../generated/prisma/client.js';
import {capeCatalog} from '../modules/auth/cape-catalog.js';
import {readEmotes} from './emotes.js';

export interface BackChoice {kind:'NONE'|'CAPE'|'BETA';code:string}
export interface BackState extends BackChoice {revision:string}
export function validBack(choice:BackChoice):boolean {
 return choice.kind==='NONE'?choice.code==='':choice.kind==='CAPE'?/^[a-z][a-z0-9-]{0,31}$/.test(choice.code):choice.kind==='BETA'&&choice.code==='RIFT_MANTLE';
}
export async function readBack(tx:Prisma.TransactionClient,user:string):Promise<BackState>{
 const rows=await tx.$queryRaw<Array<{kind:BackChoice['kind'];code:string;revision:bigint}>>`
  SELECT kind,code,revision FROM player_back_equipment WHERE user_id=${user}::uuid`;
 if(rows[0])return {...rows[0],revision:rows[0].revision.toString()};
 const cape=await tx.player_capes.findFirst({where:{user_id:user,equipped:true},select:{cape_id:true}});
 return {kind:cape?'CAPE':'NONE',code:cape?.cape_id??'',revision:'0'};
}
/** Caller holds the same users row lock used by launcher cape/account mutations. */
export async function selectBack(tx:Prisma.TransactionClient,user:string,choice:BackChoice,expected?:string):Promise<BackState>{
 if(!validBack(choice))throw new ForbiddenException('Invalid back cosmetic');
 if(choice.kind==='CAPE'&&(!capeCatalog().some(c=>c.id===choice.code)||!(await tx.player_capes.findUnique({where:{user_id_cape_id:{user_id:user,cape_id:choice.code}}}))))throw new ForbiddenException('Cape is not owned');
 const before=await readBack(tx,user);
 await tx.$executeRaw`INSERT INTO player_back_equipment(user_id,kind,code) VALUES (${user}::uuid,${before.kind},${before.code}) ON CONFLICT(user_id) DO NOTHING`;
 const current=(await tx.$queryRaw<Array<{kind:BackChoice['kind'];code:string;revision:bigint}>>`
  SELECT kind,code,revision FROM player_back_equipment WHERE user_id=${user}::uuid FOR UPDATE`)[0]!;
 const same=current.kind===choice.kind&&current.code===choice.code;
 if(expected!==undefined&&current.revision!==BigInt(expected)&&!same)throw new ConflictException('Wardrobe changed; refresh and choose again');
 if(same)return {...current,revision:current.revision.toString()};
 await tx.player_capes.updateMany({where:{user_id:user,equipped:true},data:{equipped:false}});
 if(choice.kind==='CAPE')await tx.player_capes.update({where:{user_id_cape_id:{user_id:user,cape_id:choice.code}},data:{equipped:true}});
 const rows=await tx.$queryRaw<Array<{revision:bigint}>>`
  UPDATE player_back_equipment SET kind=${choice.kind},code=${choice.code},revision=revision+1,updated_at=now()
  WHERE user_id=${user}::uuid RETURNING revision`;
 return {...choice,revision:rows[0]!.revision.toString()};
}
export async function readWardrobe(tx:Prisma.TransactionClient,user:string){
 const catalog=capeCatalog();
 const owned=await tx.player_capes.findMany({where:{user_id:user},orderBy:[{awarded_at:'asc'},{cape_id:'asc'}]});
 const capes=owned.filter(c=>catalog.some(entry=>entry.id===c.cape_id)).map(c=>{
  const entry=catalog.find(entry=>entry.id===c.cape_id)!;
  return {id:c.cape_id,name:entry.name.replace(/[\p{Cc}\p{Cf}§]/gu,'').slice(0,80)};
 });
 if(capes.length>32)throw new HttpException('Too many capes for the game wardrobe',503);
 return {back:await readBack(tx,user),capes,emotes:await readEmotes(tx,user)};
}
