import { PublicApi, parseClanProgression, parseClanCompetitions, parseClanWars } from './public-api.js';
import { useResource, type ResourceState } from './use-resource.js';
import { LauncherIcon } from './launcher-icon.js';
import { levelProgress } from './clan-brand.js';

const achievementNames: Record<string, string> = {
  FIRST_BOSS: 'Первый босс', BOSS_HUNTERS: 'Охотники на боссов', BOSS_VETERANS: 'Ветераны охоты',
  BOSS_LEGENDS: 'Легенды охоты', BOSS_GUILD: 'Гильдия победителей',
  POINT_FIRST: 'Первая ресурсная точка', POINT_FIVE: 'Контроль ресурсов', POINT_FIFTEEN: 'Ресурсная империя',
  DEFENCE_FIRST: 'Не отступать', DEFENCE_FIVE: 'Крепкий гарнизон', DEFENCE_FIFTEEN: 'Несокрушимые',
  WAR_FIRST: 'Первое завоевание', WAR_FIVE: 'Покорители земель',
};
type Progression = ReturnType<typeof parseClanProgression>;
type Competitions = ReturnType<typeof parseClanCompetitions>;
type Wars = ReturnType<typeof parseClanWars>;
const warLabels: Record<string, string> = { PREPARATION: 'Подготовка', ACTIVE: 'Рейд открыт', COOLDOWN: 'Перерыв после войны', FINISHED: 'Завершена', CANCELLED: 'Отменена' };
const time = (value: string) => new Date(value).toLocaleString('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });

export function useClanData(service: PublicApi, clanId: string, revision: number) {
  return {
    progression: useResource(service, { resource: 'clan_progression', key: clanId }, parseClanProgression, revision),
    competitions: useResource(service, { resource: 'clan_competitions', key: clanId }, parseClanCompetitions, revision),
    wars: useResource(service, { resource: 'clan_wars', key: clanId }, parseClanWars, revision),
  };
}

/** Level, XP, reputation, boss statistics and achievement count. */
export function ClanLevel({ state }: { state: ResourceState<Progression> }) {
  if (state.status === 'loading') return <section className="panel clan-level"><p role="status" className="muted">Загружаем прогрессию клана…</p></section>;
  if (state.status === 'error') return <section className="panel clan-level"><p role="status" className="muted">Прогрессия пока недоступна. {state.message}</p></section>;
  const clan = state.data, progress = levelProgress(clan.level, clan.xp);
  const unlocked = clan.achievements.filter(item => item.unlocked_at !== null).length;
  return <section className="panel clan-level clan-progression" aria-label="Прогрессия клана">
    <div className="block-head"><h3 className="panel-title"><LauncherIcon name="swords" size={18} />Уровень клана</h3><strong className="level-value">Ур. {clan.level}</strong></div>
    <div className="xp-bar" role="progressbar" aria-label="Опыт до следующего уровня" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent}><span style={{ width: `${progress.percent}%` }} /></div>
    <p className="xp-caption">{clan.xp.toLocaleString('ru-RU')}{progress.next ? ` / ${progress.next.toLocaleString('ru-RU')} XP` : ' XP · максимальный уровень'}</p>
    <div className="stat-tiles">
      <div className="stat-tile"><span className="stat-icon star"><LauncherIcon name="star" size={18} /></span><div><small>Репутация</small><strong>{clan.reputation.toLocaleString('ru-RU')}</strong></div></div>
      <div className="stat-tile"><span className="stat-icon"><LauncherIcon name="chart" size={18} /></span><div><small>Статистика</small><strong>{clan.kills}</strong><small className="muted">боссов побеждено</small></div></div>
      <div className="stat-tile"><span className="stat-icon trophy"><LauncherIcon name="trophy" size={18} /></span><div><small>Достижения</small><strong>{unlocked} / {clan.achievements.length}</strong><small className="muted">разблокировано</small></div></div>
    </div>
  </section>;
}

export function ClanAchievements({ state }: { state: ResourceState<Progression> }) {
  if (state.status !== 'ready') return null;
  return <section className="panel" aria-label="Достижения клана">
    <h3 className="panel-title"><LauncherIcon name="trophy" size={18} />Достижения</h3>
    <div className="achievement-grid">{state.data.achievements.map(item => <article key={item.code} className="achievement-card" data-unlocked={item.unlocked_at !== null}>
      <strong>{achievementNames[item.code] ?? item.code}</strong>
      <p>{item.progress} / {item.target} {item.code.startsWith('POINT_') ? 'захватов точек' : item.code.startsWith('DEFENCE_') ? 'защит точек' : item.code.startsWith('WAR_') ? 'завоеваний' : 'боссов'}</p>
      <progress value={item.progress} max={item.target} aria-label={achievementNames[item.code] ?? item.code} />
      <p className="muted small">{item.unlocked_at ? 'Получено · бонус начислен клану' : 'Ещё не получено'}</p>
    </article>)}</div>
    <p className="muted small">Опыт выдаётся за боссов, захват и защиту точек и завоевания. Достижения сохраняются и дают бонус один раз.</p>
  </section>;
}

function EmptyBlock({ icon, title, text }: { icon: 'calendar' | 'trophy'; title: string; text: string }) {
  return <div className="empty-inline boxed"><span className="empty-icon"><LauncherIcon name={icon} size={20} /></span><div><strong>{title}</strong><small className="muted">{text}</small></div></div>;
}

export function ClanWars({ state, compact = false }: { state: ResourceState<Wars>; compact?: boolean }) {
  const items = state.status === 'ready' ? (compact ? state.data.items.slice(0, 2) : state.data.items) : [];
  return <section className="panel" aria-label="Войны клана">
    <h3 className="panel-title"><LauncherIcon name="swords" size={18} />{compact ? 'Ближайшие войны' : 'Войны и территории'}</h3>
    {state.status === 'loading' ? <p role="status" className="muted">Загружаем расписание…</p> : state.status === 'error' ? <p role="status" className="muted">{state.message}</p> : !items.length ? <EmptyBlock icon="calendar" title="Войны пока не назначены" text="Расписание войн назначает владелец." /> : items.map(war => <article className="event-card" key={war.id}>
      <strong>{war.attacker} → {war.defender}</strong><span className="tag">{warLabels[war.status]}</span>
      <p className="muted small">Окно рейда: {time(war.starts_at)} — {time(war.ends_at)}</p>
      {war.status === 'COOLDOWN' && war.cooldown_ends_at && <p className="small">Следующая война доступна после {time(war.cooldown_ends_at)}</p>}
    </article>)}
    {!compact && <p className="muted small">Вне окна рейда чужие территории и контейнеры защищены. Спавны защищены всегда. Урон соклановцам отключён. Захват: удерживайте точку в 48 блоках к востоку от спавна территории 5 минут без противников в радиусе 12 блоков. После рейда — перерыв 24 часа.</p>}
  </section>;
}

export function ClanCompetitions({ state, compact = false }: { state: ResourceState<Competitions>; compact?: boolean }) {
  const items = state.status === 'ready' ? (compact ? state.data.items.slice(0, 2) : state.data.items) : [];
  return <section className="panel" aria-label="Соревнования клана">
    <h3 className="panel-title"><LauncherIcon name="trophy" size={18} />{compact ? 'Ближайшие соревнования' : 'Клановые соревнования'}</h3>
    {state.status === 'loading' ? <p role="status" className="muted">Загружаем соревнования…</p> : state.status === 'error' ? <p role="status" className="muted">{state.message}</p> : !items.length ? <EmptyBlock icon="trophy" title="Соревнования пока не объявлены" text="Информация о турнирах появится здесь." /> : items.map(item => <article className="event-card" key={item.id}>
      <strong>{item.title}</strong>
      <span className="tag">{item.status === 'FINISHED' ? item.outcome === 'EXPIRED' ? 'Время вышло — наград нет' : 'Завершено' : item.status === 'CANCELLED' ? 'Отменено' : Date.now() >= Date.parse(item.ends_at) ? 'Подводим итоги (до 10 минут)' : Date.now() < Date.parse(item.starts_at) ? 'Сбор перед появлением босса' : 'Идёт сейчас'}</span>
      <p className="small">Результат клана: {item.kind === 'WORLD_BOSS' ? `${(item.score / 1000).toLocaleString('ru-RU')} урона` : `${item.score} боссов`}</p>
      {!compact && item.kind === 'WORLD_BOSS' && <>
        <p className="small">Появление: {time(item.starts_at)}. Один общий босс для всех кланов.</p>
        {item.arena && <p className="small">Нейтральная территория: X {item.arena.x}, Y {item.arena.y}, Z {item.arena.z}</p>}
        <p className="muted small">После победы 2000 опыта и 60 репутации делятся по нанесённому урону между кланами с вкладом от 5%. Бонусы достижений начисляются отдельно. При истечении времени наград нет.</p>
      </>}
      <p className="muted small">До {time(item.ends_at)}</p>
      {!compact && item.leaderboard.length > 0 && <ol className="leaderboard">{item.leaderboard.map(leader => <li key={leader.short_code}>{leader.short_code}: {item.kind === 'WORLD_BOSS' ? `${(leader.score / 1000).toLocaleString('ru-RU')} урона` : leader.score}</li>)}</ol>}
      {item.reward_granted_at && <p className="ok-text small">Награда начислена клану</p>}
    </article>)}
    {!compact && <p className="muted small">Побеждайте общего босса вместе или соперничайте за вклад своего клана. Обычные вызванные игроками боссы не увеличивают счёт этого события.</p>}
  </section>;
}
