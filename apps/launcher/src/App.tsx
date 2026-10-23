import { Component, useCallback, useEffect, useRef, useState, type CSSProperties, type PropsWithChildren } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { StatusPanel } from '@animeoncraft/ui';
import type { ReadyResponse } from '@animeoncraft/contracts';
import { PublicApi, parseArticle, parseClan, parseClans, parseNewsList, parseReady, safeDiscord } from './public-api.js';
import { defaultSettings, loadSettings, native, nativeHealth, saveSettings, transport, type LauncherSettings, type NativeHealth } from './native.js';
import { useResource, type ResourceState } from './use-resource.js';
import { pages, readRoute, type Page, type Route } from './routes.js';
import { useGameServer, serverLabel, playerCount, type ServerView } from './game-server.js';
import { CharacterPreview } from './character-preview.js';
import { LaunchControl } from './launch-control.js';
import { LauncherIcon, type IconName } from './launcher-icon.js';
import { AccountPanel, ClanApplication } from './account-panel.js';
import { SimpleUpdateNotice, SimpleUpdatePanel, useSimpleUpdate, type SimpleUpdateState } from './simple-update.js';
import { BetaReadiness } from './beta-readiness.js';
import { WorldMapPage } from './world-map.js';
import { ClanRoster } from './clan-roster.js';
import { OwnerClansPanel } from './owner-clans.js';
import { canOpenAdmin, useNavigationAccount, accountChangedEvent } from './account-navigation.js';
import { ClanAchievements, ClanCompetitions, ClanLevel, ClanWars, useClanData } from './clan-progress.js';
import { vpnHint } from './connection-hint.js';
import { accountAction, type AccountSnapshot } from './account-api.js';
import { clanColor, clanMonogram, pluralRu } from './clan-brand.js';
import type { PreviewSkin } from './skin-model-preview.js';

const api = new PublicApi(transport);
const mascot = '/brand/animeoncraft-avatar.png';
type Updater = ReturnType<typeof useSimpleUpdate>;

function Notice<T>({ state }: { state: ResourceState<T> }) {
  return state.status === 'loading' ? <p role="status" className="muted">Загрузка…</p> : state.status === 'error' ? <StatusPanel tone="warning" title="Не удалось загрузить данные">{state.message}</StatusPanel> : null;
}
function PageHead({ title, intro, children }: PropsWithChildren<{ title: string; intro: string }>) {
  return <div className="page-head"><div><h2 className="page-heading">{title}</h2><p className="page-intro">{intro}</p></div>{children && <div className="page-head-actions">{children}</div>}</div>;
}
function formatDate(value: string): string { return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(value)); }
const clanStyle = (code: string) => ({ '--clan': clanColor(code) }) as CSSProperties;

/* ───────────────────────── Home ───────────────────────── */

function Home({ service, server, ready, updater, refresh, account }: { service: PublicApi; server: ServerView; ready: ResourceState<ReadyResponse>; updater: Updater; refresh: () => void; account: AccountSnapshot }) {
  const news = useResource(service, { resource: 'news' }, parseNewsList, 0);
  const clans = useResource(service, { resource: 'clans' }, parseClans, 0);
  const name = account.state === 'SIGNED_IN' ? account.profile?.login : null;
  return <div className="home-screen">
    {native && (ready.status === 'error' || server.error || server.snapshot?.status === 'OFFLINE' || server.snapshot?.status === 'INVALID_RESPONSE') && <div className="connection-warning" role="status"><LauncherIcon name="wifi" size={18} /><span data-testid="vpn-hint"><strong>Нет соединения с сервером.</strong> {vpnHint}</span></div>}
    <div className="home-top">
      <section className="hero" aria-labelledby="home-title">
        <div className="hero-art" aria-hidden="true" />
        <div className="hero-copy">
          <span className="welcome-chip">{name ? `Добро пожаловать, ${name}!` : 'Добро пожаловать домой'}</span>
          <h2 id="home-title" className="hero-title">Твой мир начинается<br />с AnimeOnCraft</h2>
          <p className="hero-text">Магия, исследование и строительство вместе с друзьями. Кланы, общий мир и собственный игровой профиль.</p>
        </div>
        <LaunchControl server={server} updater={updater} onPrepared={refresh} />
      </section>
      <CharacterPreview />
    </div>
    <section className="home-clans" aria-labelledby="home-clans-title">
      <div className="section-head"><h3 id="home-clans-title">Наши кланы</h3><span className="muted">Найди своё сообщество</span><a href="#clans" className="section-link">Все кланы <LauncherIcon name="arrow-right" size={14} /></a></div>
      <Notice state={clans} />
      {clans.status === 'ready' && (clans.data.items.length ? <div className="clan-strip">{clans.data.items.map(clan => <a className="clan-mini" style={clanStyle(clan.short_code)} data-clan={clan.short_code} href={`#clans/${clan.id}`} key={clan.id}>
        <span className="clan-badge small" aria-hidden="true">{clanMonogram(clan.short_code)}</span>
        <span className="clan-mini-body">
          <strong>{clan.name}</strong>
          <small title={clan.member_count === null ? 'Нет данных об участниках' : `${clan.member_count} ${pluralRu(clan.member_count, ['участник', 'участника', 'участников'])}`}><LauncherIcon name="users" size={13} />{clan.member_count ?? '—'}<i className="dot-sep" /><span className="dot ok" aria-hidden="true" />{clan.online_count ?? '—'} онлайн</small>
          <em>Подробнее о клане <LauncherIcon name="arrow-right" size={12} /></em>
        </span>
        <LauncherIcon name="chevron-right" size={16} />
      </a>)}</div> : <p className="empty-state">Открытых кланов пока нет.</p>)}
    </section>
    <div className="home-bottom">
      <section className="panel home-news" aria-labelledby="news-title">
        <div className="block-head"><h3 id="news-title" className="panel-title">Последние новости</h3><a href="#news" className="section-link">Все новости <LauncherIcon name="arrow-right" size={14} /></a></div>
        <Notice state={news} />
        {news.status === 'ready' && (news.data.items.length ? <div className="news-row">{news.data.items.slice(0, 3).map(item => <a className="news-mini" href={`#news/${item.slug}`} key={item.id}>
          <span className="news-mini-icon"><LauncherIcon name="file" size={20} /></span>
          <span><strong>{item.title}</strong><time dateTime={item.published_at}>{formatDate(item.published_at)}</time></span>
        </a>)}</div> : <p className="empty-state">Публикаций пока нет. Новости появятся после публикации владельцем проекта.</p>)}
      </section>
      <ServicesPanel ready={ready} server={server} update={updater.state} refresh={refresh} />
    </div>
  </div>;
}

function stateTone(ok: boolean | null): 'ok' | 'warn' | 'wait' { return ok === null ? 'wait' : ok ? 'ok' : 'warn'; }
function ServicesPanel({ ready, server, update, refresh }: { ready: ResourceState<ReadyResponse>; server: ServerView; update: SimpleUpdateState | null; refresh: () => void }) {
  const api = ready.status === 'ready' ? ready.data.status === 'ok' : ready.status === 'loading' ? null : false;
  const game = native ? (server.error ? false : server.snapshot ? server.snapshot.status === 'AVAILABLE' : null) : null;
  const updates = !native || !update ? null : update.status === 'error' ? false : true;
  const rows: { label: string; value: string; tone: 'ok' | 'warn' | 'wait'; test?: string; state?: string }[] = [
    { label: 'Игровой сервер', value: serverLabel(server), tone: stateTone(game), test: 'game-server-state', state: server.snapshot?.status ?? 'UNKNOWN' },
    { label: 'Игроки онлайн', value: playerCount(server.snapshot), tone: stateTone(game), test: 'game-server-players' },
    { label: 'Аккаунты и API', value: ready.status === 'ready' ? ready.data.status === 'ok' ? 'Онлайн' : 'Частично' : ready.status === 'loading' ? 'Проверяем…' : 'Нет связи', tone: stateTone(api) },
    { label: 'Обновления лаунчера', value: !native ? '—' : !update ? 'Проверяем…' : { unavailable: 'Не подключены', idle: 'Готово', checking: 'Проверяем…', current: 'Актуальная версия', available: `Есть ${update.latest_version}`, downloading: 'Загрузка…', installing: 'Установка…', error: 'Ошибка проверки' }[update.status], tone: update?.status === 'available' ? 'warn' : stateTone(updates) },
  ];
  return <section className="panel services" aria-labelledby="service-title">
    <div className="block-head"><h3 id="service-title" className="panel-title">Состояние сервисов</h3><a href="#settings/diagnostics" className="section-link">Подробнее <LauncherIcon name="arrow-right" size={14} /></a></div>
    <ul className="service-list">{rows.map(row => <li key={row.label}><span className={`dot ${row.tone}`} aria-hidden="true" /><span>{row.label}</span><strong className={`tone-${row.tone}`} {...(row.test ? { 'data-testid': row.test } : {})} {...(row.state ? { 'data-state': row.state } : {})}>{row.value}</strong></li>)}</ul>
    {native && server.snapshot && <p className="muted small">Проверено в {new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(server.snapshot.checked_at))}{server.snapshot.latency_ms !== null ? ` · ответ ${server.snapshot.latency_ms === 0 ? 'менее 1' : server.snapshot.latency_ms} мс` : ''}</p>}
    <button type="button" className="text-button" onClick={refresh}><LauncherIcon name="refresh" size={14} />Обновить состояние</button>
  </section>;
}

/* ───────────────────────── Clans ───────────────────────── */

function Clans({ service, clanId }: { service: PublicApi; clanId?: string }) {
  const [revision, refresh] = useState(0), [cursors, setCursors] = useState<string[]>([]); const cursor = cursors.at(-1);
  const list = useResource(service, { resource: 'clans', ...(cursor ? { cursor } : {}) }, parseClans, revision);
  if (clanId) return <ClanDetail key={clanId} service={service} clanId={clanId} />;
  return <>
    <PageHead title="Найди свой клан" intro="Объединяйся с игроками, участвуй в событиях и развивайся вместе.">
      <button type="button" className="ghost-button" onClick={() => { setCursors([]); refresh(value => value + 1); }}><LauncherIcon name="refresh" size={16} />Обновить кланы</button>
    </PageHead>
    <Notice state={list} />
    {list.status === 'ready' && (list.data.items.length ? <div className="clan-grid">{list.data.items.map(clan => <article className="clan-card clan-catalog-card" style={clanStyle(clan.short_code)} data-clan={clan.short_code} key={clan.id}>
      <div className="clan-card-glow" aria-hidden="true" />
      <span className="clan-badge" aria-hidden="true">{clanMonogram(clan.short_code)}</span>
      <h3>{clan.name}</h3>
      <p className="clan-card-text">{clan.description || 'Описание пока не добавлено.'}</p>
      <div className="clan-card-stats">
        <div><LauncherIcon name="users" size={16} /><span><small>Участники</small><strong>{clan.member_count ?? '—'}</strong></span></div>
        <div><span className="dot ok" aria-hidden="true" /><span><small>Онлайн</small><strong>{clan.online_count ?? '—'}</strong></span></div>
      </div>
      <a className="outline-button" href={`#clans/${clan.id}`}><LauncherIcon name="chevron-right" size={15} />Открыть клан</a>
    </article>)}</div> : <p className="empty-state">Открытых кланов пока нет. Исходные кланы появятся здесь после включения владельцем.</p>)}
    {(cursors.length > 0 || (list.status === 'ready' && list.data.next_cursor)) && <div className="pager">
      <button type="button" className="ghost-button" disabled={!cursors.length} onClick={() => setCursors(value => value.slice(0, -1))}><LauncherIcon name="arrow-left" size={15} />Назад</button>
      <span className="pager-page">{cursors.length + 1}</span>
      <button type="button" className="ghost-button" disabled={list.status !== 'ready' || !list.data.next_cursor} onClick={() => list.status === 'ready' && list.data.next_cursor && setCursors(value => [...value, list.data.next_cursor!])}>Вперёд<LauncherIcon name="arrow-right" size={15} /></button>
    </div>}
    <div className="my-clan-area"><AccountPanel area="clans" /></div>
  </>;
}

type ClanTab = 'overview' | 'roster' | 'events' | 'applications';
function ClanDetail({ service, clanId }: { service: PublicApi; clanId: string }) {
  const [revision, refresh] = useState(0), [tab, setTab] = useState<ClanTab>('overview');
  const state = useResource(service, { resource: 'clan', key: clanId }, parseClan, revision);
  const data = useClanData(service, clanId, revision);
  const account = useNavigationAccount(`clan/${clanId}`), owner = canOpenAdmin(account);
  useEffect(() => { const timer = setInterval(() => refresh(value => value + 1), 30000); return () => clearInterval(timer); }, []);
  const clan = state.status === 'ready' ? state.data : null;
  const discord = clan ? safeDiscord(clan.discord_url) : null;
  const tabs: [ClanTab, string, IconName][] = [['overview', 'Обзор', 'home'], ['roster', 'Состав', 'users'], ['events', 'События и войны', 'calendar'], ['applications', 'Заявки', 'mail']];
  return <div className="clan-detail" style={clan ? clanStyle(clan.short_code) : undefined} data-clan={clan?.short_code}>
    <nav className="breadcrumbs" aria-label="Навигация"><a href="#clans"><LauncherIcon name="arrow-left" size={14} />Все кланы</a>{clan && <><LauncherIcon name="chevron-right" size={13} /><span>{clan.name}</span></>}</nav>
    <Notice state={state} />
    {clan && <>
      <div className="clan-head">
        <span className="clan-badge large" aria-hidden="true">{clanMonogram(clan.short_code)}</span>
        <div className="clan-head-text"><h2 className="page-heading">{clan.name}</h2><p className="muted">{clan.description || 'Описание пока не добавлено.'}</p></div>
        <button type="button" className="icon-button" aria-label="Обновить информацию" title="Обновить информацию" onClick={() => refresh(value => value + 1)}><LauncherIcon name="refresh" size={17} /></button>
        <div className="tabs pill-tabs" role="tablist" aria-label="Разделы клана">{tabs.map(([key, label, icon]) => <button key={key} type="button" role="tab" aria-selected={tab === key} onClick={() => setTab(key)}><LauncherIcon name={icon} size={15} />{label}</button>)}</div>
      </div>
      {tab === 'overview' && <div className="clan-overview"><div className="clan-main">
        <section className="panel clan-about" aria-label="О клане">
          <h3 className="panel-title"><LauncherIcon name="alert" size={17} />О клане</h3>
          <p>{clan.description || 'Описание пока не добавлено.'}</p>
          <dl className="info-grid">
            <div><dt><LauncherIcon name="users" size={15} />Участники</dt><dd>{clan.member_count ?? 'Неизвестно'}</dd></div>
            <div><dt><span className="dot ok" />Онлайн</dt><dd>{clan.online_count ?? 'Неизвестно'}</dd></div>
            <div className="wide"><dt><LauncherIcon name="activity" size={15} />Свежесть данных об онлайне</dt><dd>{clan.online_updated_at ? new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(clan.online_updated_at)) : 'Нет подтверждённых данных'}</dd></div>
          </dl>
          {discord && <a href={discord} target="_blank" rel="noreferrer noopener" className="section-link">Discord клана ↗</a>}
        </section>
        <ClanLevel state={data.progression} />
        <div className="clan-events-row">
          <ClanWars state={data.wars} compact />
          <ClanCompetitions state={data.competitions} compact />
        </div></div>
        <div className="clan-side">
          <ClanApplication clanId={clanId} />
          {owner && <div className="panel"><OwnerClansPanel clanId={clanId} compact onChanged={() => refresh(value => value + 1)} /></div>}
        </div>
      </div>}
      {tab === 'roster' && <div className="panel"><ClanRoster key={clanId} clanId={clanId} /></div>}
      {tab === 'events' && <div className="clan-events-full"><ClanCompetitions state={data.competitions} /><ClanWars state={data.wars} /><ClanAchievements state={data.progression} /></div>}
      {tab === 'applications' && <div className="clan-applications">
        <ClanApplication clanId={clanId} />
        {owner ? <div className="panel"><OwnerClansPanel clanId={clanId} onChanged={() => refresh(value => value + 1)} /></div> : <div className="panel"><h3 className="panel-title">Мои заявки</h3><p className="muted">Статус своих заявок и их отзыв — на странице <a href="#clans">«Кланы»</a> в блоке «Мои заявки».</p></div>}
      </div>}
    </>}
  </div>;
}

/* ───────────────────────── News ───────────────────────── */

function News({ service, slug }: { service: PublicApi; slug?: string }) {
  const [revision, refresh] = useState(0), [cursors, setCursors] = useState<string[]>([]); const cursor = cursors.at(-1);
  const state = useResource(service, { resource: 'news', ...(cursor ? { cursor } : {}) }, parseNewsList, revision);
  if (slug) return <Article key={slug} service={service} slug={slug} />;
  return <>
    <PageHead title="Новости AnimeOnCraft" intro="Следите за обновлениями, новостями и важными объявлениями проекта.">
      <button type="button" className="ghost-button" onClick={() => { setCursors([]); refresh(value => value + 1); }}><LauncherIcon name="refresh" size={16} />Обновить</button>
    </PageHead>
    <Notice state={state} />
    {state.status === 'ready' && (state.data.items.length ? <div className="news-list">{state.data.items.map(item => <article className="news-card" key={item.id}>
      <span className="news-card-icon" aria-hidden="true"><LauncherIcon name="news" size={30} /></span>
      <div className="news-card-body"><h3><a href={`#news/${item.slug}`}>{item.title}</a></h3><time dateTime={item.published_at}>{formatDate(item.published_at)}</time></div>
      <a className="outline-button" href={`#news/${item.slug}`}>Читать <LauncherIcon name="arrow-right" size={15} /></a>
    </article>)}</div> : <p className="empty-state">Опубликованных новостей пока нет.</p>)}
    <div className="pager">
      <button type="button" className="ghost-button" disabled={!cursors.length} onClick={() => setCursors(value => value.slice(0, -1))}><LauncherIcon name="arrow-left" size={15} />Назад</button>
      <span className="pager-page">{cursors.length + 1}</span>
      <button type="button" className="ghost-button" disabled={state.status !== 'ready' || !state.data.next_cursor} onClick={() => state.status === 'ready' && state.data.next_cursor && setCursors(value => [...value, state.data.next_cursor!])}>Вперёд<LauncherIcon name="arrow-right" size={15} /></button>
    </div>
  </>;
}

function ArticleBody({ text }: { text: string }) {
  const blocks = text.split(/\n\s*\n/).map(block => block.trim()).filter(Boolean);
  return <div className="article-body">{blocks.map((block, index) => block.split('\n').every(line => /^\s*[-•*]\s+/.test(line))
    ? <ul key={index}>{block.split('\n').map((line, i) => <li key={i}>{line.replace(/^\s*[-•*]\s+/, '')}</li>)}</ul>
    : block.length <= 70 && !/[.!?:;]$/.test(block) && !block.includes('\n') && index < blocks.length - 1 ? <h4 key={index}>{block}</h4> : <p key={index}>{block}</p>)}</div>;
}
function Article({ service, slug }: { service: PublicApi; slug: string }) {
  const [revision, refresh] = useState(0), state = useResource(service, { resource: 'article', key: slug }, parseArticle, revision);
  return <>
    <nav className="breadcrumbs" aria-label="Навигация"><a href="#news"><LauncherIcon name="arrow-left" size={14} />Все новости</a></nav>
    <Notice state={state} />
    {state.status === 'ready' && <div className="article-layout">
      <article className="panel article">
        <div className="block-head"><div><h2 className="page-heading">{state.data.title}</h2><time dateTime={state.data.published_at}>{formatDate(state.data.published_at)}</time></div>
          <button type="button" className="icon-button" aria-label="Обновить статью" title="Обновить статью" onClick={() => refresh(value => value + 1)}><LauncherIcon name="refresh" size={16} /></button></div>
        <ArticleBody text={state.data.body_markdown} />
      </article>
      <aside className="panel article-aside" aria-hidden="true"><img src={mascot} alt="" /><strong>AnimeOnCraft</strong><span className="muted">Твой мир начинается здесь</span></aside>
    </div>}
  </>;
}

/* ───────────────────────── Settings ───────────────────────── */

type SettingsSection = 'game' | 'account' | 'sessions' | 'updates' | 'diagnostics';
const sections: [SettingsSection, string, IconName][] = [['game', 'Игра', 'settings'], ['account', 'Аккаунт', 'user'], ['sessions', 'Сеансы и доступ', 'shield'], ['updates', 'Обновления', 'refresh'], ['diagnostics', 'Диагностика', 'activity']];
function sectionFromHash(): SettingsSection | null { const key = window.location.hash.split('/')[1]; return sections.some(([value]) => value === key) ? key as SettingsSection : null; }
function Settings({ updater }: { updater: Updater }) {
  const [section, setSection] = useState<SettingsSection>(() => sectionFromHash() ?? 'game');
  useEffect(() => { const sync = () => { const key = sectionFromHash(); if (key) setSection(key); }; window.addEventListener('hashchange', sync); return () => window.removeEventListener('hashchange', sync); }, []);
  const [settings, setSettings] = useState<LauncherSettings>({ ...defaultSettings });
  const [loading, setLoading] = useState(true), [saving, setSaving] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  useEffect(() => { let active = true; void loadSettings().then(value => { if (active) { setSettings(value.settings); setNotice(value.saved ? 'Сохранённые настройки загружены.' : 'Используются начальные настройки.'); } }).catch(() => { if (active) setError('Не удалось прочитать настройки. Сохранение отключено, чтобы не перезаписать повреждённый файл.'); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, []);
  function change<K extends keyof LauncherSettings>(key: K, value: LauncherSettings[K]) { setSettings(current => ({ ...current, [key]: value })); setNotice('Есть несохранённые изменения.'); }
  // Keep the address in sync so links such as #settings/account from the account menu always switch the section.
  const go = (key: SettingsSection) => { setSection(key); try { window.history.replaceState(null, '', `#settings/${key}`); } catch { /* preview without history */ } };
  return <>
    <PageHead title="Настройки лаунчера" intro="Настрой конфигурацию игры, аккаунт и другие параметры AnimeOnCraft." />
    <div className="settings-layout">
      <nav className="settings-nav panel" aria-label="Разделы настроек">{sections.map(([key, label, icon]) => <button key={key} type="button" aria-current={section === key ? 'page' : undefined} onClick={() => go(key)}><LauncherIcon name={icon} size={19} />{label}</button>)}</nav>
      <div className="settings-content">
        <section hidden={section !== 'game'} className="settings-game">
          <div className="panel game-settings">
            <div className="panel-head"><span className="panel-icon"><LauncherIcon name="gamepad" size={20} /></span><div><h3>Игра</h3><p className="muted small">Настройки запуска Minecraft и производительности.</p></div></div>
            {!native && <StatusPanel tone="neutral" title="Открыт веб-просмотр">Здесь можно посмотреть форму. Настройки сохраняются только в Windows-приложении.</StatusPanel>}
            {loading ? <p role="status">Загрузка настроек…</p> : <form onSubmit={event => { event.preventDefault(); if (!native || saving || error) return; setSaving(true); setNotice(''); void saveSettings(settings).then(value => { setSettings(value.settings); setNotice('Настройки сохранены.'); }).catch(() => setNotice('Не удалось сохранить настройки. Проверьте значения и доступ к папке приложения.')).finally(() => setSaving(false)); }}>
              {error && <p role="alert">{error}</p>}
              <fieldset disabled={saving || !!error} className="plain-fieldset">
                <div className="setting-row"><span>Выделение памяти (RAM)</span><label className="unit-input"><input name="ram_mib" aria-label="RAM, МиБ" type="number" min="2048" max="32768" step="256" required value={settings.ram_mib} onChange={event => change('ram_mib', Number(event.target.value))} /><span>МиБ</span></label></div>
                <div className="setting-row"><span>Разрешение окна</span><span className="size-pair"><label className="unit-input stacked"><input name="width" aria-label="Ширина окна" type="number" min="800" max="7680" required value={settings.width} onChange={event => change('width', Number(event.target.value))} /><small>ширина</small></label><b aria-hidden="true">×</b><label className="unit-input stacked"><input name="height" aria-label="Высота окна" type="number" min="600" max="4320" required value={settings.height} onChange={event => change('height', Number(event.target.value))} /><small>высота</small></label></span></div>
                <div className="setting-row"><span>Полноэкранный режим<small className="muted">Запускать игру в полноэкранном режиме</small></span><label className="switch"><input name="fullscreen" type="checkbox" aria-label="Полноэкранный режим" checked={settings.fullscreen} onChange={event => change('fullscreen', event.target.checked)} /><span aria-hidden="true" /></label></div>
              </fieldset>
              <div className="button-row">
                <button type="submit" className="primary-button" disabled={!native || saving || !!error}><LauncherIcon name="check" size={16} />{saving ? 'Сохраняем…' : 'Сохранить настройки'}</button>
                <button type="button" className="ghost-button" disabled={saving || !!error} onClick={() => { setSettings({ ...defaultSettings }); setNotice('Начальные значения выбраны. Нажмите «Сохранить», чтобы применить.'); }}><LauncherIcon name="rotate-left" size={16} />Начальные значения</button>
              </div>
              <p role="status" className="muted small">{notice}</p>
            </form>}
            <p className="muted small">Перед запуском проверяется Java 21. Настройки применяются при следующем запуске Minecraft.</p>
          </div>
          <div className="settings-cards">
            <div className="panel mini-card"><span className="panel-icon"><LauncherIcon name="refresh" size={20} /></span><div><h3>Обновления</h3><p className="muted small">Текущая версия лаунчера: <strong>{updater.state?.current_version ?? '—'}</strong></p></div><button type="button" className="ghost-button" onClick={() => go('updates')}>Открыть</button></div>
            <div className="panel mini-card"><span className="panel-icon"><LauncherIcon name="activity" size={20} /></span><div><h3>Диагностика</h3><p className="muted small">Проверка соединения с серверами AnimeOnCraft.</p></div><button type="button" className="ghost-button" onClick={() => go('diagnostics')}>Проверить готовность</button></div>
          </div>
        </section>
        <section hidden={section !== 'account'}><AccountPanel area="settings" /></section>
        <section hidden={section !== 'sessions'}><AccountPanel area="sessions" /></section>
        <section hidden={section !== 'updates'}><SimpleUpdatePanel controller={updater} /></section>
        <section hidden={section !== 'diagnostics'}><BetaReadiness service={api} /><div className="vpn-pill"><LauncherIcon name="alert" size={16} />{vpnHint}</div></section>
      </div>
    </div>
  </>;
}

/* ───────────────────────── Shell ───────────────────────── */

function SkinHead() {
  const ref = useRef<HTMLCanvasElement>(null), [skin, setSkin] = useState<PreviewSkin | null>(null);
  useEffect(() => {
    if (!native) return;
    let active = true;
    const load = () => void invoke<{ skin: PreviewSkin | null }>('skin_read').then(value => { if (active) setSkin(value.skin); }).catch(() => { if (active) setSkin(null); });
    load(); window.addEventListener(accountChangedEvent, load);
    return () => { active = false; window.removeEventListener(accountChangedEvent, load); };
  }, []);
  useEffect(() => {
    const ctx = ref.current?.getContext('2d'); if (!ctx || !skin) return;
    const image = new Image();
    image.onload = () => { ctx.imageSmoothingEnabled = false; ctx.clearRect(0, 0, 8, 8); ctx.drawImage(image, 8, 8, 8, 8, 0, 0, 8, 8); ctx.drawImage(image, 40, 8, 8, 8, 0, 0, 8, 8); };
    image.src = `data:image/png;base64,${skin.png_base64}`;
  }, [skin]);
  return skin ? <canvas ref={ref} className="skin-head" width={8} height={8} aria-hidden="true" /> : <img className="skin-head" src={mascot} alt="" />;
}

function AccountMenu({ account }: { account: AccountSnapshot }) {
  const [open, setOpen] = useState(false), [busy, setBusy] = useState(false), box = useRef<HTMLDivElement>(null);
  useEffect(() => { if (!open) return; const close = (event: MouseEvent) => { if (!box.current?.contains(event.target as Node)) setOpen(false); }; const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); }; document.addEventListener('mousedown', close); document.addEventListener('keydown', escape); return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', escape); }; }, [open]);
  if (account.state !== 'SIGNED_IN' || !account.profile) return <a className="account-chip" href="#profile"><span className="account-avatar"><img className="skin-head" src={mascot} alt="" /></span><span className="account-text"><strong>Войти</strong><small>Аккаунт AnimeOnCraft</small></span></a>;
  const profile = account.profile;
  return <div className="account-menu" ref={box}>
    <button type="button" className="account-chip" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(value => !value)}>
      <span className="account-avatar"><SkinHead /></span>
      <span className="account-text"><strong>{profile.login}</strong><small><span className="dot ok" aria-hidden="true" />{profile.system_role === 'OWNER' ? 'Создатель' : 'Игрок'}</small></span>
      <LauncherIcon name="chevron-down" size={16} />
    </button>
    {open && <div className="menu-popover" role="menu">
      <a role="menuitem" href="#profile" onClick={() => setOpen(false)}><LauncherIcon name="profile" size={16} />Профиль</a>
      <a role="menuitem" href="#settings/account" onClick={() => setOpen(false)}><LauncherIcon name="settings" size={16} />Аккаунт и пароль</a>
      {canOpenAdmin(account) && <a role="menuitem" href="#admin" onClick={() => setOpen(false)}><LauncherIcon name="admin" size={16} />Админка</a>}
      <button type="button" role="menuitem" disabled={busy} onClick={() => { setBusy(true); void accountAction({ action: 'logout' }).catch(() => undefined).finally(() => { setBusy(false); setOpen(false); window.dispatchEvent(new Event(accountChangedEvent)); window.location.hash = '#profile'; }); }}><LauncherIcon name="logout" size={16} />Выйти</button>
    </div>}
  </div>;
}

function StatusBar({ health, ready, server }: { health: NativeHealth | null; ready: ResourceState<ReadyResponse>; server: ServerView }) {
  const ok = ready.status === 'ready' && ready.data.status === 'ok' && (!native || server.snapshot?.status === 'AVAILABLE');
  const checking = ready.status === 'loading' || (native && !server.snapshot && !server.error);
  return <footer className="status-bar">
    <span><span className={`dot ${checking ? 'wait' : ok ? 'ok' : 'warn'}`} aria-hidden="true" />{checking ? 'Проверяем сервисы…' : ok ? 'Все системы работают' : 'Есть проблемы со связью'}</span>
    <span className="muted">Minecraft 1.21.1 (NeoForge)</span>
    <span className="status-spacer" />
    <span className="muted">{health ? `AnimeOnCraft ${health.version}` : native ? 'Windows launcher' : 'Веб-просмотр'}</span>
  </footer>;
}

const navIcons: Record<Page, IconName> = { home: 'home', clans: 'clans', map: 'pin', profile: 'profile', news: 'news', settings: 'settings', admin: 'admin' };
export function LauncherApp({ service = api }: { service?: PublicApi }) {
  const updater = useSimpleUpdate();
  const [route, setRoute] = useState<Route>(() => readRoute(typeof window === 'undefined' ? '' : window.location.hash));
  const account = useNavigationAccount(route.page);
  const [health, setHealth] = useState<NativeHealth | null>(null), [healthError, setHealthError] = useState(false);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision(value => value + 1), []);
  useEffect(() => { const timer = setInterval(refresh, 30000); return () => clearInterval(timer); }, [refresh]);
  const server = useGameServer(revision);
  const ready = useResource(service, { resource: 'ready' }, parseReady, revision);
  useEffect(() => { const update = () => setRoute(readRoute(window.location.hash)); window.addEventListener('hashchange', update); return () => window.removeEventListener('hashchange', update); }, []);
  useEffect(() => { let active = true; void nativeHealth().then(value => { if (active) setHealth(value); }).catch(() => { if (active) setHealthError(true); }); return () => { active = false; }; }, []);
  useEffect(() => { document.querySelector('.product-main')?.scrollTo?.({ top: 0 }); }, [route.page, route.key]);
  return <div className={`product-shell launcher-shell page-${route.page}`}>
    <header className="topbar">
      <a className="launcher-brand" href="#home" aria-label="AnimeOnCraft — главная"><img src={mascot} className="brand-avatar" alt="" /><h1>AnimeOnCraft</h1></a>
      <nav className="main-nav" aria-label="Основная навигация">
        {pages.map(item => <a key={item.page} className="nav-item" aria-current={route.page === item.page ? 'page' : undefined} href={`#${item.page}`}><LauncherIcon name={navIcons[item.page]} /><span>{item.label}</span></a>)}
        {canOpenAdmin(account) && <a className="nav-item admin-nav" aria-current={route.page === 'admin' ? 'page' : undefined} href="#admin"><LauncherIcon name="admin" /><span>Админка</span></a>}
      </nav>
      <AccountMenu account={account} />
    </header>
    <main className="product-main">
      {healthError && <p role="alert">Не удалось проверить Windows-приложение.</p>}
      {route.page !== 'home' && <SimpleUpdateNotice controller={updater} />}
      {route.page === 'home' ? <Home service={service} server={server} ready={ready} updater={updater} refresh={refresh} account={account} />
        : route.page === 'clans' ? <Clans service={service} {...(route.key ? { clanId: route.key } : {})} />
        : route.page === 'map' ? <><PageHead title="Карта мира" intro="Территории кланов, столицы и ключевые точки. Владельцы и таймеры обновляются автоматически." /><WorldMapPage service={service} /></>
        : route.page === 'news' ? <News service={service} {...(route.key ? { slug: route.key } : {})} />
        : route.page === 'profile' ? <>{account.state === 'SIGNED_IN' && <PageHead title="Профиль и кастомизация" intro="Настрой внешний вид персонажа и управляй основными данными аккаунта." />}<AccountPanel /></>
        : route.page === 'admin' ? <><PageHead title="Панель создателя" intro="Управление кланами и заявками AnimeOnCraft." /><AccountPanel area="admin" /></>
        : <Settings updater={updater} />}
    </main>
    <StatusBar health={health} ready={ready} server={server} />
  </div>;
}

export class LauncherBoundary extends Component<PropsWithChildren, { failed: boolean }> {
  override state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  override render() { return this.state.failed ? <main className="startup-fallback"><h1>Не удалось открыть лаунчер</h1><p>Обновите страницу или перезапустите приложение. Если ошибка повторится, сообщите её команде проекта.</p><a href="#home" onClick={() => window.location.reload()}>Перезагрузить</a></main> : this.props.children; }
}
