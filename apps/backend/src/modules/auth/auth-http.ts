import { BadRequestException, ForbiddenException, Inject, Injectable } from '@nestjs/common';
import { createHmac, timingSafeEqual } from 'node:crypto';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { plainToInstance } from 'class-transformer';
import { validateSync } from 'class-validator';
import { AuthService, type TokenPair } from './auth.service.js';
import { authOrigin, authSecret } from './auth-config.js';
import { digest, opaque, validOpaque } from './auth-crypto.js';
import type { session_client_kind } from '../../generated/prisma/client.js';

const ACCESS = '__Host-aoc-access'; const REFRESH = '__Host-aoc-refresh'; const PRE = '__Host-aoc-pre';
export function input<T extends object>(ctor: new () => T, value: unknown): T {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new BadRequestException('Invalid input');
  const result = plainToInstance(ctor, value);
  if (validateSync(result, { whitelist: true, forbidNonWhitelisted: true, forbidUnknownValues: true }).length) throw new BadRequestException('Invalid input');
  return result;
}
export function emptyInput(value: unknown): void {
  if (value !== undefined && (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length)) throw new BadRequestException('Unexpected input');
}

@Injectable()
export class AuthHttp {
  private readonly key = authSecret('AOC_CSRF_KEY');
  private readonly origin = authOrigin();
  constructor(@Inject(AuthService) private readonly auth: AuthService) {}
  kind(request: FastifyRequest): session_client_kind {
    const client = request.headers['x-aoc-client'];
    if (client === 'web-admin') {
      if (request.headers.authorization) throw new BadRequestException('Web authorization uses cookies');
      return 'WEB_ADMIN';
    }
    if (client === 'launcher' && !request.headers.origin && !request.headers.cookie && !request.headers['sec-fetch-site']) return 'LAUNCHER';
    throw new BadRequestException('Invalid authentication transport');
  }
  cookies(request: FastifyRequest): Map<string,string> {
    const map = new Map<string,string>();
    for (const item of (request.headers.cookie ?? '').split(';').filter(Boolean)) {
      const [name, ...parts] = item.trim().split('=');
      if (map.has(name!)) throw new BadRequestException('Ambiguous cookies');
      map.set(name!, parts.join('='));
    }
    return map;
  }
  sign(value: string): string { return createHmac('sha256', this.key).update(value).digest('base64url'); }
  same(a: unknown, b: string): boolean {
    if (typeof a !== 'string') return false;
    const left = Buffer.from(a); const right = Buffer.from(b);
    return left.length === right.length && timingSafeEqual(left, right);
  }
  async sessionCsrf(request: FastifyRequest): Promise<string | null> {
    const refresh = this.cookies(request).get(REFRESH) ?? '';
    if (!validOpaque(refresh, 'ar')) return null;
    const row = await this.auth.db.auth_tokens.findUnique({ where: { token_hash: digest(refresh) }, include: { session: true } });
    return row?.kind === 'REFRESH' && row.session.client_kind === 'WEB_ADMIN' ? this.sign(`session:${row.session_id}`) : null;
  }
  async unsafe(request: FastifyRequest): Promise<session_client_kind> {
    const kind = this.kind(request);
    if (kind === 'LAUNCHER') return kind;
    if (request.headers.origin !== this.origin) throw new ForbiddenException('Invalid origin');
    const header = request.headers['x-csrf-token'];
    const session = await this.sessionCsrf(request);
    if (session) { if (!this.same(header, session)) throw new ForbiddenException('Invalid CSRF token'); return kind; }
    const pre = this.cookies(request).get(PRE) ?? '';
    const parts = pre.split('.');
    const data = parts.slice(0,2).join('.');
    if (parts.length !== 3 || !validOpaque(parts[0] ?? '', 'pc') || !/^\d{13}$/.test(parts[1] ?? '') || Number(parts[1]) <= Date.now()
      || Number(parts[1]) > Date.now() + 10 * 60000 || !this.same(parts[2], this.sign(`pre:${data}`)) || !this.same(header, this.sign(`header:${pre}`))) {
      throw new ForbiddenException('Invalid CSRF token');
    }
    return kind;
  }
  async csrf(request: FastifyRequest, reply: FastifyReply): Promise<{ csrf_token: string }> {
    if (this.kind(request) !== 'WEB_ADMIN') throw new BadRequestException('CSRF is a web transport operation');
    if (request.headers.origin && request.headers.origin !== this.origin) throw new ForbiddenException('Invalid origin');
    await this.auth.limits('login',request.ip);
    const token = await this.sessionCsrf(request);
    if (token) return { csrf_token: token };
    const data = `${opaque('pc')}.${Date.now() + 5 * 60000}`;
    const pre = `${data}.${this.sign(`pre:${data}`)}`;
    reply.header('set-cookie', [`${PRE}=${pre}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=300`]);
    return { csrf_token: this.sign(`header:${pre}`) };
  }
  access(request: FastifyRequest): string {
    if (this.kind(request) === 'WEB_ADMIN') return this.cookies(request).get(ACCESS) ?? '';
    const match = /^Bearer (aa_[A-Za-z0-9_-]{43})$/.exec(request.headers.authorization ?? '');
    return match?.[1] ?? '';
  }
  issue(pair: TokenPair, kind: session_client_kind, reply: FastifyReply) {
    reply.header('cache-control', 'no-store');
    if (kind === 'LAUNCHER') return pair;
    const refreshAge = Math.max(0, Math.floor((Date.parse(pair.absolute_expires_at) - Date.now()) / 1000));
    reply.header('set-cookie', [
      `${ACCESS}=${pair.access_token}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${pair.expires_in}`,
      `${REFRESH}=${pair.refresh_token}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${refreshAge}`,
      `${PRE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0`
    ]);
    return { authenticated: true, expires_in: pair.expires_in, csrf_token: this.sign(`session:${pair.session_id}`) };
  }
  clear(reply: FastifyReply): void {
    reply.header('set-cookie', [ACCESS,REFRESH,PRE].map(name => `${name}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0`));
  }
  async principal(request: FastifyRequest, allowPasswordChange = false) { return this.auth.access(this.access(request), this.kind(request), true, allowPasswordChange); }
}
