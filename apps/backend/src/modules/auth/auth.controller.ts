import { Body, Controller, Get, HttpCode, Inject, Param, ParseUUIDPipe, Post, Req, Res, UseInterceptors } from '@nestjs/common';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { AuthService } from './auth.service.js';
import { AuthHttp, emptyInput, input } from './auth-http.js';
import { LoginInput, RegistrationInput, MfaLoginInput, PasswordChangeInput, PasswordInput, ProofInput, TokenInput } from './auth-input.js';
import { AuthNoStore } from './auth-no-store.js';

@Controller('api/v1/auth')
@UseInterceptors(AuthNoStore)
export class AuthController {
  constructor(@Inject(AuthService) private readonly auth: AuthService, @Inject(AuthHttp) private readonly http: AuthHttp) {}
  @Get('csrf')
  csrf(@Req() req: FastifyRequest, @Res({ passthrough: true }) reply: FastifyReply) {
    reply.header('cache-control', 'no-store'); return this.http.csrf(req, reply);
  }
  @Post('register') @HttpCode(202)
  async register(@Req() req: FastifyRequest, @Body() body: unknown) {
    await this.http.unsafe(req); const data = input(RegistrationInput, body);
    await this.auth.register(data.email, data.password, req.ip, data.login); return { accepted: true };
  }
  @Post('login') @HttpCode(200)
  async login(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const kind = await this.http.unsafe(req); const data = input(LoginInput, body);
    const result = await this.auth.login(data.login, data.password, kind, req.ip, req.id);
    reply.header('cache-control', 'no-store');
    return 'mfa_required' in result ? result : this.http.issue(result, kind, reply);
  }
  @Post('mfa/login') @HttpCode(200)
  async mfaLogin(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const kind = await this.http.unsafe(req); const data = input(MfaLoginInput, body);
    return this.http.issue(await this.auth.mfaLogin(data.token, data.proof, kind, req.ip, req.id), kind, reply);
  }
  @Post('refresh') @HttpCode(200)
  async refresh(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const kind = await this.http.unsafe(req);
    const token = kind === 'WEB_ADMIN' ? (emptyInput(body), this.http.cookies(req).get('__Host-aoc-refresh') ?? '') : input(TokenInput, body).token;
    return this.http.issue(await this.auth.refresh(token, kind, req.ip, req.id), kind, reply);
  }
  @Post('logout') @HttpCode(204)
  async logout(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const kind = await this.http.unsafe(req);
    const token = kind === 'WEB_ADMIN' ? (emptyInput(body), this.http.cookies(req).get('__Host-aoc-refresh') ?? '') : input(TokenInput, body).token;
    if (kind === 'WEB_ADMIN') this.http.clear(reply);
    await this.auth.logout(token, kind, req.id);
  }
  @Get('me')
  async me(@Req() req: FastifyRequest, @Res({ passthrough: true }) reply: FastifyReply) {
    reply.header('cache-control', 'no-store'); return this.auth.profile(await this.http.principal(req, true));
  }
  @Post('mfa/enroll') @HttpCode(200)
  async enroll(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    await this.http.unsafe(req); reply.header('cache-control','no-store');
    return this.auth.enroll(await this.http.principal(req), input(PasswordInput, body).password, req.id);
  }
  @Post('mfa/confirm') @HttpCode(200)
  async confirm(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const kind = await this.http.unsafe(req); const data = input(ProofInput, body); reply.header('cache-control','no-store');
    const recoveryCodes = await this.auth.confirmMfa(await this.http.principal(req), data.password, data.proof, req.id);
    if (kind === 'WEB_ADMIN') this.http.clear(reply);
    return { recovery_codes: recoveryCodes, reauthentication_required: true };
  }
  @Post('step-up') @HttpCode(204)
  async step(@Req() req: FastifyRequest, @Body() body: unknown) {
    await this.http.unsafe(req); const data = input(ProofInput, body);
    await this.auth.stepUp(await this.http.principal(req), data.password, data.proof, req.id);
  }
  @Post('password') @HttpCode(204)
  async password(@Req() req: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const kind = await this.http.unsafe(req); const data = input(PasswordChangeInput, body);
    await this.auth.changePassword(await this.http.principal(req, true), data.password, data.new_password, req.id);
    if (kind === 'WEB_ADMIN') this.http.clear(reply);
  }
  @Get('sessions')
  async sessions(@Req() req: FastifyRequest, @Res({ passthrough: true }) reply: FastifyReply) {
    reply.header('cache-control','no-store'); return this.auth.listSessions(await this.http.principal(req));
  }
  @Post('sessions/:id/revoke') @HttpCode(204)
  async revoke(@Req() req: FastifyRequest, @Param('id', new ParseUUIDPipe({ version: '4' })) id: string, @Body() body: unknown) {
    await this.http.unsafe(req); emptyInput(body); await this.auth.revokeSession(await this.http.principal(req), id, req.id);
  }
}
