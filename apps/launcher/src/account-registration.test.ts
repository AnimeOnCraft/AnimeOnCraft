import {describe,it,expect,vi} from 'vitest';
import {registerAndSignIn} from './account-registration.js';
import {signedOut,type AccountSnapshot} from './account-api.js';
vi.mock('./native.js',()=>({native:false}));
const input={action:'register' as const,login:'Fixture',email:'fixture@example.test',password:'Only-Test-Password!'};
const registered:AccountSnapshot={...signedOut,notice:'COMPLETED_LOGIN_REQUIRED'};
describe('registration continues into the existing game-name step',()=>{
 it('uses the newly registered credentials once without persisting the login',async()=>{
  const action=vi.fn().mockResolvedValueOnce(registered).mockResolvedValueOnce({...signedOut,state:'SIGNED_IN'});
  const onRegistered=vi.fn();const result=await registerAndSignIn(input,action,()=>true,onRegistered);
  expect(action.mock.calls.map(call=>call[0])).toEqual([input,{action:'login',login:input.login,password:input.password,remember:false}]);
  expect(onRegistered).toHaveBeenCalledWith(registered);expect(result?.state).toBe('SIGNED_IN');
 });
 it('does not try login when registration fails',async()=>{
  const action=vi.fn().mockRejectedValue('RESOURCE_CONFLICT');
  await expect(registerAndSignIn(input,action,()=>true,vi.fn())).rejects.toBe('RESOURCE_CONFLICT');expect(action).toHaveBeenCalledTimes(1);
 });
 it('does not start a login after the user cancels the registration flow',async()=>{
  let current=true;const action=vi.fn().mockImplementation(async()=>{current=false;return registered;});
  const onRegistered=vi.fn();expect(await registerAndSignIn(input,action,()=>current,onRegistered)).toBeNull();
  expect(action).toHaveBeenCalledTimes(1);expect(onRegistered).not.toHaveBeenCalled();
 });
 it('keeps account-created progress when the following login fails',async()=>{
  const action=vi.fn().mockResolvedValueOnce(registered).mockRejectedValueOnce('NETWORK_UNCERTAIN');const onRegistered=vi.fn();
  await expect(registerAndSignIn(input,action,()=>true,onRegistered)).rejects.toBe('NETWORK_UNCERTAIN');
  expect(onRegistered).toHaveBeenCalledWith(registered);expect(action).toHaveBeenCalledTimes(2);
 });
});
