import { Controller, Get, Inject } from '@nestjs/common';
import { WorldService } from './world.service.js';
/** Public, read-only world map for the launcher. No technical codes or unactivated slots are exposed. */
@Controller('api/v1/world')
export class WorldController {
  constructor(@Inject(WorldService) private readonly world: WorldService) {}
  @Get('map') map() { return this.world.map(); }
  @Get('points') points() { return this.world.points(); }
  @Get('events') events() { return this.world.events(); }
  /** Relief underlay image of the active world as base64 JSON (the launcher renders it as a data: URL). */
  @Get('relief') relief() { return this.world.relief(); }
}
