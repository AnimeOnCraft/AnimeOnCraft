import { Body, Controller, Headers, HttpCode, HttpException, HttpStatus, Inject, Post } from '@nestjs/common';
import { DatabaseService } from '../../infrastructure/database.service.js';
import { authenticateServer } from '../../integration-auth.js';
import { readServerRegions } from '../../world-catalog/server-regions.js';

/** Game server → backend (service credential): region catalog for protection in a states world. */
@Controller('integration/v1/world')
export class WorldIntegrationController {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService) {}

  @Post('regions') @HttpCode(200) async regions(@Headers('authorization') authorization: string | undefined, @Body() body: unknown) {
    authenticateServer(authorization, body);
    const client = this.database.client;
    if (!client) throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE);
    let catalog;
    try { catalog = await readServerRegions(client); } catch { throw new HttpException('Database unavailable', HttpStatus.SERVICE_UNAVAILABLE); }
    if (!catalog) throw new HttpException('No active world', HttpStatus.NOT_FOUND);
    const known = (body as { known_revision?: unknown }).known_revision;
    if (typeof known === 'string' && known === catalog.revision) return { unchanged: true, revision: catalog.revision };
    return catalog;
  }
}
