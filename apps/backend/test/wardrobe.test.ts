import 'reflect-metadata';
import {describe,it,expect} from 'vitest';
import {wardrobeInput} from '../src/wardrobe/wardrobe.controller.js';
import {validBack} from '../src/wardrobe/back-equipment.js';
const server='00000000-0000-4000-8000-000000000001',base={server_id:server,connection_id:'00000000-0000-4000-8000-000000000002',minecraft_uuid:'00000000-0000-4000-8000-000000000003',action:'READ'};
describe('Private game wardrobe input',()=>{
 it('accepts bounded choices and rejects client-supplied owners',()=>{
  expect(wardrobeInput(base,server).action).toBe('READ');
  expect(wardrobeInput({...base,action:'BACK',kind:'CAPE',code:'beta',expected_revision:'9'},server).code).toBe('beta');
  for(const extra of [{user_id:server},{player:server},{server_id:'other'},{connection_id:[]},{minecraft_uuid:42},{code:'beta'}])expect(()=>wardrobeInput({...base,...extra},server)).toThrow();
 });
 it('does not accept arbitrary accessories or ambiguous revisions',()=>{
  for(const revision of [-1,0,'-1','01','1.0','NaN','1000000000000000000'])expect(()=>wardrobeInput({...base,action:'BACK',kind:'BETA',code:'RIFT_MANTLE',expected_revision:revision},server)).toThrow();
  expect(validBack({kind:'BETA',code:'FOX_EARS'})).toBe(false);
  expect(validBack({kind:'CAPE',code:'../texture'})).toBe(false);
  expect(validBack({kind:'NONE',code:'beta'})).toBe(false);
 });
});
