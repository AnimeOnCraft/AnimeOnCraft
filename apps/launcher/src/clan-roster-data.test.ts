import {describe,it,expect} from 'vitest';
import {loadClanRoster} from './clan-roster-data.js';
const member=(nickname:string,online:boolean|null)=>({nickname,online,role:'MEMBER' as const,joined_at:'2026-10-19T00:00:00Z',online_updated_at:null});
describe('selected clan roster',()=>{
 it('loads all pages and preserves online, offline and unknown statuses',async()=>{
  const calls:unknown[]=[];const result=await loadClanRoster('other-clan',async(id,cursor)=>{calls.push([id,cursor]);return cursor?{items:[member('Offline',false),member('Unknown',null)],next_cursor:null}:{items:[member('Online',true)],next_cursor:'page-two'};});
  expect(calls).toEqual([['other-clan',undefined],['other-clan','page-two']]);expect(result.items.map(m=>m.online)).toEqual([true,false,null]);expect(result.next_cursor).toBeNull();
 });
 it('rejects a repeating cursor instead of looping',async()=>{await expect(loadClanRoster('a',async()=>({items:[],next_cursor:'repeat'}))).rejects.toThrow('INVALID_RESPONSE');});
 it('does not mix requests for different clans',async()=>{const read=async(id:string)=>({items:[member(id,false)],next_cursor:null});expect((await loadClanRoster('SCAM',read)).items[0]?.nickname).toBe('SCAM');expect((await loadClanRoster('NYA',read)).items[0]?.nickname).toBe('NYA');});
});
