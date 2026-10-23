import type {ClanRoster} from './account-api.js';
type ReadPage=(clanId:string,cursor?:string)=>Promise<ClanRoster>;
/** Load the entire chosen clan, including clans larger than one API page. */
export async function loadClanRoster(clanId:string,read:ReadPage):Promise<ClanRoster>{
 const items:ClanRoster['items']=[],seen=new Set<string>();let cursor:string|undefined;
 do{
  const page=await read(clanId,cursor);items.push(...page.items);
  cursor=page.next_cursor??undefined;
  if(cursor){if(seen.has(cursor)||seen.size>=500)throw new Error('INVALID_RESPONSE');seen.add(cursor);}
 }while(cursor);
 return {items,next_cursor:null};
}
