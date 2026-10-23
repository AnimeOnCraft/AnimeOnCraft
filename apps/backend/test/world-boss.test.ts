import {describe,expect,it} from 'vitest';
import {bossRewardShares,validateBossDamage} from '../src/world-boss.js';
describe('world boss contribution rewards',()=>{
 it('distributes the entire pools by damage, excludes contributions below five percent',()=>{
  const result=bossRewardShares(validateBossDamage([{clan_code:'SCAM',damage_milli:750},{clan_code:'NYA',damage_milli:250},{clan_code:'YAKUZA',damage_milli:1}]));
  expect(result.find(row=>row.clan_code==='SCAM')).toMatchObject({xp:1500,reputation:45});
  expect(result.find(row=>row.clan_code==='NYA')).toMatchObject({xp:500,reputation:15});
  expect(result.some(row=>row.clan_code==='YAKUZA')).toBe(false);
 });
 it('keeps rounding deterministic and never creates extra XP',()=>{
  const result=bossRewardShares(validateBossDamage(['ZXC','NYA','SCAM'].map(clan_code=>({clan_code,damage_milli:1000}))));
  expect(result.reduce((n,r)=>n+r.xp,0)).toBe(2000);expect(result.reduce((n,r)=>n+r.reputation,0)).toBe(60);
  expect(bossRewardShares([{clan_code:'SCAM',damage_milli:0}])).toEqual([]);
 });
 // Unknown but well-formed codes (e.g. 'ADMIN') are rejected by the clans table in writeScores, not by a fixed list.
 it('rejects malformed clan codes, negative/fractional/duplicate damage',()=>{
  for(const value of [[{clan_code:'admin',damage_milli:5}],[{clan_code:'X',damage_milli:5}],[{clan_code:42,damage_milli:5}],[{clan_code:'NYA',damage_milli:-1}],[{clan_code:'NYA',damage_milli:0.5}],[{clan_code:'NYA',damage_milli:1},{clan_code:'NYA',damage_milli:2}]])expect(()=>validateBossDamage(value)).toThrow();
 });
});
