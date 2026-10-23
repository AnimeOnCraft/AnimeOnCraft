/**
 * «Карта» — schematic world map (World Design stage 2, docs/MAP_INTEGRATION.md).
 * Region polygons in registry colours, capitals, strategic points and resource sites from the public API, over an
 * optional relief underlay (a top-down render of the pregenerated world, stage 6; GET /world/relief).
 * Coordinates: x → right, z → down (north is up).
 */
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from 'react';
import { StatusPanel } from '@animeoncraft/ui';
import type { WorldEvent, WorldMapResponse, WorldPoint, WorldPointsResponse, WorldReliefBounds } from '@animeoncraft/contracts';
import type { PublicApi } from './public-api.js';
import { useResource, type ResourceState } from './use-resource.js';
import { parseWorldEvents, parseWorldMap, parseWorldPoints, parseWorldRelief } from './world-api.js';
import { clanColor } from './clan-brand.js';
import { LauncherIcon } from './launcher-icon.js';

export const POINT_LABELS: Record<WorldPoint['type'], string> = {
  CAPITAL: 'Столица', CLAN_CAPITAL: 'Столица клана', FORT: 'Форт', MINE: 'Шахта', VILLAGE: 'Деревня', PORT: 'Порт', BRIDGE: 'Мост', PASS: 'Перевал',
  WATCHTOWER: 'Дозорная башня', RUINS: 'Руины', RESOURCE_SITE: 'Ресурсная точка', OUTPOST: 'Застава', EVENT_ZONE: 'Зона ивента', BOSS_LAIR: 'Логово босса',
};
export const STATE_LABELS: Record<WorldPoint['state'], string> = {
  NEUTRAL: 'Ничья', CONTESTED: 'Объявлен захват', CAPTURING: 'Идёт захват', CONTROLLED: 'Под контролем', PROTECTED: 'Под защитой', AVAILABLE: 'Аренда истекла',
};
export const FAMILY_LABELS: Record<NonNullable<WorldPoint['resource']>['family'], string> = {
  FERROUS: 'Железо и уголь', NONFERROUS: 'Медь и цинк', PRECIOUS: 'Драгоценности', ARCANE: 'Магия', TECH: 'Техника',
  TIMBER: 'Древесина', AGRARIAN: 'Фермы', ALCHEMY: 'Алхимия', MARINE: 'Море',
};
const REGION_KIND_LABELS: Record<WorldMapResponse['regions'][number]['kind'], string> = {
  CORE: 'Центр', STATE: 'Государство', BORDERLAND: 'Пограничье', RIM: 'Внешний вал', FRONTIER_SLOT: 'Свободные земли', FRONTIER_WILD: 'Свободные земли', SEA: 'Море',
};
export const EVENT_LABELS: Record<WorldEvent['kind'], string> = {
  DECLARED: 'объявил захват', CAPTURED: 'захватил', RENEWED: 'продлил аренду', DEFENDED: 'отстоял', NEUTRALIZED: 'точка снова ничья',
  RELEASED: 'отпустил', ADMIN_TRANSFER: 'получил от администрации', ADMIN_RESET: 'точка сброшена администрацией',
};
const clock = (iso: string) => new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
type Layer = 'relief' | 'regions' | 'capitals' | 'points' | 'sites' | 'roads' | 'events';
const LAYERS: [Layer, string][] = [['relief', 'Рельеф'], ['regions', 'Территории'], ['capitals', 'Столицы'], ['points', 'Точки'], ['sites', 'Ресурсы'], ['roads', 'Дороги'], ['events', 'События']];
const layerOf = (point: WorldPoint): Layer => point.type === 'CAPITAL' || point.type === 'CLAN_CAPITAL' ? 'capitals'
  : point.resource ? 'sites' : point.type === 'EVENT_ZONE' || point.type === 'BOSS_LAIR' ? 'events' : 'points';

/** Human remaining time, e.g. «3 ч 12 мин»; null when the moment has passed or is unknown. */
export function remaining(until: string | null, now: number): string | null {
  if (!until) return null;
  const minutes = Math.ceil((Date.parse(until) - now) / 60000);
  if (!(minutes > 0)) return null;
  const days = Math.floor(minutes / 1440), hours = Math.floor((minutes % 1440) / 60), rest = minutes % 60;
  return days ? `${days} д ${hours} ч` : hours ? `${hours} ч ${rest} мин` : `${rest} мин`;
}
function centroid(polygon: [number, number][]): [number, number] {
  let area = 0, x = 0, z = 0;
  polygon.forEach(([x0, z0], i) => { const [x1, z1] = polygon[(i + 1) % polygon.length]!; const f = x0 * z1 - x1 * z0; area += f; x += (x0 + x1) * f; z += (z0 + z1) * f; });
  if (!area) return polygon[0]!;
  return [x / (3 * area), z / (3 * area)];
}
interface View { x: number; z: number; w: number }
function bounds(map: WorldMapResponse, points: WorldPoint[]) {
  const xs: number[] = [], zs: number[] = [];
  for (const region of map.regions) for (const [x, z] of region.polygon) { xs.push(x); zs.push(z); }
  for (const point of points) { xs.push(point.x); zs.push(point.z); }
  if (map.world.border_half) { xs.push(-map.world.border_half, map.world.border_half); zs.push(-map.world.border_half, map.world.border_half); }
  if (!xs.length) return { x: 0, z: 0, w: 2000, h: 2000 };
  const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs);
  const pad = Math.max(200, (maxX - minX + maxZ - minZ) * 0.08);
  return { x: (minX + maxX) / 2, z: (minZ + maxZ) / 2, w: maxX - minX + pad * 2, h: maxZ - minZ + pad * 2 };
}

function PointCard({ point, now, onClose }: { point: WorldPoint; now: number; onClose: () => void }) {
  const protection = point.state === 'PROTECTED' ? remaining(point.protection_until, now) : null;
  const lease = point.state === 'CONTROLLED' ? remaining(point.lease_until, now) : null;
  const refresh = point.resource ? remaining(point.resource.next_refresh, now) : null;
  return <div className="map-card" data-testid="map-point-card">
    <div className="map-card-head"><div><span className="map-card-type">{POINT_LABELS[point.type]}</span><h3>{point.name}</h3></div>
      <button type="button" className="icon-button" aria-label="Закрыть" onClick={onClose}><LauncherIcon name="x" size={15} /></button></div>
    <dl className="map-facts">
      {point.resource && <div><dt>Ресурс</dt><dd>{FAMILY_LABELS[point.resource.family]} · T{point.resource.tier}</dd></div>}
      {point.capturable && <div><dt>Владелец</dt><dd>{point.owner ? <span className="map-owner" style={{ color: clanColor(point.owner.short_code) }}>{point.owner.name}</span> : 'Ничья'}</dd></div>}
      {point.capturable && <div><dt>Состояние</dt><dd>{STATE_LABELS[point.state]}</dd></div>}
      {protection && <div><dt>Защита ещё</dt><dd>{protection}</dd></div>}
      {lease && <div><dt>Аренда ещё</dt><dd>{lease}</dd></div>}
      {point.event && <div><dt>Захват</dt><dd><span className="map-owner" style={{ color: clanColor(point.event.declared_by.short_code) }}>{point.event.declared_by.name}</span>
        {point.event.phase === 'PREPARING' ? `, бой в ${clock(point.event.active_start)}` : point.event.phase === 'OVERTIME' ? ', овертайм' : `, бой до ${clock(point.event.active_end)}`}</dd></div>}
      {point.resource && <div><dt>Восстановление</dt><dd>{refresh ? `через ${refresh}` : 'по расписанию'}</dd></div>}
      <div><dt>Координаты</dt><dd>X {point.x}, Z {point.z}</dd></div>
    </dl>
  </div>;
}

function EventFeed({ events, onSelect }: { events: WorldEvent[]; onSelect: (pointId: string) => void }) {
  if (!events.length) return null;
  return <div className="map-card map-feed" data-testid="map-feed"><h4>Последние события</h4>
    <ul>{events.slice(0, 8).map(event => <li key={event.id}>
      <time dateTime={event.at}>{clock(event.at)}</time>{' '}
      {event.clan && !['NEUTRALIZED', 'ADMIN_RESET'].includes(event.kind) && <span className="map-owner" style={{ color: clanColor(event.clan.short_code) }}>{event.clan.name} </span>}
      {EVENT_LABELS[event.kind]}{' '}<button type="button" className="link-button" onClick={() => onSelect(event.point.id)}>«{event.point.name}»</button>
    </li>)}</ul></div>;
}

/** Relief image placed by its own block bounds (they travel with the image, so a newer render is never misplaced). */
export interface ReliefImage extends Omit<WorldReliefBounds, 'revision'> { url: string }

/** Loads the relief once per revision announced by the map; failures just leave the schematic map. */
function useRelief(service: PublicApi, revision: string | null): ReliefImage | null {
  const [image, setImage] = useState<ReliefImage | null>(null);
  useEffect(() => {
    if (!revision) return;
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 30000);
    void service.read({ resource: 'world_relief' }, parseWorldRelief, controller.signal)
      .then(r => { if (!controller.signal.aborted) setImage({ url: `data:${r.mime};base64,${r.image_base64}`, x0: r.x0, z0: r.z0, width_blocks: r.width_blocks, height_blocks: r.height_blocks }); })
      .catch(() => undefined).finally(() => clearTimeout(timer));
    return () => { clearTimeout(timer); controller.abort(); };
  }, [service, revision]);
  return revision && image ? image : null;
}

export function WorldMapView({ map, points, events = [], now = Date.now(), relief = null }: { map: WorldMapResponse; points: WorldPoint[]; events?: WorldEvent[]; now?: number; relief?: ReliefImage | null }) {
  const home = useMemo(() => bounds(map, points), [map, points]);
  const [view, setView] = useState<View>({ x: home.x, z: home.z, w: Math.max(home.w, home.h) });
  const [layers, setLayers] = useState<Record<Layer, boolean>>({ relief: true, regions: true, capitals: true, points: true, sites: true, roads: true, events: true });
  const [selected, setSelected] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const drag = useRef<{ id: number; sx: number; sy: number; x: number; z: number; px: number } | null>(null);
  const svg = useRef<SVGSVGElement | null>(null);
  // Periodic refreshes return equal data; only reset the view when the world extent really changes.
  const homeKey = `${home.x}:${home.z}:${home.w}:${home.h}`;
  useEffect(() => { setView({ x: home.x, z: home.z, w: Math.max(home.w, home.h) }); }, [homeKey]);
  // Measured map size so that labels and markers keep a constant on-screen size at any zoom.
  const [size, setSize] = useState(600);
  useEffect(() => {
    const element = svg.current; if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) => { if (entry) setSize(Math.max(200, Math.min(entry.contentRect.width, entry.contentRect.height))); });
    observer.observe(element); return () => observer.disconnect();
  }, []);
  const unit = view.w / size; // one screen pixel, in blocks
  const matches = query.trim() ? points.filter(p => p.name.toLowerCase().includes(query.trim().toLowerCase())) : [];
  const chosen = points.find(p => p.id === selected) ?? null;
  const focus = (point: WorldPoint) => { setSelected(point.id); setView(v => ({ x: point.x, z: point.z, w: Math.min(v.w, Math.max(600, home.w / 4)) })); };
  const zoom = (factor: number, at?: { x: number; z: number }) => setView(v => {
    const w = Math.min(Math.max(v.w * factor, 200), Math.max(home.w, home.h) * 4), anchor = at ?? v;
    return { w, x: anchor.x + (v.x - anchor.x) * (w / v.w), z: anchor.z + (v.z - anchor.z) * (w / v.w) };
  });
  const toWorld = (clientX: number, clientY: number) => {
    const box = svg.current?.getBoundingClientRect();
    if (!box || !box.width) return { x: view.x, z: view.z };
    const scale = view.w / Math.min(box.width, box.height);
    return { x: view.x + (clientX - box.left - box.width / 2) * scale, z: view.z + (clientY - box.top - box.height / 2) * scale };
  };
  const onWheel = (event: ReactWheelEvent) => { zoom(event.deltaY > 0 ? 1.2 : 1 / 1.2, toWorld(event.clientX, event.clientY)); };
  const onDown = (event: ReactPointerEvent<SVGSVGElement>) => {
    if ((event.target as Element).closest('[data-point]')) return;
    const box = event.currentTarget.getBoundingClientRect();
    drag.current = { id: event.pointerId, sx: event.clientX, sy: event.clientY, x: view.x, z: view.z, px: view.w / Math.min(box.width, box.height) };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    const d = drag.current; if (!d || d.id !== event.pointerId) return;
    setView(v => ({ ...v, x: d.x - (event.clientX - d.sx) * d.px, z: d.z - (event.clientY - d.sy) * d.px }));
  };
  const onUp = () => { drag.current = null; };
  const grid = useMemo(() => {
    const step = view.w > 12000 ? 2000 : view.w > 4000 ? 1000 : view.w > 1500 ? 500 : 100, lines: number[] = [];
    for (let v = Math.floor((Math.min(view.x, view.z) - view.w) / step) * step; v <= Math.max(view.x, view.z) + view.w; v += step) lines.push(v);
    return { step, lines };
  }, [view]);
  const shown = points.filter(p => layers[layerOf(p)]);
  const half = view.w / 2;

  return <div className="world-layout">
    <section className="panel map-panel" aria-label="Карта мира">
      <div className="map-toolbar">
        <div className="map-layers" role="group" aria-label="Слои">{LAYERS.filter(([key]) => key !== 'relief' || relief).map(([key, label]) => <button key={key} type="button" className="chip-toggle" aria-pressed={layers[key]}
          onClick={() => setLayers(value => ({ ...value, [key]: !value[key] }))}>{label}</button>)}</div>
        <label className="map-search"><LauncherIcon name="search" size={15} /><input type="search" placeholder="Найти точку" aria-label="Найти точку" value={query} onChange={event => setQuery(event.target.value)} /></label>
      </div>
      {matches.length > 0 && <ul className="map-results" aria-label="Найденные точки">{matches.slice(0, 6).map(point =>
        <li key={point.id}><button type="button" onClick={() => { focus(point); setQuery(''); }}>{point.name}<span className="muted"> · {POINT_LABELS[point.type]}</span></button></li>)}</ul>}
      <div className="map-stage">
        <svg ref={svg} className={`world-svg${relief && layers.relief ? ' with-relief' : ''}`} data-testid="world-map" role="img" aria-label={`Схема мира «${map.world.display_name}»`}
          viewBox={`${view.x - half} ${view.z - half} ${view.w} ${view.w}`} preserveAspectRatio="xMidYMid meet"
          onWheel={onWheel} onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}>
          {relief && layers.relief && <image className="map-relief" data-testid="map-relief" href={relief.url} x={relief.x0} y={relief.z0}
            width={relief.width_blocks} height={relief.height_blocks} preserveAspectRatio="none" aria-hidden="true" />}
          <g className="map-grid" aria-hidden="true">{grid.lines.map(v => <g key={v}>
            <line x1={v} x2={v} y1={view.z - view.w} y2={view.z + view.w} /><line y1={v} y2={v} x1={view.x - view.w} x2={view.x + view.w} /></g>)}</g>
          {map.world.border_half && <rect className="map-border" x={-map.world.border_half} y={-map.world.border_half} width={map.world.border_half * 2} height={map.world.border_half * 2} />}
          {layers.regions && map.regions.map(region => {
            const hex = region.color ? `#${region.color}` : region.clan ? clanColor(region.clan.short_code) : '#8f8a73';
            const [cx] = centroid(region.polygon), top = Math.min(...region.polygon.map(([, z]) => z));
            return <g key={region.id} className="map-region" data-kind={region.kind}>
              <polygon points={region.polygon.map(([x, z]) => `${x},${z}`).join(' ')} style={{ fill: hex, stroke: hex }} />
              <text x={cx} y={top + unit * 14} fontSize={unit * 13} className="map-region-label">{region.clan?.name ?? REGION_KIND_LABELS[region.kind] ?? region.display_name}</text>
            </g>;
          })}
          {layers.roads && map.connections.map(c => <polyline key={c.id} className="map-road" data-kind={c.kind} points={c.polyline.map(([x, z]) => `${x},${z}`).join(' ')} style={{ strokeWidth: unit * 2.5 }} />)}
          {layers.events && map.markers.map(marker => <g key={marker.id} className="map-marker" transform={`translate(${marker.x} ${marker.z})`}>
            <path d={`M0 ${-6 * unit} L${5.5 * unit} ${4 * unit} L${-5.5 * unit} ${4 * unit}Z`} /><title>{marker.label}</title></g>)}
          {shown.map(point => {
            const owner = point.owner ? clanColor(point.owner.short_code) : null, r = (point.type === 'CAPITAL' ? 7 : point.type === 'CLAN_CAPITAL' ? 6 : point.resource ? 3.5 + point.resource.tier : 5) * unit;
            return <g key={point.id} data-point={point.type} className={`map-point${selected === point.id ? ' selected' : ''}${point.state === 'CONTESTED' || point.state === 'CAPTURING' ? ' contested' : ''}`}
              transform={`translate(${point.x} ${point.z})`} role="button" tabIndex={0} aria-label={`${point.name}, ${POINT_LABELS[point.type]}`}
              onClick={() => setSelected(point.id)} onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelected(point.id); } }}>
              {point.resource ? <circle r={r} className="map-site" style={owner ? { stroke: owner } : undefined} />
                : point.type === 'CAPITAL' || point.type === 'CLAN_CAPITAL' ? <circle r={r} className="map-capital" style={owner ? { stroke: owner } : undefined} />
                : <rect x={-r} y={-r} width={r * 2} height={r * 2} className="map-structure" style={owner ? { fill: owner } : undefined} />}
              <circle r={r * 2.2} className="map-hit" />
              {(point.type === 'CAPITAL' || selected === point.id) && <text y={-r - unit * 5} fontSize={unit * 12} className="map-point-label">{point.name}</text>}
            </g>;
          })}
        </svg>
        <div className="map-zoom" role="group" aria-label="Масштаб">
          <button type="button" className="icon-button" aria-label="Приблизить" onClick={() => zoom(1 / 1.5)}>+</button>
          <button type="button" className="icon-button" aria-label="Отдалить" onClick={() => zoom(1.5)}>−</button>
          <button type="button" className="icon-button" aria-label="Весь мир" onClick={() => setView({ x: home.x, z: home.z, w: Math.max(home.w, home.h) })}><LauncherIcon name="refresh" size={14} /></button>
        </div>
        <span className="map-scale" aria-hidden="true">сетка {grid.step} блоков · север сверху</span>
      </div>
    </section>
    <aside className="map-side">
      {chosen ? <PointCard point={chosen} now={now} onClose={() => setSelected(null)} />
        : <div className="map-card"><h3>{map.world.display_name}</h3><p className="muted">Выберите точку на карте, чтобы увидеть владельца, состояние и таймеры. Колесо мыши — масштаб, перетаскивание — перемещение.</p></div>}
      <div className="map-card map-legend">
        <h4>Государства</h4>
        <ul>{map.regions.filter(r => r.clan).map(r => <li key={r.id}><span className="legend-swatch" style={{ background: r.color ? `#${r.color}` : clanColor(r.clan!.short_code) }} />{r.clan!.name}</li>)}</ul>
        <h4>Обозначения</h4>
        <ul><li><span className="legend-capital" />Столица</li><li><span className="legend-structure" />Форт, застава, мост</li><li><span className="legend-site" />Ресурсная точка (размер — tier)</li></ul>
      </div>
      <EventFeed events={events} onSelect={id => { const point = points.find(p => p.id === id); if (point) focus(point); }} />
    </aside>
  </div>;
}

export function WorldMapPage({ service }: { service: PublicApi }) {
  const [revision, setRevision] = useState(0), [now, setNow] = useState(() => Date.now());
  useEffect(() => { const timer = setInterval(() => { setRevision(value => value + 1); setNow(Date.now()); }, 30000); return () => clearInterval(timer); }, []);
  const map = useResource(service, { resource: 'world_map' }, parseWorldMap, revision);
  const points = useResource(service, { resource: 'world_points' }, parseWorldPoints, revision);
  // The feed is optional: an older server without it still shows the map.
  const events = useResource(service, { resource: 'world_events' }, parseWorldEvents, revision);
  const [last, setLast] = useState<{ map: WorldMapResponse; points: WorldPointsResponse } | null>(null);
  const relief = useRelief(service, last?.map.relief?.revision ?? null);
  useEffect(() => { if (map.status === 'ready' && points.status === 'ready') setLast({ map: map.data, points: points.data }); }, [map, points]);
  const missing = (state: ResourceState<unknown>) => state.status === 'error' && state.message === 'Запись больше не доступна.';
  if (!last) {
    if (missing(map)) return <div className="empty-state" data-testid="map-unpublished">Карта мира ещё не опубликована.</div>;
    if (map.status === 'error' || points.status === 'error') return <StatusPanel tone="warning" title="Не удалось загрузить карту">{map.status === 'error' ? map.message : points.status === 'error' ? points.message : ''}</StatusPanel>;
    return <p role="status" className="muted">Загрузка карты…</p>;
  }
  return <WorldMapView map={last.map} points={last.points.items} events={events.status === 'ready' ? events.data.items : []} now={now} relief={relief} />;
}
