import { describe, expect, it, vi } from 'vitest';
import { PublicApi, PublicApiError, parseArticle, parseClanProgression, parseClanCompetitions, parseClanWars, parseClans, parseCompatibility, parseReady, publicPath, safeDiscord } from './public-api.js';
import { LauncherApp } from './App.js';
import { readRoute } from './routes.js';
import { renderToStaticMarkup } from 'react-dom/server';
import { createElement } from 'react';
const clan = { id: '11111111-1111-4111-8111-111111111111', name: 'Akuma', short_code: 'AKUMA', description: '', logo_url: null, discord_url: null, member_count: 0, online_count: null, online_updated_at: null };
describe('launcher public boundary', () => {
  it('preserves real zero members and unknown online', () => {
    const value = parseClans({ items: [clan], next_cursor: null });
    expect(value.items[0]?.member_count).toBe(0); expect(value.items[0]?.online_count).toBeNull();
  });
  it('rejects malformed counters, missing fields and oversized lists', () => {
    expect(() => parseClans({ items: [{ ...clan, member_count: -1 }], next_cursor: null })).toThrow(PublicApiError);
    expect(() => parseClans({ items: [clan] })).toThrow(PublicApiError);
    expect(() => parseClans({ items: Array(101).fill(clan), next_cursor: null })).toThrow(PublicApiError);
  });
  it('does not accept future compatibility as permission to PLAY', () => {
    expect(() => parseCompatibility({ public_api_major: 1, integration_protocol_major: 1, launcher_status: 'AVAILABLE', reason: 'OK' })).toThrow(PublicApiError);
  });
  it('reads degraded readiness rather than reporting successful game status', async () => {
    const api = new PublicApi(async () => ({ status: 503, body: { status: 'degraded', dependencies: { database: 'unavailable', rate_limits: 'ok' } } }));
    expect((await api.read({ resource: 'ready' }, parseReady, new AbortController().signal)).status).toBe('degraded');
  });
  it('distinguishes vanished content and rejects stale cancelled replies', async () => {
    const api = new PublicApi(async () => ({ status: 404, body: null }));
    await expect(api.read({ resource: 'article', key: 'first-news' }, parseArticle, new AbortController().signal)).rejects.toMatchObject({ code: 'NOT_FOUND' });
    const controller = new AbortController(); controller.abort();
    await expect(new PublicApi(async () => ({ status: 200, body: { items: [], next_cursor: null } })).read({ resource: 'clans' }, parseClans, controller.signal)).rejects.toThrow(PublicApiError);
  });
  it('bounds detail routes and encodes opaque cursors', () => {
    expect(publicPath({ resource: 'news', cursor: 'a&redirect=https://evil' })).toBe('/api/v1/news?limit=20&cursor=a%26redirect%3Dhttps%3A%2F%2Fevil');
    for (const key of ['../auth/me', 'first-news?token=x', 'https://example.org']) expect(() => publicPath({ resource: 'article', key })).toThrow(PublicApiError);
    expect(() => publicPath({ resource: 'ready', cursor: 'x' })).toThrow(PublicApiError);
    expect(() => publicPath({ resource: 'clan', key: 'fake' })).toThrow(PublicApiError);
  });
  it('reads progression through a bounded clan route and rejects corrupt achievements', () => {
    expect(publicPath({resource:'clan_progression',key:clan.id})).toBe(`/api/v1/clans/${clan.id}/progression`);
    expect(() => publicPath({resource:'clan_progression',key:'../auth'})).toThrow(PublicApiError);
    const row={clan_id:clan.id,xp:200,level:2,reputation:10,statistics:{BOSS_KILL:1},achievements:[{code:'FIRST_BOSS',progress:1,target:1,unlocked_at:'2026-10-18T00:00:00Z'}]};
    expect(parseClanProgression(row).kills).toBe(1);
    expect(() => parseClanProgression({...row,xp:-1})).toThrow(PublicApiError);
    expect(() => parseClanProgression({...row,achievements:[{...row.achievements[0],target:0}]})).toThrow(PublicApiError);
  });
  it('accepts real competition standings and war schedules without accepting unknown states',()=>{
    const competition={event_id:clan.id,title:'Охота',status:'ACTIVE',score:0,starts_at:'2026-10-18T00:00:00Z',ends_at:'2026-10-18T01:00:00Z',reward_granted_at:null,leaderboard:[{short_code:'SCAM',score:3}]};
    expect(parseClanCompetitions({items:[competition]}).items[0]?.leaderboard[0]?.score).toBe(3);
    const worldBoss={...competition,kind:'WORLD_BOSS',arena:{x:-100,y:70,z:200},outcome:null,score:12500};
    expect(parseClanCompetitions({items:[worldBoss]}).items[0]).toMatchObject({kind:'WORLD_BOSS',score:12500,arena:{x:-100,y:70,z:200}});
    expect(()=>parseClanCompetitions({items:[{...worldBoss,arena:{x:0.5,y:70,z:0}}]})).toThrow(PublicApiError);
    expect(()=>parseClanCompetitions({items:[{...competition,status:'UNKNOWN'}]})).toThrow(PublicApiError);
    const war={id:clan.id,attacker:'SCAM',defender:'NYA',status:'PREPARATION',starts_at:competition.starts_at,ends_at:competition.ends_at,cooldown_ends_at:null};
    expect(parseClanWars({items:[war]}).items[0]?.attacker).toBe('SCAM');
    expect(()=>parseClanWars({items:[{...war,starts_at:'bad'}]})).toThrow(PublicApiError);
    for(const resource of ['clan_competitions','clan_wars'] as const){
      expect(()=>publicPath({resource,key:'../auth'})).toThrow(PublicApiError);
      expect(()=>publicPath({resource,key:clan.id,cursor:'unexpected'})).toThrow(PublicApiError);
    }
  });
  it('does not turn backend links into unsafe navigation', () => {
    expect(safeDiscord('https://discord.gg/akuma')).toBe('https://discord.gg/akuma');
    for (const url of ['javascript:alert(1)', 'http://discord.gg/a', 'https://discord.gg.evil.example/a', 'https://user:password@discord.gg/a']) expect(safeDiscord(url)).toBeNull();
  });
  it('keeps news text as data and bounds the article body', () => {
    const row = { id: clan.id, slug: 'first-news', title: 'News', version: '1', published_at: '2026-10-11T00:00:00.000Z', body_markdown: '<script>alert(1)</script>' };
    expect(parseArticle(row).body_markdown).toBe(row.body_markdown);
    expect(() => parseArticle({ ...row, body_markdown: 'x'.repeat(20001) })).toThrow(PublicApiError);
  });
  it('bounds routes from browser fragments', () => {
    expect(readRoute('#settings')).toEqual({ page: 'settings' });
    expect(readRoute('#news/first-news')).toEqual({ page: 'news', key: 'first-news' });
    expect(readRoute('#news/../auth/me')).toEqual({ page: 'news' });
    expect(readRoute('#unknown')).toEqual({ page: 'home' });
  });
  it('renders disabled PLAY and unknown online before service replies', () => {
    const transport = vi.fn(); const html = renderToStaticMarkup(createElement(LauncherApp, { service: new PublicApi(transport) }));
    expect(html).toContain('Играть'); expect(html).toContain('Запуск доступен в Windows-приложении.'); expect(html).toContain('data-testid="play"'); expect(html).toContain('disabled=""');
    expect(html).toContain('Статус неизвестен'); expect(html).toContain('Неизвестно'); expect(html).not.toContain('localStorage'); expect(transport).not.toHaveBeenCalled();
  });
});
