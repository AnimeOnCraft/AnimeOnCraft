import 'reflect-metadata';
import {describe,it,expect} from 'vitest';
import {PACKS,pack,rubles,settle,yookassa,type YooKassa,type YooPayment} from '../src/premium/payments.js';

/** A tiny fake transaction: only the queries settle() makes. */
function fakeTx(order:{id:string;user_id:string;crystals:number;amount:number;status:string}|null){
 const calls:string[]=[];
 const tx={
  $queryRaw:async(strings:TemplateStringsArray)=>{const sql=strings.join('?');calls.push(sql);
   if(sql.includes('FROM payment_orders'))return order?[order]:[];
   if(sql.includes('FROM premium_wallets'))return [{earned:0,paid:0,level_rewarded:null,guild_week:'',guild_week_earned:0}];
   if(sql.includes('INSERT INTO premium_ledger'))return [{id:1n}];return [];},
  $executeRaw:async(strings:TemplateStringsArray)=>{calls.push(strings.join('?'));return 1;},
 };
 return {tx:tx as never,calls};
}
const api=(payment:Partial<YooPayment>):YooKassa=>({create:async()=>{throw new Error();},refund:async()=>{throw new Error();},
 get:async id=>({id,status:'succeeded',paid:true,amount:{value:'99.00',currency:'RUB'},metadata:{order_id:'o1'},...payment})});
const order={id:'o1',user_id:'00000000-0000-4000-8000-000000000003',crystals:200,amount:9900,status:'PENDING'};
const pid='2d8f0f4e-000f-5000-9000-1b2c3d4e5f60';
describe('Crystal payments',()=>{
 it('sells only the published packs with exact kopeck prices',()=>{
  expect(PACKS.map(p=>rubles(p.kopecks))).toEqual(['99.00','249.00','499.00','990.00']);
  expect(pack('P200')?.crystals).toBe(200);expect(pack('FREE')).toBeNull();expect(pack({code:'P200'})).toBeNull();
 });
 it('stays disabled without credentials',()=>{
  expect(yookassa({})).toBeNull();expect(yookassa({YOOKASSA_SHOP_ID:'abc',YOOKASSA_SECRET_KEY:'x'.repeat(20)})).toBeNull();
  expect(yookassa({YOOKASSA_SHOP_ID:'123456',YOOKASSA_SECRET_KEY:'test_'+'x'.repeat(20)})).not.toBeNull();
 });
 it('credits a succeeded payment once and only when it matches the order',async()=>{
  const ok=fakeTx(order);expect(await settle(ok.tx,api({}),pid)).toBe('CREDITED');expect(ok.calls.some(c=>c.includes("status='SUCCEEDED'"))).toBe(true);
  expect(await settle(fakeTx({...order,status:'SUCCEEDED'}).tx,api({}),pid)).toBe('IGNORED');
  expect(await settle(fakeTx(order).tx,api({amount:{value:'9.90',currency:'RUB'}}),pid)).toBe('IGNORED');
  expect(await settle(fakeTx(order).tx,api({metadata:{order_id:'other'}}),pid)).toBe('IGNORED');
  expect(await settle(fakeTx(order).tx,api({status:'pending',paid:false}),pid)).toBe('IGNORED');
  expect(await settle(fakeTx(null).tx,api({}),pid)).toBe('IGNORED');
  expect(await settle(fakeTx(order).tx,api({status:'canceled',paid:false}),pid)).toBe('CANCELED');
  await expect(settle(fakeTx(order).tx,api({}),"x' OR 1=1")).rejects.toThrow();
 });
});
