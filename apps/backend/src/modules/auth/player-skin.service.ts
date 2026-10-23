import {BadRequestException,ForbiddenException,Inject,Injectable,UnauthorizedException} from '@nestjs/common';
import {PNG} from 'pngjs';
import {AuthService,type Principal} from './auth.service.js';

export function normalizeSkin(base64:string):Buffer {
 try {
  if(base64.length>175000||base64.length%4!==0||!/^[A-Za-z0-9+/]+={0,2}$/.test(base64))throw Error();
  const bytes=Buffer.from(base64,'base64');
  if(bytes.length<33||bytes.length>131072||bytes.subarray(0,8).toString('hex')!=='89504e470d0a1a0a'||bytes.toString('ascii',12,16)!=='IHDR'||bytes.readUInt32BE(16)!==64||bytes.readUInt32BE(20)!==64)throw Error();
  const image=PNG.sync.read(bytes,{checkCRC:true});
  // Opaque base layers: Minecraft skins must not make body parts invisible.
  for(const [x,y,w,h] of [[0,0,32,16],[0,16,64,16],[16,48,32,16]])for(let dy=y!;dy<y!+h!;dy++)for(let dx=x!;dx<x!+w!;dx++)image.data[(dy*64+dx)*4+3]=255;
  const normalized=PNG.sync.write(image,{colorType:6,bitDepth:8});
  if(normalized.length>24000)throw Error();
  return normalized;
 }catch{throw new BadRequestException('Use a PNG Minecraft skin, 64 x 64 pixels, up to 128 KiB');}
}
export function skinView(row:{png:Uint8Array;model:string;updated_at:Date}|null){return {skin:row?{png_base64:Buffer.from(row.png).toString('base64'),model:row.model,updated_at:row.updated_at.toISOString()}:null};}
@Injectable()
export class PlayerSkinService {
 constructor(@Inject(AuthService) private readonly auth:AuthService){}
 async read(principal:Principal){return skinView(await this.auth.db.player_skins.findUnique({where:{user_id:principal.user.id}}));}
 async save(principal:Principal,png:string|null,model:'classic'|'slim',requestId:string){
  const bytes=png===null?null:normalizeSkin(png);
  const result=await this.auth.transaction(async tx=>{
   const user=await this.auth.lockUser(tx,principal.user.id);
   const session=await tx.sessions.findUnique({where:{id:principal.session.id}});
   const token=await tx.auth_tokens.findUnique({where:{id:principal.access_token_id}});
   if(!user||!session||!(await this.auth.usable(tx,user))||!(await this.auth.sessionValid(tx,user,session,principal.session.client_kind))||!token||session.user_id!==user.id||token.kind!=='ACCESS'||token.session_id!==session.id||token.revoked_at||token.expires_at<=new Date())throw new UnauthorizedException();
   if(user.passwordChangeRequired||!(await tx.game_profiles.findUnique({where:{user_id:user.id}})))throw new ForbiddenException('Choose a game profile first');
   if(bytes)await tx.player_skins.upsert({where:{user_id:user.id},create:{user_id:user.id,png:Uint8Array.from(bytes),model},update:{png:Uint8Array.from(bytes),model,updated_at:new Date()}});
   else await tx.player_skins.deleteMany({where:{user_id:user.id}});
   await this.auth.audit(tx,user.id,bytes?'PLAYER_SKIN_CHANGED':'PLAYER_SKIN_REMOVED',requestId);
   return skinView(await tx.player_skins.findUnique({where:{user_id:user.id}}));
  });
  return result;
 }
}
