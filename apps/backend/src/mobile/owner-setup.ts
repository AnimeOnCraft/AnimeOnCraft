import { ForbiddenException, HttpException, Inject, Injectable, UnauthorizedException } from '@nestjs/common';
import { AuthService } from '../modules/auth/auth.service.js';
import { digest, opaque, validOpaque } from '../modules/auth/auth-crypto.js';

interface Pending { userId:string; epoch:bigint; hash:string; ciphertext:string; expires:number }
const rejected=()=>new UnauthorizedException('Invalid credentials or expired setup');
/** Restricted MFA bootstrap. It never creates a session or grants management access. */
@Injectable()
export class MobileOwnerSetup {
  private pending=new Map<string,Pending>();
  constructor(@Inject(AuthService) private readonly auth:AuthService){}
  async start(login:string,password:string,ip:string,requestId:string){
    const key=login.trim().toLowerCase();await this.auth.limits('login',ip,key);
    const initial=await this.auth.db.user.findUnique({where:key.includes('@')?{emailKey:key}:{loginKey:key}});
    const verified=await this.auth.crypto.verify(initial?.passwordHash,password);
    if(!initial||!verified)throw rejected();
    for(const[key,value]of this.pending)if(value.expires<=Date.now())this.pending.delete(key);
    if(this.pending.size>=32)throw new HttpException('Try later',429);
    const token=opaque('ms'),expires=Date.now()+300000;
    const data=await this.auth.transaction(async tx=>{
      const user=await this.auth.lockUser(tx,initial.id);
      if(!user||user.passwordHash!==initial.passwordHash||user.systemRole!=='OWNER'||user.passwordChangeRequired||!await this.auth.usable(tx,user))throw rejected();
      const credential=await tx.mfa_credentials.findUnique({where:{user_id:user.id}});
      if(credential?.enabled)throw new ForbiddenException('MFA is already enabled; sign in normally');
      const totp=this.auth.crypto.totp(undefined,user.login);
      const ciphertext=this.auth.crypto.encrypt(totp.secret.base32,user.id);
      await tx.mfa_credentials.upsert({where:{user_id:user.id},create:{user_id:user.id,secret_ciphertext:ciphertext,key_id:this.auth.crypto.keyId,enabled:false,pending_expires_at:new Date(expires),last_used_step:-1n},update:{secret_ciphertext:ciphertext,key_id:this.auth.crypto.keyId,enabled:false,pending_expires_at:new Date(expires),last_used_step:-1n}});
      await this.auth.audit(tx,user.id,'MOBILE_MFA_ENROLLMENT_STARTED',requestId);
      return {state:{userId:user.id,epoch:user.authEpoch,hash:user.passwordHash,ciphertext,expires},secret:totp.secret.base32,otpauth_uri:totp.toString()};
    });
    for(const[key,value]of this.pending)if(value.userId===data.state.userId)this.pending.delete(key);
    this.pending.set(digest(token),data.state);
    return {token,secret:data.secret,otpauth_uri:data.otpauth_uri,expires_in:300};
  }
  async confirm(token:string,password:string,proof:string,ip:string,requestId:string){
    await this.auth.limits('mfa',ip,'mobile-setup:'+digest(token));
    const state=validOpaque(token,'ms')?this.pending.get(digest(token)):undefined;
    if(!state||state.expires<=Date.now())throw rejected();
    if(!await this.auth.crypto.verify(state.hash,password))throw rejected();
    const codes=Array.from({length:10},()=>opaque('rc'));
    await this.auth.transaction(async tx=>{
      const user=await this.auth.lockUser(tx,state.userId);
      if(!user||user.systemRole!=='OWNER'||user.passwordChangeRequired||user.passwordHash!==state.hash||user.authEpoch!==state.epoch||!await this.auth.usable(tx,user))throw rejected();
      const credential=await tx.mfa_credentials.findUnique({where:{user_id:user.id}});
      if(!credential||credential.enabled||credential.secret_ciphertext!==state.ciphertext||!credential.pending_expires_at||credential.pending_expires_at.getTime()<=Date.now()||!/^\d{6}$/.test(proof)||!await this.auth.mfaProof(tx,user.id,proof,true))throw rejected();
      await tx.mfa_credentials.update({where:{user_id:user.id},data:{enabled:true,pending_expires_at:null}});
      await tx.mfa_recovery_codes.createMany({data:codes.map(code=>({user_id:user.id,code_hash:digest(code)}))});
      await tx.user.update({where:{id:user.id},data:{authEpoch:{increment:1},version:{increment:1}}});
      await this.auth.revoke(tx,user.id);await this.auth.audit(tx,user.id,'MFA_ENABLED',requestId);
    });
    this.pending.delete(digest(token));return {recovery_codes:codes,reauthentication_required:true};
  }
}
