import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import type {NestFastifyApplication} from '@nestjs/platform-fastify';
import type {PrismaClient} from '../src/generated/prisma/client.js';
import {offlinePlayerUuid} from '../src/modules/auth/game-profile.service.js';
import type {AuthService} from '../src/modules/auth/auth.service.js';

export async function betaCapeCases(app:NestFastifyApplication,db:PrismaClient,auth:AuthService,owner:string,passwordHash:string){
 const previous={id:process.env.AOC_GAME_SERVER_ID,key:process.env.AOC_GAME_SERVER_KEY};
 const server=await db.game_servers.create({data:{name:'Beta cape fixture',maintenance:false}});
 const key=randomBytes(32).toString('hex');process.env.AOC_GAME_SERVER_ID=server.id;process.env.AOC_GAME_SERVER_KEY=key;
 const post=(route:string,payload:object,headers:Record<string,string>)=>app.inject({method:'POST',url:`/api/v1/${route}`,headers,payload,remoteAddress:'127.0.3.1'});
 async function player(login:string){
  const user=await db.user.create({data:{login,email:`${login}@example.test`,emailKey:`${login.toLowerCase()}@example.test`,passwordHash}});
  const pair=await auth.transaction(tx=>auth.newSession(tx,user,'LAUNCHER',false,randomUUID()));
  const headers={'x-aoc-client':'launcher',authorization:`Bearer ${pair.access_token}`};
  const profile=await post('auth/me/game-profile',{game_name:login},headers);assert.equal(profile.statusCode,200);
  await db.whitelist.create({data:{user_id:user.id,kind:'OWNER',granted_by:owner,reason:'Beta cape fixture'}});
  return {user,headers,uuid:profile.json().profile.minecraft_uuid as string};
 }
 async function admit(p:Awaited<ReturnType<typeof player>>){
  const issued=await post('game/tickets',{},p.headers);assert.equal(issued.statusCode,200);
  const connection=randomUUID();const redeem=await post('game/admission/redeem',{ticket:issued.json().ticket,connection_id:connection,game_name:p.user.login,minecraft_uuid:p.uuid},{authorization:`Bearer ${key}`});
  assert.equal(redeem.statusCode,200);
  return {connection_id:connection,minecraft_uuid:p.uuid};
 }
 const campaign=async(start:Date|null)=>db.beta_campaign.update({where:{id:'beta'},data:{starts_at:start,ends_at:start?new Date(start.getTime()+7*86400000):null}});
 try{
  const p=await player('CapeFirst');
  assert.equal((await post('auth/me/capes',{cape_id:'beta'},p.headers)).statusCode,403);
  const noWindow=await admit(p);
  assert.equal(await db.player_capes.count({where:{user_id:p.user.id}}),0,'Redeeming a ticket must not award a cape');
  assert.equal((await post('game/admission/joined',noWindow,p.headers)).statusCode,401);
  assert.equal((await post('game/admission/joined',{...noWindow,connection_id:randomUUID()},{authorization:`Bearer ${key}`})).statusCode,403);
  const joined=()=>post('game/admission/joined',noWindow,{authorization:`Bearer ${key}`});
  assert.equal((await joined()).statusCode,200);assert.equal(await db.player_capes.count({where:{user_id:p.user.id}}),0,'Disabled beta grants nothing');
  await campaign(new Date());
  assert.equal((await joined()).json().awarded,false,'Opening beta later must not turn an older join into a qualifying join');
  const valid=await admit(p);
  assert.equal(await db.player_capes.count({where:{user_id:p.user.id}}),0,'Valid configuration alone is not an actual world join');
  assert.equal((await post('game/admission/joined',{...valid,minecraft_uuid:offlinePlayerUuid('CapeWrong')},{authorization:`Bearer ${key}`})).statusCode,403);
  const replies=await Promise.all([post('game/admission/joined',valid,{authorization:`Bearer ${key}`}),post('game/admission/joined',valid,{authorization:`Bearer ${key}`})]);
  assert.deepEqual(replies.map(r=>r.statusCode),[200,200]);assert.equal(replies.filter(r=>r.json().awarded).length,1);
  assert.equal(await db.player_capes.count({where:{user_id:p.user.id}}),1);
  assert.equal((await post('auth/me/capes',{cape_id:null},p.headers)).json().equipped,null);
  await campaign(new Date(Date.now()-8*86400000));
  const owned=await app.inject({url:'/api/v1/auth/me/capes',headers:p.headers});assert.equal(owned.json().capes.length,1);assert.equal(owned.json().beta.active,false);
  assert.equal((await post('auth/me/capes',{cape_id:'beta'},p.headers)).json().equipped,'beta','Owned capes remain usable after beta');
  assert.equal((await post('auth/me/capes',{cape_id:'beta',user_id:owner},p.headers)).statusCode,400);
  await db.player_capes.create({data:{user_id:p.user.id,cape_id:'ayaa-cobblestone'}});
  const wardrobeRoute='auth/me/capes/wardrobe';
  const wardrobe=await app.inject({url:'/api/v1/'+wardrobeRoute,headers:p.headers});assert.equal(wardrobe.statusCode,200);
  assert.equal(wardrobe.json().capes.length,2);assert.ok(wardrobe.json().capes.find((c:{id:string})=>c.id==='ayaa-cobblestone').preview_png_base64);
  const legacy=await app.inject({url:'/api/v1/auth/me/capes',headers:p.headers});assert.equal(legacy.json().capes.length,1);assert.deepEqual(Object.keys(legacy.json().capes[0]).sort(),['awarded_at','id','name']);
  assert.equal((await post(wardrobeRoute,{cape_id:'ayaa-cobblestone'},p.headers)).json().equipped,'ayaa-cobblestone');
  assert.equal((await app.inject({url:'/api/v1/auth/me/capes',headers:p.headers})).json().equipped,null,'Legacy clients cannot receive unknown cape IDs');
  assert.equal((await post(wardrobeRoute,{cape_id:'unknown-cape'},p.headers)).statusCode,403);
  assert.equal((await post(wardrobeRoute,{cape_id:'../beta'},p.headers)).statusCode,400);
  assert.equal((await post(wardrobeRoute,{cape_id:'beta',user_id:owner},p.headers)).statusCode,400);
  assert.equal((await post(wardrobeRoute,{cape_id:null},p.headers)).json().equipped,null);
  const late=await player('CapeLate');const lateJoin=await admit(late);
  assert.equal((await post(wardrobeRoute,{cape_id:'ayaa-cobblestone'},late.headers)).statusCode,403,'Another player cannot wear a personal cape');
  assert.equal((await post('game/admission/joined',lateJoin,{authorization:`Bearer ${key}`})).json().awarded,false);
  assert.equal(await db.player_capes.count({where:{user_id:late.user.id}}),0,'A join after the week cannot award Beta');
  console.log('Beta capes: actual-join receipt only, no early/late/unowned/client grants, concurrent idempotence, selection/removal and permanent ownership after beta: passed');
 }finally{
  await campaign(null);
  if(previous.id===undefined)delete process.env.AOC_GAME_SERVER_ID;else process.env.AOC_GAME_SERVER_ID=previous.id;
  if(previous.key===undefined)delete process.env.AOC_GAME_SERVER_KEY;else process.env.AOC_GAME_SERVER_KEY=previous.key;
 }
}
