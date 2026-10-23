import {accountAction,type AccountAction,type AccountSnapshot} from './account-api.js';

export async function registerAndSignIn(
  input:Extract<AccountAction,{action:'register'}>,
  action:(input:AccountAction)=>Promise<AccountSnapshot>=accountAction,
  current:()=>boolean=()=>true,
  onRegistered:(snapshot:AccountSnapshot)=>void=()=>{},
):Promise<AccountSnapshot|null>{
  const registered=await action(input);
  if(!current())return null;
  onRegistered(registered);
  if(!current())return null;
  return action({action:'login',login:input.login,password:input.password,remember:false});
}
