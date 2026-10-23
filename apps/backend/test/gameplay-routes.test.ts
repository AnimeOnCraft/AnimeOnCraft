import 'reflect-metadata';
import {Test} from '@nestjs/testing';
import {FastifyAdapter,type NestFastifyApplication} from '@nestjs/platform-fastify';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {IntegrationAppModule} from '../src/integration-app.module.js';
import {DatabaseService} from '../src/infrastructure/database.service.js';
import {RateLimitStoreService} from '../src/infrastructure/rate-limit-store.service.js';

describe('gameplay integration HTTP routes',()=>{
 let app:NestFastifyApplication;
 const key='a'.repeat(64),server='11111111-1111-4111-8111-111111111111';
 const execute=vi.fn(),query=vi.fn(),transaction=vi.fn();
 beforeEach(async()=>{
  vi.stubEnv('AOC_GAME_SERVER_KEY',key);vi.stubEnv('AOC_GAME_SERVER_ID',server);
  query.mockReset().mockImplementation(async (sql)=>sql.join('').includes('SELECT id FROM clans')?[{id:'33333333-3333-4333-8333-333333333333'}]:sql.join('').includes('RETURNING (statistics')?[{kills:1n}]:sql.join('').includes('SELECT server_id')?[{server_id:server,clan_id:'33333333-3333-4333-8333-333333333333',kind:'BOSS_KILL'}]:[]);
  execute.mockReset().mockResolvedValue(1);transaction.mockReset().mockImplementation(async work=>work({$executeRaw:execute,$queryRaw:query}));
  const module=await Test.createTestingModule({imports:[IntegrationAppModule]})
   .overrideProvider(DatabaseService).useValue({client:{$executeRaw:execute,$queryRaw:query,$transaction:transaction}})
   .overrideProvider(RateLimitStoreService).useValue({}).compile();
  app=module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({logger:false}));
  await app.init();await app.getHttpAdapter().getInstance().ready();
 });
 afterEach(async()=>{await app.close();vi.unstubAllEnvs();});
 it('routes heartbeat and control at the paths used by Minecraft and rejects missing credentials',async()=>{
  for(const [method,url] of [['POST','/integration/v1/servers/heartbeat'],['POST','/integration/v1/events'],['POST','/integration/v1/competitions/start'],['POST','/integration/v1/competitions/cancel'],['POST','/integration/v1/wars/schedule'],['POST','/integration/v1/wars/cancel'],['POST','/integration/v1/territories/capture'],['GET','/integration/v1/territories/control']] as const){
   const response=await app.inject({method,url,...method==='POST'?{payload:{server_id:server}}:{}});
   expect(response.statusCode).toBe(401);
  }
  expect((await app.inject({method:'GET',url:'/integration/v1/health/live'})).statusCode).toBe(200);
  for(const url of ['/integration/v1/competitions/world-boss/progress','/integration/v1/competitions/world-boss/result'])expect((await app.inject({method:'POST',url,payload:{server_id:server}})).statusCode).toBe(401);
  expect((await app.inject({method:'GET',url:'/integration/v1/competitions/world-boss'})).statusCode).toBe(401);
 });
 const event={server_id:server,event_id:'22222222-2222-4222-8222-222222222222',clan_id:'33333333-3333-4333-8333-333333333333',kind:'BOSS_KILL',xp_delta:100,reputation_delta:5};
 it('writes the event and progression inside one transaction',async()=>{
  const response=await app.inject({method:'POST',url:'/integration/v1/events',headers:{authorization:`Bearer ${key}`},payload:event});
  expect(response.statusCode).toBe(200);expect(response.json().duplicate).toBe(false);
  expect(transaction).toHaveBeenCalledOnce();expect(execute).toHaveBeenCalledTimes(2);
 });
 it('rejects caller-defined rewards and unknown event kinds',async()=>{
  for(const patch of [{xp_delta:1000},{kind:'FREE_XP'},{occurred_at:'invalid'},{clan_code:'NYA'}]){
   const response=await app.inject({method:'POST',url:'/integration/v1/events',headers:{authorization:`Bearer ${key}`},payload:{...event,...patch}});
   expect(response.statusCode).toBe(400);
  }
  expect(transaction).not.toHaveBeenCalled();
 });
 it('does not reward an event a second time',async()=>{
  execute.mockResolvedValueOnce(1).mockResolvedValueOnce(0);
  const response=await app.inject({method:'POST',url:'/integration/v1/events',headers:{authorization:`Bearer ${key}`},payload:event});
  expect(response.json().duplicate).toBe(true);expect(execute).toHaveBeenCalledTimes(2);
 });
 it('does not acknowledge an event when the progression write fails',async()=>{
  query.mockResolvedValueOnce([{id:event.clan_id}]).mockRejectedValueOnce(Error('database failure'));
  const response=await app.inject({method:'POST',url:'/integration/v1/events',headers:{authorization:`Bearer ${key}`},payload:event});
  expect(response.statusCode).toBe(400);expect(transaction).toHaveBeenCalledOnce();
 });
});
