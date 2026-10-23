import {useEffect,useState} from 'react';
import {Button} from '@animeoncraft/ui';
import {clanRoster,accountError,type ClanRoster as Roster} from './account-api.js';
import {useNavigationAccount} from './account-navigation.js';
import {loadClanRoster} from './clan-roster-data.js';

export function ClanRoster({clanId}:{clanId:string}){
 const account=useNavigationAccount(`clan-roster/${clanId}`),signed=account.state==='SIGNED_IN'&&!account.profile?.password_change_required;
 const [data,setData]=useState<Roster|null>(null),[error,setError]=useState(''),[revision,setRevision]=useState(0);
 useEffect(()=>{setData(null);setError('');},[clanId,signed]);
 useEffect(()=>{if(!signed)return;const timer=setInterval(()=>setRevision(v=>v+1),30000);return()=>clearInterval(timer);},[signed]);
 useEffect(()=>{if(!signed)return;let active=true;setError('');void loadClanRoster(clanId,clanRoster).then(value=>{if(active)setData(value);}).catch(e=>{if(active){setData(null);setError(accountError(e));}});return()=>{active=false;};},[clanId,signed,revision]);
 if(!signed)return <section className="clan-roster"><h4>Участники клана</h4><a href="#profile">Войдите в аккаунт, чтобы увидеть состав и онлайн участников</a></section>;
 const members=[...(data?.items??[])].sort((a,b)=>Number(b.online===true)-Number(a.online===true)||(a.nickname??'').localeCompare(b.nickname??'','ru'));
 return <section className="clan-roster" aria-label="Участники выбранного клана"><div className="section-title"><h4>Участники клана{data?` · ${data.items.length}`:''}</h4><Button type="button" onClick={()=>setRevision(v=>v+1)}>Обновить состав</Button></div>
  {error&&<p role="alert">{error}</p>}{!data&&!error&&<p role="status">Загружаем состав…</p>}
  {data&&<><p className="muted">В сети: {members.filter(m=>m.online===true).length} · не в сети: {members.filter(m=>m.online===false).length}{members.some(m=>m.online===null)?' · у части игроков нет свежих данных':''}</p>
   <ul className="clan-roster-list">{members.map((member,index)=><li className="clan-roster-member" key={`${member.nickname??index}-${member.joined_at}`}><div><strong>{member.nickname??'Ник ещё не выбран'}</strong><small>{member.role==='MANAGER'?'Управляющий':'Участник'}</small></div><span className={`clan-presence ${member.online===null?'unknown':member.online?'online':'offline'}`}><i aria-hidden="true"/>{member.online===null?'Нет свежих данных':member.online?'В сети':'Не в сети'}</span></li>)}</ul>
   {!members.length&&<p>В этом клане пока нет участников.</p>}</>}
  <p className="muted">Статусы обновляются каждые 30 секунд. При потере связи показываем отсутствие свежих данных.</p>
 </section>;
}
