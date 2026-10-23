import { Body, Controller, Delete, Get, Headers, Inject, Module, Param, ParseUUIDPipe, Post, Put, Req, ValidationPipe } from '@nestjs/common';
import type { FastifyRequest } from 'fastify';
import { ClanInput } from './clan-input.js';
import { DevelopmentService } from './development.service.js';
const clanBody = new ValidationPipe({ expectedType: ClanInput, transform: true, whitelist: true, forbidNonWhitelisted: true });

@Controller('development/v1/clans')
class DevelopmentController {
  constructor(@Inject(DevelopmentService) private readonly service: DevelopmentService) {}
  @Get() list() { return this.service.list(); }
  @Get(':id') get(@Param('id', new ParseUUIDPipe()) id: string) { return this.service.get(id); }
  @Post() create(@Body(clanBody) input: ClanInput, @Req() request: FastifyRequest) { return this.service.create(input, request.id); }
  @Put(':id') update(@Param('id', new ParseUUIDPipe()) id: string, @Body(clanBody) input: ClanInput,
    @Headers('if-match') version: string | undefined, @Req() request: FastifyRequest) {
    return this.service.change(id, input, version, request.id);
  }
  @Delete(':id') archive(@Param('id', new ParseUUIDPipe()) id: string,
    @Headers('if-match') version: string | undefined, @Req() request: FastifyRequest) {
    return this.service.change(id, null, version, request.id);
  }
}
@Module({ controllers: [DevelopmentController], providers: [DevelopmentService] })
export class DevelopmentModule {}
