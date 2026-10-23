import { Injectable, OnApplicationShutdown } from '@nestjs/common';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../generated/prisma/client.js';

@Injectable()
export class DatabaseService implements OnApplicationShutdown {
  readonly client: PrismaClient | null;

  constructor() {
    const url = process.env.DATABASE_URL;
    this.client = url ? new PrismaClient({ adapter: new PrismaPg({ connectionString: url, connectionTimeoutMillis: 2000, query_timeout: 2000 }) }) : null;
  }

  async probe(): Promise<boolean> {
    if (!this.client) return false;
    try {
      await this.client.$queryRaw`SELECT "id" FROM "clans" LIMIT 1`;
      return true;
    } catch {
      return false;
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client?.$disconnect();
  }
}
