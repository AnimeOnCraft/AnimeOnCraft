import 'reflect-metadata';
import assert from 'node:assert/strict';import {randomUUID,randomBytes} from 'node:crypto';import fs from 'node:fs';
import {Test} from '@nestjs/testing';import {FastifyAdapter,type NestFastifyApplication} from '@nestjs/platform-fastify';
import {PrismaPg} from '@prisma/adapter-pg';import {PrismaClient} from '../src/generated/prisma/client.js';
import {GamePremiumModule} from '../src/premium/premium.controller.js';
import {DatabaseService} from '../src/infrastructure/database.service.js';import {RateLimitStoreService} from '../src/infrastructure/rate-limit-store.service.js';
import {AuthCrypto} from '../src/modules/auth/auth-crypto.js';

// Rift Gate first clears through the real premium route on a disposable database (scripts/test-dungeon-crystals.mjs).
const url=new URL(process.env.DATABASE_URL!);assert.equal(url.hostname,'127.0.0.1');assert.ok(['55439','54329'].includes(url.port));assert.match(url.pathname,/^\/aoc_dungeon_[a-f0-9]{32}$/);
const db=new PrismaClient({adapter:new PrismaPg({connectionString:url.toString()})});
const runtime=new PrismaClient({adapter:new PrismaPg({connectionString:url.toString(),options:'-c role=anime_app'})});let app:NestFastifyApplication|undefined;
const checks:string[]=[];const note=(s:string)=>checks.push(s);
try{
 const module=await Test.createTestingModule({imports:[GamePremiumModule]}).overrideProvider(DatabaseService).useValue({client:runtime}).overrideProvider(RateLimitStoreService).useValue({consume:async()=>{}}).overrideProvider(AuthCrypto).useValue({}).compile();
 app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false,genReqId:()=>randomUUID()}));app.useLogger(['error']);await app.init();await app.getHttpAdapter().getInstance().ready();
 const server=process.env.AOC_GAME_SERVER_ID!,connection=randomUUID(),mc=randomUUID(),now=new Date(),expiry=new Date(Date.now()+3600000);
 await db.game_servers.create({data:{id:server,name:'Isolated dungeon test',maintenance:false}});
 const email='dungeon-'+randomUUID()+'@example.invalid';const user=await db.user.create({data:{email,emailKey:email,passwordHash:'UNUSABLE_TEST_FIXTURE'}});
 await db.game_profiles.create({data:{user_id:user.id,game_name:'DungeonTest',minecraft_uuid:mc}});
 await db.whitelist.create({data:{user_id:user.id,kind:'OWNER',reason:'Isolated fixture'}});
 const session=await db.sessions.create({data:{user_id:user.id,client_kind:'LAUNCHER',auth_epoch:0,absolute_expires_at:expiry,idle_expires_at:expiry}});
 await db.game_tickets.create({data:{token_hash:randomBytes(32).toString('hex'),user_id:user.id,session_id:session.id,server_id:server,connection_id:connection,created_at:new Date(now.getTime()-1000),expires_at:new Date(now.getTime()+30000),consumed_at:now,joined_at:now}});
 const headers={authorization:'Bearer '+process.env.AOC_GAME_SERVER_KEY};const base={server_id:server,connection_id:connection,minecraft_uuid:mc};
 async function clear(dungeon:string,difficulty:string,status=200,extra:object={}){
  const r=await app!.inject({method:'POST',url:'/integration/v1/premium',headers,payload:{...base,action:'DUNGEON',dungeon,difficulty,ref:`dungeon:${dungeon}:${difficulty.toLowerCase()}`,...extra}});
  assert.equal(r.statusCode,status,r.body);return r.json();
 }
 let reply=await clear('sakura_crypt','NORMAL');assert.equal(reply.granted,50);assert.equal(reply.wallet.total,50);
 reply=await clear('sakura_crypt','NORMAL');assert.equal(reply.granted,0);assert.equal(reply.wallet.total,50);note('First Normal clear pays 50 once; a repeat pays nothing');
 for(const [d,n] of [['HARD',50],['NIGHTMARE',50],['MYTHIC',150]] as const){reply=await clear('sakura_crypt',d);assert.equal(reply.granted,n);}
 assert.equal(reply.wallet.total,300);note('Every difficulty of one dungeon: 50+50+50+150 = 300');
 reply=await clear('rift_forge','MYTHIC');assert.equal(reply.granted,150);assert.equal(reply.wallet.total,450);note('Counted separately for each dungeon');
 await Promise.all([clear('ice_spire','HARD'),clear('ice_spire','HARD'),clear('ice_spire','HARD')]);
 assert.equal((await db.$queryRaw<Array<{n:bigint}>>`SELECT count(*) AS n FROM premium_ledger WHERE user_id=${user.id}::uuid AND ref='dungeon:ice_spire:hard'`)[0]!.n,1n);note('Concurrent retries post one ledger row');
 await clear('sakura_crypt','NORMAL',400,{ref:'dungeon:sakura_crypt:mythic'});await clear('sakura_crypt','LEGEND',400);await clear('sakura_crypt','NORMAL',400,{amount:999});note('Mismatched reference, unknown difficulty and amounts rejected');
 const rows=await db.$queryRaw<Array<{kind:string;n:bigint;sum:bigint}>>`SELECT kind,count(*) AS n,sum(delta_earned) AS sum FROM premium_ledger WHERE user_id=${user.id}::uuid GROUP BY kind`;
 assert.deepEqual(rows.map(r=>[r.kind,Number(r.n),Number(r.sum)]),[['DUNGEON',6,500]]);
 const wallet=(await db.$queryRaw<Array<{earned:number;guild_week_earned:number}>>`SELECT earned,guild_week_earned FROM premium_wallets WHERE user_id=${user.id}::uuid`)[0]!;
 assert.equal(wallet.earned,500);assert.equal(wallet.guild_week_earned,0);note('Ledger kind DUNGEON; the weekly guild cap is untouched');
 const result={passed:true,checked_at:new Date().toISOString(),real_postgresql:true,real_nest_http:true,isolated_database:true,checks};
 fs.mkdirSync('F:/AnimeOnCraft/.tools/dungeons-backend',{recursive:true});fs.writeFileSync('F:/AnimeOnCraft/.tools/dungeons-backend/result.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{await app?.close();await runtime.$disconnect();await db.$disconnect();}
