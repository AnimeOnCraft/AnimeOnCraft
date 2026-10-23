import { Inject, Injectable, NotFoundException, ServiceUnavailableException } from '@nestjs/common';
import type { Prisma } from '../../generated/prisma/client.js';
import { DatabaseService } from '../../infrastructure/database.service.js';
import { readWorldEvents, readWorldMap, readWorldPoints } from '../../world-catalog/catalog-store.js';
import { readWorldRelief } from '../../world-catalog/relief.js';

@Injectable()
export class WorldService {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService) {}
  private async read<T>(reader: (db: Prisma.TransactionClient) => Promise<T | null>): Promise<T> {
    const client = this.database.client;
    if (!client) throw new ServiceUnavailableException('Database unavailable');
    let result: T | null;
    try { result = await reader(client); } catch { throw new ServiceUnavailableException('Database unavailable'); }
    if (result === null) throw new NotFoundException('No world map is published');
    return result;
  }
  map() { return this.read(readWorldMap); }
  points() { return this.read(readWorldPoints); }
  events() { return this.read(readWorldEvents); }
  relief() { return this.read(readWorldRelief); }
}
