import { Controller, Get, Inject, Res } from '@nestjs/common';
import type { FastifyReply } from 'fastify';
import { HealthService } from './health.service.js';

@Controller('api/v1/health')
export class HealthController {
  constructor(@Inject(HealthService) private readonly health: HealthService) {}

  @Get('live') live() { return this.health.live(); }

  @Get('ready')
  async ready(@Res({ passthrough: true }) reply: FastifyReply) {
    const response = await this.health.ready();
    if (response.status !== 'ok') reply.status(503);
    return response;
  }
}
