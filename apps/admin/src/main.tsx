import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ClanDirectory, ProductShell } from '@animeoncraft/ui';
import { ClanManagement } from './clan-management.js';
import { adminApi } from './admin-api.js';
import { LoginPanel, SecurityPanel, type Profile } from './auth-panel.js';
import { AuditPanel, NewsPanel, OverviewPanel, PlayersPanel, ServersPanel } from './administration-panels.js';
import '@animeoncraft/ui/styles.css';
import './admin.css';
import { MobileOwnerApp } from './mobile-owner.js';

function App() {
  const [profile,setProfile]=useState<Profile|null>(null);const [tab,setTab]=useState('overview');const [checking,setChecking]=useState(location.protocol==='https:');
  useEffect(()=>{let active=true;const expired=()=>{adminApi.clear();setProfile(null);};window.addEventListener('aoc-auth-expired',expired);
    if(location.protocol==='https:')void (async()=>{try{let value:Profile;try{value=await adminApi.call<Profile>('auth/me');}catch{await adminApi.renew();value=await adminApi.call<Profile>('auth/me');}if(active)setProfile(value);}catch{if(active)setProfile(null);}finally{if(active)setChecking(false);}})();
    return()=>{active=false;window.removeEventListener('aoc-auth-expired',expired);};},[]);
  const owner=profile?.system_role==='OWNER';const tabs=profile?[['overview','Обзор'],['clans','Кланы и заявки'],...owner?[['players','Игроки и доступ'],['news','Новости'],['audit','Audit log'],['servers','Сервер']]:[],['security','Безопасность']]:[];
  const nav=<>{tabs.map(([id,label])=><button key={id} className="nav-item" aria-current={tab===id?'page':undefined} onClick={()=>setTab(id!)}>{label}</button>)}</>;
  return <ProductShell eyebrow="Control room" title="AnimeOnCraft Admin" nav={nav}>
    <h2 className="page-heading">Панель управления</h2><p className="page-intro">Официальные кланы, игроки и новости AnimeOnCraft.</p>
    {checking?<p role="status">Проверяем сессию…</p>:!profile?<LoginPanel onLogin={value=>{setProfile(value);setTab('overview');}}/>:<div key={profile.session_id}>
      {tab==='overview'&&<OverviewPanel/>}{tab==='clans'&&<ClanManagement actorId={profile.id} canRemove={id=>profile.clan_management.some(row=>row.clan_id===id&&row.can_remove_members)}/>}{owner&&tab==='players'&&<PlayersPanel/>}{owner&&tab==='news'&&<NewsPanel/>}{owner&&tab==='audit'&&<AuditPanel/>}{owner&&tab==='servers'&&<ServersPanel/>}{tab==='security'&&<SecurityPanel profile={profile} onLogout={()=>setProfile(null)}/>}
    </div>}
    {!profile&&!checking&&<ClanDirectory/>}
  </ProductShell>;
}

const root = document.getElementById('root');
if (!root) throw new Error('Missing #root element');
createRoot(root).render(<StrictMode>{location.pathname.startsWith('/admin/phone')?<MobileOwnerApp/>:<App />}</StrictMode>);
