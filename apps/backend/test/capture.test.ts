import 'reflect-metadata';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { Test } from '@nestjs/testing';
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify';
import { parse } from 'smol-toml';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { derivedId } from '../src/capture/lifecycle.js';
import { captureReward, influenceCapacity, influenceWeight, inWindow, localMinutes, parseRules, rankMultiplier, RulesError, serverRules } from '../src/capture/rules.js';
import { DatabaseService } from '../src/infrastructure/database.service.js';
import { RateLimitStoreService } from '../src/infrastructure/rate-limit-store.service.js';
import { IntegrationAppModule } from '../src/integration-app.module.js';
import { loadCatalog } from '../src/world-catalog/load.js';
import {buildCatalog,CatalogError} from '../src/world-catalog/catalog.js';

const rulesPath = new URL('../../../minecraft/server/world-config/capture-rules.toml', import.meta.url);
const raw = async () => parse(await readFile(rulesPath, 'utf8')) as Record<string, unknown>;

describe('capture rules', () => {
  it('parses the shipped rules with the design numbers', async () => {
    const rules = parseRules(await raw());
    expect(rules.timezone).toBe('Asia/Yekaterinburg');
    expect([rules.timing.preparation_seconds, rules.timing.battle_seconds, rules.timing.protection_seconds, rules.timing.lease_seconds]).toEqual([300, 900, 21600, 86400]);
    expect(rules.score.exponent).toBe(0.7);
    expect(rules.influence.tier_weights).toEqual([1, 2, 3, 4]);
    expect(Object.keys(rules.families).sort()).toEqual(['AGRARIAN', 'ALCHEMY', 'ARCANE', 'FERROUS', 'MARINE', 'NONFERROUS', 'PRECIOUS', 'TECH', 'TIMBER']);
    expect(Object.keys(serverRules(rules))).toEqual(['timezone', 'window', 'declare', 'timing', 'presence', 'score', 'yield', 'families', 'caches']);
    expect(JSON.stringify(serverRules(rules)).length).toBeLessThan(8000);
  });
  it('rejects unknown keys, wrong ranges and inconsistent timing', async () => {
    const base = await raw();
    // Mutations of the parsed TOML tree; loosely typed on purpose.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    type Mutable = Record<string, any>;
    const variants: Array<(r: Mutable) => void> = [
      r => { r.timing.lease_seconds = 60; },
      r => { r.score.exponent = 2; },
      r => { r.window.daily = [['15:00', '15:00']]; },
      r => { r.declare.roles = ['OWNER']; },
      r => { r.families.FERROUS.seed = [['minecraft:diamond_ore', 1]]; },
      r => { r.timing.surprise = 1; },
      r => { r.timezone = 'Mars/Olympus'; },
      r => { r.influence.max_capacity = 1; },
    ];
    for (const mutate of variants) { const copy = structuredClone(base) as Mutable; mutate(copy); expect(() => parseRules(copy)).toThrow(RulesError); }
  });
  it('checks declaration windows in Yekaterinburg time, including ranges past midnight', async () => {
    const rules = parseRules(await raw());
    expect(localMinutes(rules, new Date('2026-10-20T10:00:00Z'))).toBe(15 * 60);
    expect(inWindow(rules, new Date('2026-10-20T09:59:00Z'))).toBe(false);
    expect(inWindow(rules, new Date('2026-10-20T10:00:00Z'))).toBe(true);
    expect(inWindow(rules, new Date('2026-10-20T18:29:00Z'))).toBe(true);
    expect(inWindow(rules, new Date('2026-10-20T18:30:00Z'))).toBe(false);
    const night = { ...rules, window: { daily: [['23:00', '02:00']] as Array<[string, string]> } };
    expect(inWindow(night, new Date('2026-10-20T18:30:00Z'))).toBe(true);
    expect(inWindow(night, new Date('2026-10-20T20:59:00Z'))).toBe(true);
    expect(inWindow(night, new Date('2026-10-20T21:00:00Z'))).toBe(false);
  });
  it('computes influence, yield rank and rewards', async () => {
    const rules = parseRules(await raw());
    expect([influenceWeight(rules, null), influenceWeight(rules, 1), influenceWeight(rules, 4)]).toEqual([1, 1, 4]);
    expect([influenceCapacity(rules, 1), influenceCapacity(rules, 25), influenceCapacity(rules, 74), influenceCapacity(rules, 100)]).toEqual([8, 9, 10, 12]);
    expect([0, 1, 2, 3, 4, 5, 9].map(rank => rankMultiplier(rules, rank, false))).toEqual([1, 1, 0.85, 0.7, 0.55, 0.4, 0.4]);
    expect(rankMultiplier(rules, 0, true)).toBe(0.5);
    expect(captureReward(rules, 4)).toEqual({ xp: 500, reputation: 20 });
    expect(captureReward(rules, null)).toEqual({ xp: 200, reputation: 10 });
    expect(derivedId('f5333466-87a2-4ac7-bacd-6c405828b662', 'defended')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
  it('imports the states world with its rules and all capturable objects outside the states', async () => {
    const catalog = await loadCatalog(fileURLToPath(new URL('../../../minecraft/server/world-config/world-states.toml', import.meta.url)));
    expect(catalog.capture_rules?.format_version).toBe(1);
    expect(catalog.points.filter(p => p.capturable)).toHaveLength(57);
    expect(catalog.points.filter(p => p.resource)).toHaveLength(28);
    expect(catalog.points.filter(p => p.capturable).every(p=>Number.isInteger(p.y))).toBe(true);
    expect(catalog.points.filter(p => p.resource).every(p=>p.resource?.yield.version===2)).toBe(true);
    expect(catalog.points.find(p=>p.code==='site.ring.252')?.resource?.kind).toBe('CACHE');
    expect(catalog.points.find(p=>p.code==='site.rim.108')?.resource?.yield.seed).toContainEqual(['irons_spellbooks:mithril_ore',1]);
    expect(catalog.regions.filter(r => r.kind === 'STATE').map(r => r.clan_code).sort()).toEqual(['AKUMA', 'NYA', 'SCAM', 'YAKUZA', 'ZXC']);
  });
  it('rejects broken per-site output rather than importing a misleading empty resource point',async()=>{
    const source=parse(await readFile(new URL('../../../minecraft/server/world-config/world-states.toml',import.meta.url),'utf8'));
    const rules=await raw();
    for(const output of [
      {version:2,label:'Test',placement:'UNDERGROUND',blocks:['minecraft:iron_ore'],seed:[['minecraft:diamond_ore',1]]},
      {version:2,label:'Test',placement:'UNDERGROUND',blocks:['minecraft:iron_ore'],seed:[['minecraft:iron_ore',0]]},
      {version:2,label:'Test',placement:'CACHE',items:[['minecraft:cod',24]]},
      {version:2,label:'Test',placement:'UNDERGROUND',blocks:['minecraft:iron_ore'],seed:[['minecraft:iron_ore',1]],limit:9999},
    ]){
      const copy=structuredClone(source);
      const rows=copy.points as Array<Record<string,unknown>>;
      const point=rows.find(p=>p.code==='site.ring.108')!;
      (point.resource as Record<string,unknown>).yield=output;
      expect(()=>buildCatalog(copy,undefined,rules)).toThrow(CatalogError);
    }
  });
});

describe('capture integration routes', () => {
  let app: NestFastifyApplication;
  const key = 'b'.repeat(64), server = '11111111-1111-4111-8111-111111111111';
  const transaction = vi.fn();
  beforeEach(async () => {
    vi.stubEnv('AOC_GAME_SERVER_KEY', key); vi.stubEnv('AOC_GAME_SERVER_ID', server);
    transaction.mockReset();
    const module = await Test.createTestingModule({ imports: [IntegrationAppModule] })
      .overrideProvider(DatabaseService).useValue({ client: { $transaction: transaction } })
      .overrideProvider(RateLimitStoreService).useValue({}).compile();
    app = module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({ logger: false }));
    await app.init(); await app.getHttpAdapter().getInstance().ready();
  });
  afterEach(async () => { await app.close(); vi.unstubAllEnvs(); });
  it('requires the service credential and the configured server identity on every route', async () => {
    for (const route of ['sync', 'declare', 'snapshot', 'finish', 'void', 'admin', 'refreshed']) {
      const url = `/integration/v1/capture/${route}`;
      expect((await app.inject({ method: 'POST', url, payload: { server_id: server } })).statusCode).toBe(401);
      expect((await app.inject({ method: 'POST', url, headers: { authorization: `Bearer ${'c'.repeat(64)}` }, payload: { server_id: server } })).statusCode).toBe(401);
      expect((await app.inject({ method: 'POST', url, headers: { authorization: `Bearer ${key}` }, payload: { server_id: '22222222-2222-4222-8222-222222222222' } })).statusCode).toBe(403);
    }
    expect(transaction).not.toHaveBeenCalled();
  });
  it('turns engine conflicts into 409 and storage failures into 503 without leaking details', async () => {
    const headers = { authorization: `Bearer ${key}` };
    transaction.mockImplementation(async (work: (tx: unknown) => Promise<unknown>) => work({}));
    const conflict = await app.inject({ method: 'POST', url: '/integration/v1/capture/admin', headers, payload: { server_id: server, action: 'EXPLODE' } });
    expect(conflict.statusCode).toBe(409);
    const invalid = await app.inject({ method: 'POST', url: '/integration/v1/capture/declare', headers, payload: { server_id: server, point_code: 'BAD CODE' } });
    expect(invalid.statusCode).toBe(409);
    expect((await app.inject({method:'POST',url:'/integration/v1/capture/declare',headers,payload:{server_id:server,auto_neutral:'true'}})).statusCode).toBe(409);
    transaction.mockRejectedValue(new Error('connection refused at 10.0.0.5'));
    const down = await app.inject({ method: 'POST', url: '/integration/v1/capture/sync', headers, payload: { server_id: server } });
    expect(down.statusCode).toBe(503);
    expect(down.body).not.toContain('10.0.0.5');
  });
});
