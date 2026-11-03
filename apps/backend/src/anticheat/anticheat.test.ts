import { afterEach, describe, expect, it, vi } from 'vitest';
import { notifyOwner, reportInput } from './anticheat.js';
const server='11111111-1111-4111-8111-111111111111';
const base=()=>({server_id:server,report_id:'22222222-2222-4222-8222-222222222222',minecraft_uuid:'33333333-3333-4333-8333-333333333333',player_name:'Steve_1',check:'SPEED',level:'ALERT',score:60,detail:'Скорость 14.2 бл/с на земле (норма до 9)',dimension:'minecraft:overworld',x:10,y:64,z:-20,occurred_at:new Date().toISOString()});
describe('anticheat report input',()=>{
 it('accepts a valid report',()=>{expect(reportInput(base(),server).check).toBe('SPEED');});
 it('rejects another server id',()=>{expect(()=>reportInput(base(),'44444444-4444-4444-8444-444444444444')).toThrow();});
 it('rejects unknown fields and bad values',()=>{
  expect(()=>reportInput({...base(),extra:1},server)).toThrow();
  expect(()=>reportInput({...base(),level:'BAN'},server)).toThrow();
  expect(()=>reportInput({...base(),score:5000},server)).toThrow();
  expect(()=>reportInput({...base(),player_name:'bad name'},server)).toThrow();
  expect(()=>reportInput({...base(),detail:'a\u0000b'},server)).toThrow();
  expect(()=>reportInput({...base(),occurred_at:'2000-01-01T00:00:00Z'},server)).toThrow();
 });
 it('allows a report without position',()=>{const rest:Record<string,unknown>=base();for(const key of ['dimension','x','y','z'])delete rest[key];expect(reportInput(rest,server).x).toBeUndefined();});
});
describe('owner notification',()=>{
 afterEach(()=>{delete process.env.AOC_ALERT_TELEGRAM_TOKEN;delete process.env.AOC_ALERT_TELEGRAM_CHAT;});
 it('is off without configuration',async()=>{const send=vi.fn();expect(await notifyOwner(reportInput(base(),server),send)).toBe(false);expect(send).not.toHaveBeenCalled();});
 it('skips WATCH and sends ALERT',async()=>{
  process.env.AOC_ALERT_TELEGRAM_TOKEN='123456789:'+'A'.repeat(35);process.env.AOC_ALERT_TELEGRAM_CHAT='987654321';
  const send=vi.fn(async()=>new Response('{}',{status:200}));
  expect(await notifyOwner({...reportInput(base(),server),level:'WATCH'},send)).toBe(false);
  expect(await notifyOwner(reportInput(base(),server),send)).toBe(true);
  expect(send).toHaveBeenCalledTimes(1);
  expect(String((send.mock.calls[0] as unknown[])[0])).toContain('api.telegram.org');
 });
 it('swallows network failures',async()=>{
  process.env.AOC_ALERT_TELEGRAM_TOKEN='123456789:'+'A'.repeat(35);process.env.AOC_ALERT_TELEGRAM_CHAT='987654321';
  expect(await notifyOwner(reportInput(base(),server),vi.fn(async()=>{throw new Error('offline');}))).toBe(false);
 });
});
