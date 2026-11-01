import 'reflect-metadata';
import {describe,it,expect} from 'vitest';
import {giftInput,premiumInput} from '../src/premium/premium.controller.js';
import {CRYSTALS,dungeonRef,guildWeek,levelReward,validDungeon,validRef} from '../src/premium/crystals.js';
const server='00000000-0000-4000-8000-000000000001',base={server_id:server,connection_id:'00000000-0000-4000-8000-000000000002',minecraft_uuid:'00000000-0000-4000-8000-000000000003',action:'READ'};
describe('Premium crystals',()=>{
 it('lets the game server report events but never amounts or owners',()=>{
  expect(premiumInput(base,server).action).toBe('READ');
  expect(premiumInput({...base,action:'LEVEL',level:12},server).level).toBe(12);
  expect(premiumInput({...base,action:'GUILD',rank:'C',ref:'guild:2026-10-25:herbs:1'},server).rank).toBe('C');
  for(const extra of [{amount:500},{user_id:server},{server_id:'other'},{level:3},{action:'PURCHASE'},{action:'ADMIN'}])expect(()=>premiumInput({...base,...extra},server)).toThrow();
  for(const level of [0,101,1.5,'7',null])expect(()=>premiumInput({...base,action:'LEVEL',level},server)).toThrow();
  for(const bad of [{rank:'Z',ref:'guild:x'},{rank:'A',ref:'level:5'},{rank:'A',ref:'guild:../x'},{rank:'A'}])expect(()=>premiumInput({...base,action:'GUILD',...bad},server)).toThrow();
 });
 it('accepts a dungeon first clear only with its own reference and never an amount',()=>{
  const clear={...base,action:'DUNGEON',dungeon:'sakura_crypt',difficulty:'MYTHIC',ref:'dungeon:sakura_crypt:mythic'};
  expect(premiumInput(clear,server).dungeon).toBe('sakura_crypt');
  for(const bad of [{difficulty:'EASY'},{dungeon:'../x'},{dungeon:'X'},{ref:'dungeon:sakura_crypt:hard'},{ref:'dungeon:sakura_crypt:mythic:2'},{amount:150},{rank:'A'}])
   expect(()=>premiumInput({...clear,...bad},server)).toThrow();
  expect(()=>premiumInput({...base,action:'DUNGEON',dungeon:'sakura_crypt',difficulty:'HARD'},server)).toThrow();
  expect(dungeonRef('sakura_crypt','NIGHTMARE')).toBe('dungeon:sakura_crypt:nightmare');
  expect(validDungeon('sakura_crypt','NORMAL')).toBe(true);expect(validDungeon('sakura_crypt','normal')).toBe(false);
  expect(Object.values(CRYSTALS.dungeonFirstClear).reduce((a,b)=>a+b,0)*4).toBe(1200);
  expect(CRYSTALS.dungeonFirstClear).toEqual({NORMAL:50,HARD:50,NIGHTMARE:50,MYTHIC:150});
 });
 it('pays small fixed amounts per level',()=>{
  expect([1,2,20,21,50,51,100,101].map(levelReward)).toEqual([0,20,20,40,40,60,60,0]);
  let total=0;for(let n=2;n<=100;n++)total+=levelReward(n);expect(total).toBe(19*20+30*40+50*60);
 });
 it('counts the guild weekly cap from Monday in Yekaterinburg',()=>{
  expect(guildWeek(new Date('2026-10-25T18:59:00Z'))).toBe('2026-10-19');
  expect(guildWeek(new Date('2026-10-25T19:00:00Z'))).toBe('2026-10-26');
  expect(guildWeek(new Date('2026-10-28T12:00:00Z'))).toBe('2026-10-26');
 });
 it('accepts an operator gift only with a bounded amount and a gift id',()=>{
  const gift={server_id:server,connection_id:base.connection_id,minecraft_uuid:base.minecraft_uuid,amount:500,gift_id:'00000000-0000-4000-8000-000000000009'};
  expect(giftInput(gift,server).amount).toBe(500);
  for(const bad of [{amount:0},{amount:5001},{amount:1.5},{amount:'500'},{gift_id:'x'},{server_id:'other'},{user_id:server},{action:'READ'}])expect(()=>giftInput({...gift,...bad},server)).toThrow();
 });
 it('accepts only plain references',()=>{
  expect(validRef('guild:2026-10-25:quest:3')).toBe(true);
  for(const ref of ['','a b',"x'--",'x'.repeat(121),42])expect(validRef(ref)).toBe(false);
 });
});
