import { Body, Controller, Get, HttpCode, Inject, Post, Req, UseInterceptors } from '@nestjs/common';
import { IsString, Matches } from 'class-validator';
import type { FastifyRequest } from 'fastify';
import { AuthHttp, input } from './auth-http.js';
import { AuthNoStore } from './auth-no-store.js';
import { GAME_NAME_PATTERN, GameProfileService } from './game-profile.service.js';

class GameProfileInput {
  @IsString() @Matches(GAME_NAME_PATTERN) game_name!: string;
}

@Controller('api/v1/auth/me/game-profile')
@UseInterceptors(AuthNoStore)
export class GameProfileController {
  constructor(@Inject(AuthHttp) private readonly http: AuthHttp, @Inject(GameProfileService) private readonly profiles: GameProfileService) {}

  @Get()
  async read(@Req() request: FastifyRequest) {
    return this.profiles.read(await this.http.principal(request));
  }

  @Post() @HttpCode(200)
  async create(@Req() request: FastifyRequest, @Body() body: unknown) {
    await this.http.unsafe(request);
    const data = input(GameProfileInput, body);
    return this.profiles.create(await this.http.principal(request), data.game_name, request.id);
  }
}
