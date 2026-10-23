import 'reflect-metadata';
import { Test } from '@nestjs/testing';
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DatabaseService } from '../src/infrastructure/database.service.js';
import { RateLimitStoreService } from '../src/infrastructure/rate-limit-store.service.js';
import { IntegrationAppModule } from '../src/integration-app.module.js';

describe('integration region catalog', () => {
  let app: NestFastifyApplication;
  const key = 'b'.repeat(64), server = '11111111-1111-4111-8111-111111111111', url = '/integration/v1/world/regions';
  const query = vi.fn();
  const region = { code: 'state.scam', kind: 'STATE', clan_code: 'SCAM', color: 'E84A5F', pvp_policy: 'WAR_ONLY', build_policy: 'MEMBERS', polygon: [[0, 0], [64, 0], [64, 64]], spawn_x: 10, spawn_z: 20 };
  beforeEach(async () => {
    vi.stubEnv('AOC_GAME_SERVER_KEY', key); vi.stubEnv('AOC_GAME_SERVER_ID', server);
    query.mockReset();
    const module = await Test.createTestingModule({ imports: [IntegrationAppModule] })
      .overrideProvider(DatabaseService).useValue({ client: { $queryRaw: query } })
      .overrideProvider(RateLimitStoreService).useValue({}).compile();
    app = module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({ logger: false }));
    await app.init(); await app.getHttpAdapter().getInstance().ready();
  });
  afterEach(async () => { await app.close(); vi.unstubAllEnvs(); });
  const headers = { authorization: `Bearer ${key}` };

  it('requires the service credential and the server identity', async () => {
    expect((await app.inject({ method: 'POST', url, payload: { server_id: server } })).statusCode).toBe(401);
    expect((await app.inject({ method: 'POST', url, headers, payload: { server_id: '22222222-2222-4222-8222-222222222222' } })).statusCode).toBe(403);
    expect(query).not.toHaveBeenCalled();
  });

  it('returns the active regions with a revision and answers unchanged for a known revision', async () => {
    query.mockResolvedValueOnce([{ world_id: 'states-20261020' }]).mockResolvedValueOnce([region]);
    const first = await app.inject({ method: 'POST', url, headers, payload: { server_id: server } });
    expect(first.statusCode).toBe(200);
    const body = first.json() as { revision: string; regions: Array<Record<string, unknown>> };
    expect(body.regions).toEqual([{ code: 'state.scam', kind: 'STATE', clan_code: 'SCAM', color: 'E84A5F', pvp_policy: 'WAR_ONLY', build_policy: 'MEMBERS', polygon: region.polygon, spawn: { x: 10, z: 20 } }]);
    expect(body.revision).toMatch(/^[0-9a-f]{64}$/);
    query.mockResolvedValueOnce([{ world_id: 'states-20261020' }]).mockResolvedValueOnce([region]);
    const again = await app.inject({ method: 'POST', url, headers, payload: { server_id: server, known_revision: body.revision } });
    expect(again.json()).toEqual({ unchanged: true, revision: body.revision });
  });

  it('is 404 without an active world and 503 without leaking storage errors', async () => {
    query.mockResolvedValueOnce([]);
    expect((await app.inject({ method: 'POST', url, headers, payload: { server_id: server } })).statusCode).toBe(404);
    query.mockRejectedValueOnce(new Error('connection refused at 10.0.0.5'));
    const down = await app.inject({ method: 'POST', url, headers, payload: { server_id: server } });
    expect(down.statusCode).toBe(503);
    expect(down.body).not.toContain('10.0.0.5');
  });
});
