import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {Redis} from 'ioredis';
import type {NestFastifyApplication} from '@nestjs/platform-fastify';
import type {PrismaClient} from '../src/generated/prisma/client.js';
import type {AuthService} from '../src/modules/auth/auth.service.js';
import {offlinePlayerUuid} from '../src/modules/auth/game-profile.service.js';

/** Actual HTTP/SQL/Argon2/MFA flow, exclusively in friends-smoke's disposable database. */
export async function launcherOwnerPasswordCases(app:NestFastifyApplication,db:PrismaClient,auth:AuthService,ownerId:string,hash:string,password:string){
 const crypto=auth.crypto,owner=await db.user.findUniqueOrThrow({where:{id:ownerId}});
 const player=await db.user.create({data:{login:'ResetAccount',email:'reset-player@example.test',emailKey:'reset-player@example.test',passwordHash:hash}});
 await db.game_profiles.create({data:{user_id:player.id,game_name:'ResetNick',minecraft_uuid:offlinePlayerUuid('ResetNick')}});
 const session=async(id:string)=>auth.transaction(async tx=>auth.newSession(tx,await tx.user.findUniqueOrThrow({where:{id}}),'LAUNCHER',false,randomUUID()));
 const ownerPair=await session(owner.id),playerPair=await session(player.id);
 const headers=(pair:typeof ownerPair,key=randomUUID())=>({'x-aoc-client':'launcher',authorization:`Bearer ${pair.access_token}`,'idempotency-key':key});
 const post=(pair:typeof ownerPair,body:object,key=randomUUID())=>app.inject({method:'POST',url:'/api/v1/launcher/owner/password-reset',headers:headers(pair,key),payload:body,remoteAddress:'127.0.7.1'});
 const input={login:'ResetNick',password,proof:'123456',reason:'Owner recovery integration fixture'};
 assert.equal((await post(playerPair,input)).statusCode,403,'Only OWNER can reset a player');
 assert.equal((await post(ownerPair,{...input,password:'Wrong-Fixture-Password!'})).statusCode,403);
 const mfa=await db.mfa_credentials.findUniqueOrThrow({where:{user_id:owner.id}});
 await db.mfa_credentials.update({where:{user_id:owner.id},data:{enabled:false,pending_expires_at:new Date(Date.now()+300000)}});
 assert.equal((await post(ownerPair,input)).json<{error:{code:string}}>().error.code,'OWNER_MFA_REQUIRED');
 // Independent negative and positive cases share an OWNER; start a new fixture-only MFA budget window.
 const namespace=process.env.AOC_AUTH_RATE_NAMESPACE!;assert.match(namespace,/^aoc:friends:test:[a-f0-9-]{36}$/);
 const redisUrl=new URL(process.env.VALKEY_URL!);assert.equal(redisUrl.hostname,'127.0.0.1');assert.equal(redisUrl.port,'56379');
 const rate=new Redis(redisUrl.toString(),{lazyConnect:true,enableOfflineQueue:false,maxRetriesPerRequest:0,retryStrategy:()=>null});
 try{await rate.connect();await rate.del(`${namespace}:mfa:${crypto.accountKey('owner-reset:'+owner.id)}`);}finally{rate.disconnect();}
 const totp=crypto.totp();await db.mfa_credentials.update({where:{user_id:owner.id},data:{enabled:true,pending_expires_at:null,secret_ciphertext:crypto.encrypt(totp.secret.base32,owner.id),last_used_step:-1n}});
 try {
  assert.equal((await db.user.findUniqueOrThrow({where:{id:player.id}})).passwordHash,hash,'Rejected request changed password');
  const proof=totp.generate(),key=randomUUID();
  const response=await post(ownerPair,{...input,login:'resetnick',proof},key);assert.equal(response.statusCode,201);
  const reset=response.json<{user_id:string;login:string;game_name:string;temporary_password:string}>();
  assert.equal(reset.user_id,player.id);assert.equal(reset.login,'ResetAccount');assert.equal(reset.game_name,'ResetNick');
  assert.match(reset.temporary_password,/^[A-Za-z0-9_-]{24}$/);
  const after=await db.user.findUniqueOrThrow({where:{id:player.id}});
  assert.equal(after.passwordChangeRequired,true);assert.equal(after.authEpoch,player.authEpoch+1n);
  assert.ok(await crypto.verify(after.passwordHash,reset.temporary_password));assert.equal(await crypto.verify(after.passwordHash,password),false);
  assert.equal(await db.sessions.count({where:{user_id:player.id,revoked_at:null}}),0);
  assert.equal(await db.auth_tokens.count({where:{session:{user_id:player.id},revoked_at:null}}),0);
  const replay=await post(ownerPair,{...input,login:'resetnick',proof},key);assert.equal(replay.statusCode,201);
  assert.equal(replay.json<{temporary_password:null}>().temporary_password,null,'Replay exposed a password');
  const audit=await db.audit_logs.findMany({where:{action:'PLAYER_PASSWORD_RESET',target_id:player.id}});assert.equal(audit.length,1);
  assert.equal(JSON.stringify(audit).includes(reset.temporary_password),false,'Password leaked into audit');
  assert.equal((await post(ownerPair,{...input,proof})).statusCode,403,'MFA code was reused');
  const collision=await db.user.create({data:{login:'ResetNick',email:'reset-collision@example.test',emailKey:'reset-collision@example.test',passwordHash:hash}});
  const ambiguous=await post(ownerPair,{...input,proof});assert.equal(ambiguous.statusCode,409);assert.equal(ambiguous.json<{error:{code:string}}>().error.code,'AMBIGUOUS_PLAYER');
  assert.equal((await db.user.findUniqueOrThrow({where:{id:collision.id}})).passwordHash,hash);
  assert.equal((await db.user.findUniqueOrThrow({where:{id:player.id}})).passwordHash,after.passwordHash);
  assert.equal((await post(ownerPair,{...input,login:player.login,proof})).statusCode,403);
  assert.equal((await post(ownerPair,{...input,login:player.login,proof})).statusCode,429,'MFA retry budget must still apply');
  console.log('AOC_OWNER_PASSWORD_RESET_HTTP_PASS owner_only=true mfa=true nickname=true ambiguous_rejected=true revoked_sessions=true one_time_secret=true');
 } finally {
  await db.mfa_credentials.update({where:{user_id:owner.id},data:{enabled:mfa.enabled,pending_expires_at:mfa.pending_expires_at,secret_ciphertext:mfa.secret_ciphertext,last_used_step:mfa.last_used_step}});
 }
}
