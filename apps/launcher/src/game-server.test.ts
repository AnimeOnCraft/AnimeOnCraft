import {describe,expect,it} from 'vitest';
import {parseServerSnapshot,serverLabel,serverLaunchMessage,playerCount} from './game-server.js';

const available={status:'AVAILABLE',players_online:0,players_max:8,latency_ms:4,checked_at:'2026-10-15T18:39:14Z'};
it('shows unlimited slots without exposing the Java sentinel',()=>{
 const unlimited=parseServerSnapshot({...available,players_online:21,players_max:0});
 expect(playerCount(unlimited)).toBe('21 / ∞');
 expect(playerCount(parseServerSnapshot(available))).toBe('0 / 8');
});
describe('game server status boundary',()=>{
 it('suggests checking VPN on connection errors without claiming it detected VPN',()=>{
  for(const status of ['OFFLINE','INVALID_RESPONSE'] as const){
   expect(serverLaunchMessage({snapshot:{status,players_online:null,players_max:null,latency_ms:null,checked_at:available.checked_at},error:false})).toContain('VPN');
  }
  expect(serverLaunchMessage({snapshot:null,error:true})).toContain('VPN');
  expect(serverLaunchMessage({snapshot:parseServerSnapshot(available),error:false})).not.toContain('VPN');
 });
 it('preserves zero players and permits hidden player counts',()=>{
  expect(parseServerSnapshot(available).players_online).toBe(0);
  expect(parseServerSnapshot({...available,players_online:null,players_max:null}).players_max).toBeNull();
 });
 it('rejects stale offline counts, malformed values and unexpected server content',()=>{
  for(const value of [null,[],{...available,status:'OFFLINE'},{...available,players_online:-1},{...available,players_online:0.5},{...available,players_max:null},{...available,latency_ms:NaN},{...available,checked_at:'invalid'},{...available,motd:'untrusted'},{...available,status:'ONLINE'}])expect(()=>parseServerSnapshot(value)).toThrow('INVALID_RESPONSE');
 });
 it('distinguishes unknown status, failed checks and server availability',()=>{
  expect(serverLabel({snapshot:null,error:false})).toBe('Статус неизвестен');
  expect(serverLabel({snapshot:null,error:true})).toBe('Не удалось проверить');
  expect(serverLabel({snapshot:parseServerSnapshot(available),error:false})).toBe('Доступен');
  expect(serverLaunchMessage({snapshot:parseServerSnapshot({...available,status:'OFFLINE',players_online:null,players_max:null,latency_ms:null}),error:false})).toContain('не отвечает');
 });
});
