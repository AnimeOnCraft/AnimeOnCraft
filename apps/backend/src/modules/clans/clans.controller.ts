import { Controller, Get, Inject, Param, ParseUUIDPipe, Query, ValidationPipe } from '@nestjs/common';
import { PageInput } from '../../infrastructure/pagination.js';
import { ClansService } from './clans.service.js';
@Controller('api/v1/clans')
export class ClansController {
  constructor(@Inject(ClansService) private readonly clans: ClansService) {}
  @Get() list(@Query(new ValidationPipe({ expectedType: PageInput, transform: true, whitelist: true, forbidNonWhitelisted: true })) query: PageInput) { return this.clans.list(query); }
  @Get(':id') detail(@Param('id', new ParseUUIDPipe()) id: string) { return this.clans.detail(id); }
  @Get(':id/progression') progression(@Param('id', new ParseUUIDPipe()) id: string) { return this.clans.progression(id); }
  @Get(':id/live') live(@Param('id', new ParseUUIDPipe()) id: string) { return this.clans.live(id); }
  @Get(':id/events') events(@Param('id', new ParseUUIDPipe()) id: string) { return this.clans.events(id); }
  @Get(':id/competitions') competitions(@Param('id', new ParseUUIDPipe()) id: string) { return this.clans.competitions(id); }
  @Get(':id/wars') wars(@Param('id', new ParseUUIDPipe()) id: string) { return this.clans.wars(id); }
}
