import 'reflect-metadata';
import {describe,it,expect} from 'vitest';
import {BANNER,WORST_CASE_ROLLS,chance5,pull,type Rng} from '../src/premium/gacha.js';
import {gachaInput} from '../src/premium/premium.controller.js';
function seeded(seed:number):Rng{let s=seed>>>0;return bound=>{s=(s*1664525+1013904223)>>>0;return Math.floor(s/2**32*bound);};}
/** Rolls until the set is complete; fragments are exchanged for rolls as soon as possible. Returns all rolls and the ones paid with crystals. */
function collect(rng:Rng){const owned=new Set<string>(),pity={pity5:0,pity4:0,drought:0};let rolls=0,paid=0,fragments=0;
 while(owned.size<BANNER.items.length&&rolls<10000){if(fragments>=BANNER.fragmentRoll)fragments-=BANNER.fragmentRoll;else paid++;fragments+=pull(pity,owned,rng).fragments;rolls++;}
 return {rolls,paid};}
const server='00000000-0000-4000-8000-000000000001',base={server_id:server,connection_id:'00000000-0000-4000-8000-000000000002',minecraft_uuid:'00000000-0000-4000-8000-000000000003',action:'ROLL',count:10,request_id:'00000000-0000-4000-8000-0000000000aa'};
describe('Gacha banner',()=>{
 it('raises the 5-star chance from the soft pity and guarantees it at the hard pity',()=>{
  expect(chance5(0)).toBe(BANNER.rate5);expect(chance5(BANNER.soft5-2)).toBe(BANNER.rate5);expect(chance5(BANNER.soft5)).toBe(BANNER.rate5+BANNER.step5);expect(chance5(BANNER.hard5-1)).toBe(1000);
 });
 it('repeats only 3/4-star items, pays fragments for them and gives a 4-star or better at least every 10 rolls',()=>{
  const rng=seeded(7),owned=new Set<string>(),pity={pity5:0,pity4:0,drought:0};let since=0,dupes=0;
  for(let i=0;i<300;i++){const before=owned.size;const r=pull(pity,owned,rng);
   if(r.item&&!r.duplicate)expect(owned.size).toBe(before+1);
   if(r.duplicate){dupes++;expect(owned.size).toBe(before);expect(r.fragments).toBe(BANNER.fragments[r.item!.tier]);}else expect(r.fragments).toBe(0);
   if(r.duplicate&&r.item!.tier===5)expect(BANNER.items.filter(x=>x.tier===5).every(x=>owned.has(x.code))).toBe(true);
   since=r.rolled>=4?0:since+1;expect(since).toBeLessThan(BANNER.hard4);}
  expect(dupes).toBeGreaterThan(0);
 });
 it('gives something new after a drought of repeats',()=>{
  const owned=new Set(BANNER.items.filter(i=>i.code!=='HANAMI').map(i=>i.code)),pity={pity5:0,pity4:0,drought:BANNER.drought-1};
  const r=pull(pity,owned,bound=>bound-1);expect(r.item?.code).toBe('HANAMI');expect(r.duplicate).toBe(false);expect(pity.drought).toBe(0);
 });
 it('completes the whole set within the published worst case and costs 5–7k rubles on average',()=>{
  expect(collect(bound=>bound-1).rolls).toBeLessThanOrEqual(WORST_CASE_ROLLS);
  let worst=0,sum=0;for(let seed=1;seed<=20000;seed++){const n=collect(seeded(seed));worst=Math.max(worst,n.rolls);sum+=n.paid;}
  // Owner's target: the whole set costs about 5–7k rubles on average (200 crystals = 99 rubles, 100 per roll), fragments exchanged for rolls.
  const rubles=sum/20000*BANNER.cost*99/200;
  expect(worst).toBeLessThanOrEqual(WORST_CASE_ROLLS);expect(rubles).toBeGreaterThan(5000);expect(rubles).toBeLessThan(7000);
  console.log('GACHA_SET_ROLLS paid_average='+(sum/20000).toFixed(1)+' rubles~'+Math.round(rubles)+' worst_seen='+worst+' published='+WORST_CASE_ROLLS);
 });
 it('keeps published odds close to the configuration',()=>{
  const rng=seeded(42),counts={0:0,3:0,4:0,5:0} as Record<number,number>;
  for(let i=0;i<200000;i++){const pity={pity5:0,pity4:0,drought:0};counts[pull(pity,new Set(),rng).rolled]!++;}
  expect(counts[5]!/200000).toBeCloseTo(BANNER.rate5/1000,2);expect(counts[4]!/200000).toBeCloseTo(BANNER.rate4/1000,2);expect(counts[3]!/200000).toBeCloseTo(BANNER.rate3/1000,2);
 });
 it('accepts only bounded roll requests from the game server',()=>{
  expect(gachaInput(base,server).count).toBe(10);
  expect(gachaInput({...base,source:'FRAGMENTS'},server).source).toBe('FRAGMENTS');
  expect(()=>gachaInput({...base,source:'COINS'},server)).toThrow();
  expect(gachaInput({server_id:server,connection_id:base.connection_id,minecraft_uuid:base.minecraft_uuid,action:'READ'},server).action).toBe('READ');
  for(const bad of [{count:5},{count:100},{request_id:'x'},{item:'SAKURA_WINGS'},{tier:5},{action:'BUY'},{server_id:'other'}])expect(()=>gachaInput({...base,...bad},server)).toThrow();
 });
});
describe('Gacha back slot',()=>{
 it('accepts only banner accessories that are worn on the back',async()=>{
  const {validBack}=await import('../src/wardrobe/back-equipment.js');
  expect(validBack({kind:'GACHA',code:'SAKURA_WINGS'})).toBe(true);
  for(const code of ['KITSUNE_SAKURA','RIFT_MANTLE','','sakura_wings'])expect(validBack({kind:'GACHA',code})).toBe(false);
  expect(validBack({kind:'BETA',code:'RIFT_MANTLE'})).toBe(true);
 });
});
