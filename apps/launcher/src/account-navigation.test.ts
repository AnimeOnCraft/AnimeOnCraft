import {describe,it,expect,vi} from 'vitest';
vi.mock('./native.js',()=>({native:false}));
import {canOpenAdmin} from './account-navigation.js';
import {signedOut,type AccountSnapshot,type Profile} from './account-api.js';
import {readRoute} from './routes.js';
const profile:Profile={id:'owner',login:'Y2K',email:'owner@example.test',email_verified:false,password_change_required:false,system_role:'OWNER',session_id:'session',client_kind:'LAUNCHER',clan_management:[]};
describe('owner navigation access',()=>{
 it('uses the authenticated role, not a nickname or clan manager assignment',()=>{
  const owner:AccountSnapshot={...signedOut,state:'SIGNED_IN',profile};
  expect(canOpenAdmin(owner)).toBe(true);
  expect(canOpenAdmin({...owner,profile:{...profile,system_role:'PLAYER',clan_management:[{clan_id:'clan',can_remove_members:true}]}})).toBe(false);
  expect(canOpenAdmin({...owner,profile:{...profile,login:'different-login'}})).toBe(true);
  expect(canOpenAdmin({...owner,profile:{...profile,password_change_required:true}})).toBe(false);
  for(const state of ['SIGNED_OUT','REAUTH_REQUIRED','RESTORING','STORAGE_UNAVAILABLE'] as const)expect(canOpenAdmin({...owner,state})).toBe(false);
 });
 it('recognizes the admin page so its access guard handles direct navigation',()=>{
  expect(readRoute('#admin')).toEqual({page:'admin'});
  expect(readRoute('#unknown')).toEqual({page:'home'});
  expect(readRoute('#settings/account')).toEqual({page:'settings'});
 });
});
