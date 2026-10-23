import { HttpException, Injectable, OnApplicationShutdown, ServiceUnavailableException } from '@nestjs/common';
import { Redis } from 'ioredis';

@Injectable()
export class RateLimitStoreService implements OnApplicationShutdown {
  private readonly client: Redis | null;

  constructor() {
    const url = process.env.VALKEY_URL;
    this.client = url ? new Redis(url, { lazyConnect: true, enableOfflineQueue: false, maxRetriesPerRequest: 0, connectTimeout: 2000, commandTimeout: 2000, retryStrategy: () => null }) : null;
    this.client?.on('error', () => { /* Dependency failure is reported by readiness without logging credentials. */ });
  }

  async probe(): Promise<boolean> {
    if (!this.client) return false;
    try {
      if (this.client.status === 'wait' || this.client.status === 'end') await this.client.connect();
      return (await this.client.ping()) === 'PONG';
    } catch {
      return false;
    }
  }

  async consume(budgets: { key: string; limit: number; seconds: number }[]): Promise<void> {
    if (!this.client || !(await this.probe())) throw new ServiceUnavailableException();
    const script = `
      for i,key in ipairs(KEYS) do
        if tonumber(redis.call('GET',key) or '0') >= tonumber(ARGV[i*2-1]) then return 0 end
      end
      for i,key in ipairs(KEYS) do
        local count = redis.call('INCR',key)
        if count == 1 then redis.call('EXPIRE',key,ARGV[i*2]) end
      end
      return 1`;
    let result: unknown;
    try { result = await this.client.eval(script, budgets.length, ...budgets.map(b => b.key), ...budgets.flatMap(b => [b.limit, b.seconds])); }
    catch { throw new ServiceUnavailableException(); }
    if (result !== 1) throw new HttpException({ message: 'Too many attempts', retry_after: Math.max(...budgets.map(b => b.seconds)) }, 429);
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.client && this.client.status !== 'end') this.client.disconnect(false);
  }
}
