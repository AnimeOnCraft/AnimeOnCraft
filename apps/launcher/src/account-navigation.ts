import {useEffect,useState} from 'react';
import {accountSnapshot,signedOut,type AccountSnapshot} from './account-api.js';
import {native} from './native.js';

export const accountChangedEvent='animeoncraft-account-changed';
export function canOpenAdmin(account:AccountSnapshot):boolean {
 return account.state==='SIGNED_IN'&&account.profile?.system_role==='OWNER'&&!account.profile.password_change_required;
}
/** Re-read the native session after navigation, authentication, or window focus. */
export function useNavigationAccount(route:string){
 const [account,setAccount]=useState<AccountSnapshot>(signedOut);
 useEffect(()=>{
  if(!native)return;
  let active=true,generation=0;
  const refresh=()=>{const id=++generation;void accountSnapshot().then(value=>{if(active&&id===generation)setAccount(value);}).catch(()=>{if(active&&id===generation)setAccount(signedOut);});};
  refresh();window.addEventListener(accountChangedEvent,refresh);window.addEventListener('focus',refresh);
  return()=>{active=false;window.removeEventListener(accountChangedEvent,refresh);window.removeEventListener('focus',refresh);};
 },[route]);
 return account;
}
