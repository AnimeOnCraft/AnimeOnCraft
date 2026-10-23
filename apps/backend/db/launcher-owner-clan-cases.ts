import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import type {NestFastifyApplication} from '@nestjs/platform-fastify';
import type {PrismaClient} from '../src/generated/prisma/client.js';
import type {AuthService} from '../src/modules/auth/auth.service.js';
import {offlinePlayerUuid} from '../src/modules/auth/game-profile.service.js';

export async function launcherOwnerClanCases(app:NestFastifyApplication,db:PrismaClient,auth:AuthService,ownerId:string,hash:string,password:string){
 const owner=await db.user.findUniqueOrThrow({where:{id:ownerId}});
 await db.game_profiles.create({data:{user_id:owner.id,game_name:'ClanOwnerTest',minecraft_uuid:offlinePlayerUuid('ClanOwnerTest')}});
 const player=await db.user.create({data:{login:'ClanApplicant',email:'clanapplicant@example.test',emailKey:'clanapplicant@example.test',passwordHash:hash}});
 await db.game_profiles.create({data:{user_id:player.id,game_name:'ClanApplicant',minecraft_uuid:offlinePlayerUuid('ClanApplicant')}});
 async function session(user:typeof owner){const pair=await auth.transaction(tx=>auth.newSession(tx,user,'LAUNCHER',false,randomUUID()));return {'x-aoc-client':'launcher',authorization:`Bearer ${pair.access_token}`};}
 const own=await session(owner),ordinary=await session(player);
 const request=(method:'GET'|'POST',route:string,headers:Record<string,string>,payload?:object)=>app.inject({method,url:`/api/v1/${route}`,headers,...payload===undefined?{}:{payload},remoteAddress:'127.0.5.1'});
 const write=(headers:Record<string,string>,version='0',key=randomUUID())=>({...headers,'if-match':`"${version}"`,'idempotency-key':key});
 const a=await db.clan.create({data:{name:'Owner clan A',shortCode:'OCA',codeKey:'OCA',enabled:true}}),b=await db.clan.create({data:{name:'Owner clan B',shortCode:'OCB',codeKey:'OCB',enabled:true}});
 assert.equal((await request('GET','launcher/owner/clans',ordinary)).statusCode,403);
 assert.equal((await request('GET','launcher/owner/clans',own)).statusCode,200);
 assert.equal((await request('POST',`clans/${a.id}/applications`,write(ordinary),{message:'x'.repeat(4001)})).statusCode,400);
 const applied=await request('POST',`clans/${a.id}/applications`,write(ordinary),{message:''});assert.equal(applied.statusCode,201);
 const application=applied.json<{id:string;version:string}>();
 assert.equal((await request('GET',`launcher/owner/clans/${a.id}/applications`,ordinary)).statusCode,403);
 const queue=await request('GET',`launcher/owner/clans/${a.id}/applications`,own);assert.equal(queue.statusCode,200);assert.equal(queue.json().items[0].nickname,'ClanApplicant');
 const approve=`launcher/owner/applications/${application.id}/approve`,body={reason:'Owner fixture approval'},key=randomUUID();
 assert.equal((await request('POST',approve,write(ordinary),body)).statusCode,403);
 assert.equal((await request('POST',approve,write(own,'99'),body)).statusCode,412);
 for(let i=0;i<2;i++)assert.equal((await request('POST',approve,write(own,application.version,key),body)).statusCode,201);
 assert.equal(await db.clan_members.count({where:{user_id:player.id,ended_at:null}}),1);
 const membership=await db.clan_members.findFirstOrThrow({where:{user_id:player.id,ended_at:null}});
 assert.equal((await request('POST','me/clan/leave',write(ordinary,membership.version.toString()),{reason:'Fixture leaves'})).statusCode,201);
 const short=await request('POST',`clans/${b.id}/applications`,write(ordinary),{message:'Hi'});assert.equal(short.statusCode,201);
 const rejected=await request('POST',`launcher/owner/applications/${short.json().id}/reject`,write(own,short.json().version),{reason:'Fixture rejection'});assert.equal(rejected.statusCode,201);
 await assert.rejects(db.$executeRaw`UPDATE clan_applications SET decided_by=${player.id}::uuid WHERE id=${short.json().id}::uuid`,
  (error:unknown)=>String(error).includes('23514')&&String(error).includes('Only the active creator'),'Database must still reject ordinary self-review');
 assert.equal(await db.clan_members.count({where:{user_id:player.id,ended_at:null}}),0);
 const self=await request('POST',`clans/${a.id}/applications`,write(own),{message:''});assert.equal(self.statusCode,201);
 const selfHeaders=write(own,self.json().version),selfRoute=`launcher/owner/applications/${self.json().id}/approve`;
 for(let i=0;i<2;i++)assert.equal((await request('POST',selfRoute,selfHeaders,{reason:'Creator accepts own application'})).statusCode,201);
 assert.equal((await db.clan_applications.findUniqueOrThrow({where:{id:self.json().id}})).status,'ACCEPTED');
 let currentMember=await db.clan_members.findFirstOrThrow({where:{user_id:owner.id,ended_at:null}});
 assert.equal((await request('POST','me/clan/leave',write(own,currentMember.version.toString()),{reason:'Test direct owner join'})).statusCode,201);
 const pendingSame=await request('POST',`clans/${b.id}/applications`,write(own),{message:''});assert.equal(pendingSame.statusCode,201);
 const clan=await db.clan.findUniqueOrThrow({where:{id:b.id}}),join=`launcher/owner/clans/${b.id}/join`,joinKey=randomUUID();
 assert.equal((await request('POST',join,write(ordinary,clan.version.toString()),{reason:'Illegal player join'})).statusCode,403);
 const joinHeaders=write(own,clan.version.toString(),joinKey);
 for(let i=0;i<2;i++)assert.equal((await request('POST',join,joinHeaders,{reason:'Owner chooses clan'})).statusCode,201);
 assert.equal((await db.clan_applications.findUniqueOrThrow({where:{id:pendingSame.json().id}})).status,'ACCEPTED');
 assert.equal(await db.clan_members.count({where:{user_id:owner.id,ended_at:null}}),1);
 assert.equal(await db.whitelist.count({where:{user_id:owner.id,kind:'MEMBERSHIP',revoked_at:null}}),1);
 const other=await db.clan.findUniqueOrThrow({where:{id:a.id}});
 assert.equal((await request('POST',`launcher/owner/clans/${a.id}/join`,write(own,other.version.toString()),{reason:'Second membership'})).statusCode,409);
 currentMember=await db.clan_members.findFirstOrThrow({where:{user_id:owner.id,ended_at:null}});
 assert.equal((await request('POST','me/clan/leave',write(own,currentMember.version.toString()),{reason:'Test pending application to different clan'})).statusCode,201);
 const pendingOther=await request('POST',`clans/${a.id}/applications`,write(own),{message:''});assert.equal(pendingOther.statusCode,201);
 const latestB=await db.clan.findUniqueOrThrow({where:{id:b.id}});
 assert.equal((await request('POST',join,write(own,latestB.version.toString()),{reason:'Creator chooses different clan'})).statusCode,201);
 assert.equal((await db.clan_applications.findUniqueOrThrow({where:{id:pendingOther.json().id}})).status,'WITHDRAWN');
 assert.equal(await db.clan_members.count({where:{user_id:owner.id,ended_at:null}}),1);
 await db.user.update({where:{id:owner.id},data:{systemRole:'PLAYER'}});
 assert.equal((await request('GET','launcher/owner/clans',own)).statusCode,403);
 await assert.rejects(db.$executeRaw`UPDATE clan_applications SET decided_by=${owner.id}::uuid WHERE id=${self.json().id}::uuid`,
  (error:unknown)=>String(error).includes('23514')&&String(error).includes('Only the active creator'),'A demoted creator cannot self-review through SQL either');
 await db.user.update({where:{id:owner.id},data:{systemRole:'OWNER'}});
 if(process.env.AOC_OWNER_CLANS_NATIVE_PROBE==='true'){
  const member=await db.clan_members.findFirstOrThrow({where:{user_id:owner.id,ended_at:null}});
  assert.equal((await request('POST','me/clan/leave',write(own,member.version.toString()),{reason:'Native fixture setup'})).statusCode,201);
  assert.equal((await request('POST',`clans/${a.id}/applications`,write(ordinary),{message:''})).statusCode,201);
  await app.listen(39431,'127.0.0.1');
  const {nativeOwnerClans}=await import('../../../scripts/native-owner-clans.mjs');
  await nativeOwnerClans({login:owner.login,password,join_clan:b.id,review_clan:a.id});
  assert.equal((await db.clan_members.findFirstOrThrow({where:{user_id:owner.id,ended_at:null}})).clan_id,b.id);
  assert.equal((await db.clan_members.findFirstOrThrow({where:{user_id:player.id,ended_at:null}})).clan_id,a.id);
 }
 console.log('Launcher owner clans: owner self-review, pending same/different clan direct join, player refusal, role revocation, approve/replay/stale version and one-clan limit: passed');
}
