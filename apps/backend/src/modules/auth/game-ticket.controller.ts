import { BadRequestException, Body, Controller, HttpCode, Inject, Post, Req, UseInterceptors } from '@nestjs/common';
import { IsString, IsUUID, Matches, MaxLength, ValidateIf } from 'class-validator';
import type { FastifyRequest } from 'fastify';
import { AuthHttp, input } from './auth-http.js';
import { AuthNoStore } from './auth-no-store.js';
import { GAME_NAME_PATTERN } from './game-profile.service.js';
import { GAME_TICKET_PATTERN, GameTicketService } from './game-ticket.service.js';
import { LAUNCHER_VERSION } from './launcher-release.js';

class IssueInput {
  @ValidateIf(value=>value.launcher_version!==undefined) @IsString() @MaxLength(20) @Matches(LAUNCHER_VERSION) launcher_version?:string;
}

class RedeemInput {
  @IsString() @Matches(GAME_TICKET_PATTERN) ticket!: string;
  @IsString() @Matches(GAME_NAME_PATTERN) game_name!: string;
  @IsUUID('3') minecraft_uuid!: string;
  @IsUUID('4') connection_id!: string;
}
class JoinedInput {
  @IsUUID('4') connection_id!:string;
  @IsUUID('3') minecraft_uuid!:string;
}

@Controller('api/v1/game')
@UseInterceptors(AuthNoStore)
export class GameTicketController {
  constructor(@Inject(AuthHttp) private readonly http: AuthHttp, @Inject(GameTicketService) private readonly tickets: GameTicketService) {}

  @Post('tickets') @HttpCode(200)
  async issue(@Req() request: FastifyRequest, @Body() body: unknown) {
    await this.http.unsafe(request);const data=input(IssueInput,body??{});
    const header=input(IssueInput,{launcher_version:request.headers['x-aoc-launcher-version']});
    if(header.launcher_version&&data.launcher_version&&header.launcher_version!==data.launcher_version)throw new BadRequestException('Conflicting launcher version');
    return this.tickets.issue(await this.http.principal(request),header.launcher_version??data.launcher_version);
  }

  @Post('admission/redeem') @HttpCode(200)
  async redeem(@Req() request: FastifyRequest, @Body() body: unknown) {
    const server = this.tickets.authenticateServer(request);
    const data = input(RedeemInput, body);
    return this.tickets.redeem(server, data);
  }
  @Post('admission/joined') @HttpCode(200)
  async joined(@Req() request:FastifyRequest,@Body() body:unknown){
    return this.tickets.joined(this.tickets.authenticateServer(request),input(JoinedInput,body));
  }
}
