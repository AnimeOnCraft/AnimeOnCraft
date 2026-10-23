import { BadRequestException, ConflictException, Inject, Injectable, UnauthorizedException } from '@nestjs/common';
import { AuthService, type Principal } from './auth.service.js';
import { MinecraftProofService } from './minecraft-proof.service.js';
import { digest, validOpaque } from './auth-crypto.js';
@Injectable()
export class MinecraftBindingService {
  constructor(@Inject(AuthService) private readonly auth: AuthService, @Inject(MinecraftProofService) private readonly provider: MinecraftProofService) {}
  async challenge(principal: Principal, password: string): Promise<{challenge:string;expires_in:number}> {
    this.provider.assertAvailable(); await this.auth.limits('bind','',principal.user.id);
    return this.auth.credentialAction(principal,password,async(tx,user,session)=>({challenge:await this.auth.challenge(tx,user,'MINECRAFT_BIND',5,undefined,session.id),expires_in:300}));
  }
  async bind(principal: Principal, challengeToken: string, providerToken: string, requestId: string) {
    this.provider.assertAvailable();
    if(!validOpaque(challengeToken,'ac')) throw new UnauthorizedException();
    // Avoid provider calls for foreign/expired challenges. Everything is rechecked under locks after network I/O.
    const initial=await this.auth.db.auth_challenges.findUnique({where:{token_hash:digest(challengeToken)}});
    if(!initial || initial.kind!=='MINECRAFT_BIND' || initial.user_id!==principal.user.id || initial.session_id!==principal.session.id || initial.consumed_at || initial.expires_at<=new Date()) throw new UnauthorizedException();
    const proof=await this.provider.verify(providerToken);
    const result = await this.auth.transaction(async tx=>{
      const user=await this.auth.lockUser(tx,principal.user.id); const session=await tx.sessions.findUnique({where:{id:principal.session.id}});
      const challenge=await tx.auth_challenges.findUnique({where:{id:initial.id}});
      if(!user || !(await this.auth.usable(tx,user)) || !session || !(await this.auth.sessionValid(tx,user,session,principal.session.client_kind))
        || !challenge || challenge.consumed_at || challenge.expires_at<=new Date() || challenge.auth_epoch!==user.authEpoch || proof.checked_at < Date.now()-60000 || proof.checked_at>Date.now()+1000) return null;
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${'minecraft:'+proof.uuid},0))`;
      const existing=await tx.minecraft_accounts.findUnique({where:{minecraft_uuid:proof.uuid}});
      if(existing && existing.user_id!==user.id) throw new ConflictException('Minecraft account is already linked');
      const active=await tx.minecraft_accounts.findFirst({where:{user_id:user.id,state:'VERIFIED'}});
      if(active && active.minecraft_uuid!==proof.uuid) throw new ConflictException('Unlink the current Minecraft account before changing identity');
      await tx.auth_challenges.update({where:{id:challenge.id},data:{consumed_at:new Date()}});
      const account=await tx.minecraft_accounts.upsert({where:{minecraft_uuid:proof.uuid},create:{user_id:user.id,minecraft_uuid:proof.uuid,nickname:proof.nickname,state:'VERIFIED',verified_at:new Date()},
        update:{nickname:proof.nickname,state:'VERIFIED',verified_at:new Date(),revoked_at:null,version:{increment:1}}});
      const changed=await tx.user.update({where:{id:user.id},data:{authEpoch:{increment:1},accessEpoch:{increment:1},version:{increment:1}}});
      await this.auth.revoke(tx,user.id); await this.auth.audit(tx,user.id,'MINECRAFT_VERIFIED',requestId,account.id);
      await tx.outbox_events.create({data:{type:'MINECRAFT_IDENTITY_CHANGED',aggregate_id:user.id,aggregate_version:changed.accessEpoch,payload:{user_id:user.id,minecraft_uuid:proof.uuid,state:'VERIFIED'}}});
      return {minecraft_uuid:account.minecraft_uuid,nickname:account.nickname,state:account.state,reauthentication_required:true};
    });
    if(!result) throw new UnauthorizedException(); return result;
  }
  async unlink(principal: Principal, password: string, requestId: string): Promise<void> {
    await this.auth.credentialAction(principal,password,async(tx,user,session)=>{
      const mfa=await tx.mfa_credentials.findUnique({where:{user_id:user.id}});
      if(mfa?.enabled && (!session.last_step_up_at || session.last_step_up_at.getTime()<Date.now()-10*60000)) throw new UnauthorizedException('Recent step-up required');
      const account=await tx.minecraft_accounts.findFirst({where:{user_id:user.id,state:'VERIFIED'}}); if(!account) throw new BadRequestException('No verified Minecraft account');
      await tx.minecraft_accounts.update({where:{id:account.id},data:{state:'REVOKED',revoked_at:new Date(),version:{increment:1}}});
      const changed=await tx.user.update({where:{id:user.id},data:{authEpoch:{increment:1},accessEpoch:{increment:1},version:{increment:1}}});
      await this.auth.revoke(tx,user.id); await this.auth.audit(tx,user.id,'MINECRAFT_REVOKED',requestId,account.id);
      await tx.outbox_events.create({data:{type:'MINECRAFT_IDENTITY_CHANGED',aggregate_id:user.id,aggregate_version:changed.accessEpoch,payload:{user_id:user.id,minecraft_uuid:account.minecraft_uuid,state:'REVOKED'}}});
    });
  }
}
