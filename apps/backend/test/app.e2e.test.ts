import { Test } from '@nestjs/testing';
import { FastifyAdapter, NestFastifyApplication } from '@nestjs/platform-fastify';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { DatabaseService } from '../src/infrastructure/database.service.js';
import { RateLimitStoreService } from '../src/infrastructure/rate-limit-store.service.js';

describe('public skeleton API', () => {
  let app: NestFastifyApplication;
  let databaseReady: boolean;
  let rateLimitsReady: boolean;
  beforeEach(async () => {
    databaseReady = true;
    rateLimitsReady = true;
    const module = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(DatabaseService).useValue({ client: { clan: { findMany: async () => [] } }, probe: async () => databaseReady })
      .overrideProvider(RateLimitStoreService).useValue({ probe: async () => rateLimitsReady })
      .compile();
    app = module.createNestApplication<NestFastifyApplication>(new FastifyAdapter({ logger: false }));
    await app.init(); await app.getHttpAdapter().getInstance().ready();
  });
  afterEach(async () => { await app.close(); });

  it('reports live and ready', async () => {
    expect((await app.inject({ method: 'GET', url: '/api/v1/health/live' })).statusCode).toBe(200);
    expect((await app.inject({ method: 'GET', url: '/api/v1/health/ready' })).json().status).toBe('ok');
  });
  it('returns an honest empty clan list', async () => {
    expect((await app.inject({ method: 'GET', url: '/api/v1/clans' })).json()).toEqual({ items: [], next_cursor: null });
  });
  it('keeps launcher unavailable', async () => {
    expect((await app.inject({ method: 'GET', url: '/api/v1/launcher/compatibility' })).json().launcher_status).toBe('UNAVAILABLE');
  });
  it.each(['database', 'rate_limits'])('returns 503 when %s is unavailable while staying live', async (dependency) => {
    if (dependency === 'database') databaseReady = false;
    else rateLimitsReady = false;
    const response = await app.inject({ method: 'GET', url: '/api/v1/health/ready' });
    expect(response.statusCode).toBe(503);
    expect(response.json().dependencies[dependency]).toBe('unavailable');
    expect((await app.inject({ method: 'GET', url: '/api/v1/health/live' })).statusCode).toBe(200);
  });
  it('exposes neither clan writes nor private integration routes on the public listener', async () => {
    for(const route of ['users','news','audit','servers'])expect((await app.inject({method:'GET',url:`/api/v1/management/${route}`})).statusCode).toBe(404);
    expect((await app.inject({ method: 'POST', url: '/api/v1/management/clans', payload: { name: 'UNAUTHORIZED' } })).statusCode).toBe(404);
    expect((await app.inject({ method: 'POST', url: '/api/v1/clans', payload: { name: 'UNAUTHORIZED' } })).statusCode).toBe(404);
    expect((await app.inject({ method: 'POST', url: '/integration/v1/admissions' })).statusCode).toBe(404);
  });
});
