import {describe,it,expect,vi} from 'vitest';
import {createElement} from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
vi.mock('./native.js',()=>({native:false}));
const invoke=vi.fn();vi.mock('@tauri-apps/api/core',()=>({invoke:(...args:unknown[])=>invoke(...args)}));
import {accountAction,accountSnapshot,accountError,playerRead,playerPrepare,playerSubmit,securityAction,securitySessions} from './account-api.js';
import {AccountPanel} from './account-panel.js';
describe('account UI boundary',()=>{
 it('network errors include a VPN troubleshooting hint, validation errors do not',()=>{
  expect(accountError('SERVICE_UNAVAILABLE')).toContain('VPN');
  expect(accountError('NETWORK_UNCERTAIN')).toContain('VPN');
  expect(accountError('VALIDATION_ERROR')).not.toContain('VPN');
 });
 it('browser preview never sends account or player IPC',async()=>{
  await expect(accountSnapshot()).rejects.toThrow('WINDOWS_REQUIRED');
  await expect(accountAction({action:'login',login:'fixture',password:'fixture',remember:true})).rejects.toThrow('WINDOWS_REQUIRED');
  await expect(playerRead('access')).rejects.toThrow('WINDOWS_REQUIRED');
  await expect(playerPrepare({action:'leave',version:'0',reason:'fixture'})).rejects.toThrow('WINDOWS_REQUIRED');
  await expect(playerSubmit('fixture')).rejects.toThrow('WINDOWS_REQUIRED');expect(invoke).not.toHaveBeenCalled();
  await expect(securitySessions()).rejects.toThrow('WINDOWS_REQUIRED');
  await expect(securityAction({action:'enroll',password:'fixture'})).rejects.toThrow('WINDOWS_REQUIRED');expect(invoke).not.toHaveBeenCalled();
 });
 it('raw errors cannot leak tokens or server details into UI',()=>{
  const secret='ar_'+'x'.repeat(43);expect(accountError(secret)).not.toContain(secret);expect(accountError(new Error(secret))).not.toContain(secret);
  expect(accountError('NETWORK_UNCERTAIN')).toContain('неизвестен');expect(accountError('STORAGE_UNAVAILABLE')).toContain('остановлен');
  expect(accountError('ACCOUNT_SERVICE_UNAVAILABLE')).toContain('сервисом аккаунтов');
  expect(accountError('NOT_FOUND')).toContain('Обновите');
 });
 it('browser profile clearly directs users to the Windows application',()=>{
  const html=renderToStaticMarkup(createElement(AccountPanel));expect(html).toContain('Windows-приложении');expect(html).not.toContain('type="password"');expect(html).not.toContain('Время в игре: 0');
 });
});
