import { Inject, Injectable } from '@nestjs/common';
import type { ReadyResponse } from '@animeoncraft/contracts';
import { DatabaseService } from '../../infrastructure/database.service.js';
import { RateLimitStoreService } from '../../infrastructure/rate-limit-store.service.js';

@Injectable()
export class HealthService {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService, @Inject(RateLimitStoreService) private readonly rateLimits: RateLimitStoreService) {}

  live() {
    return { status: 'ok' as const, service: 'animeoncraft-backend' as const, version: '0.6.0-admin-dev' };
  }

  async ready(): Promise<ReadyResponse> {
    const [database, rateLimits] = await Promise.all([this.database.probe(), this.rateLimits.probe()]);
    return {
      status: database && rateLimits ? 'ok' : 'unavailable',
      dependencies: { database: database ? 'ok' : 'unavailable', rate_limits: rateLimits ? 'ok' : 'unavailable' }
    };
  }
}
