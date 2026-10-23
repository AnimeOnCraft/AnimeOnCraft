import {ForbiddenException,Inject,Injectable,UnauthorizedException} from '@nestjs/common';
import type {Prisma} from '../../generated/prisma/client.js';
import {AuthService,type Principal} from './auth.service.js';
import {capeCatalog,capePreview} from './cape-catalog.js';
import {readBack,selectBack} from '../../wardrobe/back-equipment.js';

export const CAPE_IDS = ['beta'] as const;
export async function equippedCape(tx:Prisma.TransactionClient,user:string):Promise<string|null>{
 return (await tx.player_capes.findFirst({where:{user_id:user,equipped:true},select:{cape_id:true}}))?.cape_id??null;
}
export async function awardBetaCape(tx:Prisma.TransactionClient,user:string,joined:Date):Promise<boolean>{
 const campaign=await tx.beta_campaign.findUnique({where:{id:'beta'}});
 if(!campaign?.starts_at||joined<campaign.starts_at||(campaign.ends_at&&joined>=campaign.ends_at))return false;
 const owned=await tx.player_capes.findUnique({where:{user_id_cape_id:{user_id:user,cape_id:'beta'}}});
 if(owned)return false;
 const automatic=!(await equippedCape(tx,user))&&(await readBack(tx,user)).kind!=='BETA';
 await tx.player_capes.create({data:{user_id:user,cape_id:'beta',awarded_at:joined,equipped:automatic}});
 if(automatic)await selectBack(tx,user,{kind:'CAPE',code:'beta'});
 return true;
}
@Injectable()
export class PlayerCapeService {
 constructor(@Inject(AuthService) private readonly auth:AuthService){}
 private async snapshot(tx:Prisma.TransactionClient,user:string,wardrobe=false){
  const [owned,campaign]=await Promise.all([tx.player_capes.findMany({where:{user_id:user},orderBy:{awarded_at:'asc'}}),tx.beta_campaign.findUnique({where:{id:'beta'}})]);
  const now=new Date();
  const catalog=capeCatalog();
  // Legacy launchers only accept the original Beta response shape and one cape.
  const visible=owned.filter(c=>wardrobe?catalog.some(v=>v.id===c.cape_id):c.cape_id==='beta');
  return {capes:visible.map(c=>{const entry=catalog.find(v=>v.id===c.cape_id)!;return {id:c.cape_id,name:entry.name,awarded_at:c.awarded_at.toISOString(),...(wardrobe?{description:entry.description,preview_png_base64:capePreview(entry)}:{})};}),equipped:visible.find(c=>c.equipped)?.cape_id??null,
   beta:{starts_at:campaign?.starts_at?.toISOString()??null,ends_at:campaign?.ends_at?.toISOString()??null,active:!!campaign?.starts_at&&now>=campaign.starts_at&&(!campaign.ends_at||now<campaign.ends_at)}};
 }
 async read(principal:Principal,wardrobe=false){return this.snapshot(this.auth.db,principal.user.id,wardrobe);}
 async save(principal:Principal,cape:string|null,requestId:string,wardrobe=false){
  return this.auth.transaction(async tx=>{
   const user=await this.auth.lockUser(tx,principal.user.id);
   const session=await tx.sessions.findUnique({where:{id:principal.session.id}});
   const token=await tx.auth_tokens.findUnique({where:{id:principal.access_token_id}});
   if(!user||!session||!(await this.auth.usable(tx,user))||!(await this.auth.sessionValid(tx,user,session,principal.session.client_kind))||session.user_id!==user.id||!token||token.kind!=='ACCESS'||token.session_id!==session.id||token.revoked_at||token.expires_at<=new Date())throw new UnauthorizedException();
   if(user.passwordChangeRequired||!(await tx.game_profiles.findUnique({where:{user_id:user.id}})))throw new ForbiddenException();
   if(cape!==null&&(!(wardrobe?capeCatalog().some(c=>c.id===cape):CAPE_IDS.includes(cape as 'beta'))||!(await tx.player_capes.findUnique({where:{user_id_cape_id:{user_id:user.id,cape_id:cape}}}))))throw new ForbiddenException('Cape is not owned');
   await selectBack(tx,user.id,cape===null?{kind:'NONE',code:''}:{kind:'CAPE',code:cape});
   await this.auth.audit(tx,user.id,'PLAYER_CAPE_CHANGED',requestId);
   return this.snapshot(tx,user.id,wardrobe);
  });
 }
}
