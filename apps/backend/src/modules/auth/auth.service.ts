import { BadRequestException, ForbiddenException, HttpException, Inject, Injectable, Optional, ServiceUnavailableException, UnauthorizedException } from '@nestjs/common';
import { DatabaseService } from '../../infrastructure/database.service.js';
import { RateLimitStoreService } from '../../infrastructure/rate-limit-store.service.js';
import { Prisma, type User, type sessions, type session_client_kind } from '../../generated/prisma/client.js';
import { AuthCrypto, digest, opaque, validOpaque, validPassword } from './auth-crypto.js';


type Tx = Prisma.TransactionClient;
export interface Principal { user: User; session: sessions; access_token_id: string; managers: { clan_id: string; can_remove_members: boolean }[] }
export interface TokenPair { access_token: string; refresh_token: string; expires_in: number; session_id: string; absolute_expires_at: string }
const after = (milliseconds: number, now = new Date()) => new Date(now.getTime() + milliseconds);
const MINUTE = 60000; const DAY = 86400000;
const emailKey = (email: string) => email.trim().toLowerCase();
const rejected = () => new UnauthorizedException('Invalid credentials or expired authorization');

@Injectable()
export class AuthService {
  constructor(@Inject(DatabaseService) private readonly database: DatabaseService,
    @Inject(RateLimitStoreService) private readonly rate: RateLimitStoreService,
    @Optional() @Inject(AuthCrypto) private readonly authCrypto?: AuthCrypto) {}
  // Private game integration validates existing launcher sessions; it has no password/MFA routes.
  // Public AuthModule still provides and initializes AuthCrypto, including its required keys.
  get crypto():AuthCrypto {if(!this.authCrypto)throw new ServiceUnavailableException('Authentication cryptography unavailable');return this.authCrypto;}
  get db() { if (!this.database.client) throw new ServiceUnavailableException(); return this.database.client; }
  transaction<T>(task: (tx: Tx) => Promise<T>): Promise<T> {
    return this.db.$transaction(async tx => { await tx.$executeRaw`SELECT pg_advisory_xact_lock_shared(171733)`; return task(tx); }, { timeout: 5000, maxWait: 2000 });
  }
  async lockUser(tx: Tx, id: string): Promise<User | null> {
    await tx.$queryRaw`SELECT id FROM users WHERE id = ${id}::uuid FOR UPDATE`;
    return tx.user.findUnique({ where: { id } });
  }
  async audit(tx: Tx, userId: string, action: string, requestId: string, targetId = userId): Promise<void> {
    await tx.audit_logs.create({ data: { actor_kind: 'USER', actor_user_id: userId, action, target_type: 'auth', target_id: targetId,
      reason: 'Account security action', request_id: requestId } });
  }
  async revoke(tx: Tx, userId: string, sessionId?: string): Promise<void> {
    const now = new Date(); const where = { user_id: userId, ...(sessionId ? { id: sessionId } : {}) };
    await tx.sessions.updateMany({ where: { ...where, revoked_at: null }, data: { revoked_at: now } });
    await tx.auth_tokens.updateMany({ where: { session: where, revoked_at: null }, data: { revoked_at: now } });
    await tx.auth_challenges.updateMany({ where: { user_id: userId, consumed_at: null, ...(sessionId ? { session_id: sessionId } : {}) }, data: { consumed_at: now } });
  }
  async usable(tx: Tx, user: User | null): Promise<boolean> {
    if (!user) return false;
    const banned = await tx.bans.count({ where: { user_id: user.id, scope: 'ACCOUNT', revoked_at: null,
      OR: [{ expires_at: null }, { expires_at: { gt: new Date() } }] } });
    if (user.state !== 'ACTIVE' || banned) { await this.revoke(tx, user.id); return false; }
    return true;
  }
  async limits(scope: 'login'|'register'|'recovery'|'mfa'|'bind'|'api'|'reauth', ip: string, account?: string): Promise<void> {
    const prefix = process.env.AOC_AUTH_RATE_NAMESPACE ?? 'aoc:auth:v1';
    const key = (suffix: string) => `${prefix}:${scope}:${this.crypto.accountKey(suffix)}`;
    const budgets: { key: string; limit: number; seconds: number }[] = [];
    if (scope === 'login') { budgets.push({ key: key(`ip:${ip}`), limit: 30, seconds: 900 }); if (account) budgets.push({ key: key(`account:${account}`), limit: 10, seconds: 900 }); }
    if (scope === 'register') budgets.push({ key: key(`ip:${ip}`), limit: 5, seconds: 3600 });
    if (scope === 'recovery') { budgets.push({ key: key(`ip:${ip}`), limit: 5, seconds: 3600 }); if (account) budgets.push({ key: key(`account:${account}`), limit: 3, seconds: 3600 }); }
    if (scope === 'mfa') budgets.push({ key: key(account ?? ip), limit: 5, seconds: 300 });
    if (scope === 'bind') budgets.push({ key: key(account ?? ip), limit: 5, seconds: 3600 });
    if (scope === 'api') budgets.push({ key: key(account ?? ip), limit: 120, seconds: 60 });
    if (scope === 'reauth') budgets.push({ key: key(account ?? ip), limit: 10, seconds: 900 });
    await this.rate.consume(budgets);
  }
  async challenge(tx: Tx, user: User, kind: 'EMAIL_VERIFY'|'PASSWORD_RESET'|'LOGIN_MFA'|'MINECRAFT_BIND', minutes: number, client?: session_client_kind, sessionId?: string): Promise<string> {
    await tx.auth_challenges.updateMany({ where: { user_id: user.id, kind, consumed_at: null }, data: { consumed_at: new Date() } });
    const token = opaque('ac');
    await tx.auth_challenges.create({ data: { user_id: user.id, kind, token_hash: digest(token), auth_epoch: user.authEpoch,
      expires_at: after(minutes * MINUTE), client_kind: client ?? null, session_id: sessionId ?? null } });
    return token;
  }
  async register(email: string, password: string, ip: string, login = 'player_' + digest(emailKey(email)).slice(0,32)): Promise<void> {
    if (!validPassword(password)) throw new BadRequestException('Password must contain 15–128 Unicode characters');
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$/.test(login)) throw new BadRequestException('Invalid login');
    const key = emailKey(email); const loginKey = login.toLowerCase(); await this.limits('register', ip);
    const hash = await this.crypto.hash(password);
    try {
      await this.transaction(async tx => {
        if (await tx.user.count({where:{OR:[{emailKey:key},{loginKey}]}})) throw new HttpException('Login or email already registered',409);
        await tx.user.create({data:{login,email:key,emailKey:key,passwordHash:hash,systemRole:'PLAYER',state:'ACTIVE'}});
      });
    } catch(error) {
      if(error instanceof Prisma.PrismaClientKnownRequestError && error.code==='P2002') throw new HttpException('Login or email already registered',409);
      throw error;
    }
  }
  // Historical service signatures retained for old test harnesses; public routes are removed.
  async verifyEmail(_token: string, _password: string, _requestId: string): Promise<void> { throw new ForbiddenException('Email verification is not used'); }
  async recovery(_email: string, _ip: string): Promise<void> { throw new ForbiddenException('Contact the owner for manual recovery'); }
  async reset(_token: string, _password: string, _requestId: string, _ip: string): Promise<void> { throw new ForbiddenException('Contact the owner for manual recovery'); }
  async managers(tx: Tx, userId: string) {
    const rows = await tx.clan_manager_assignments.findMany({ where: { user_id: userId, ended_at: null, clans: { archivedAt: null } } });
    return rows.map(row => ({ clan_id: row.clan_id, can_remove_members: row.can_remove_members }));
  }
  async login(login: string, password: string, kind: session_client_kind, ip: string, requestId: string): Promise<TokenPair | { mfa_required: true; challenge: string }> {
    const key = login.trim().toLowerCase(); await this.limits('login', ip, key);
    // Existing accounts keep email sign-in; new accounts also have a separate handle.
    const initial = await this.db.user.findUnique({ where: key.includes('@') ? { emailKey: key } : { loginKey: key } });
    const verified = await this.crypto.verify(initial?.passwordHash, password);
    if (!initial || !verified) throw rejected();
    const rehash = this.crypto.needsRehash(initial.passwordHash) ? await this.crypto.hash(password) : null;
    const result = await this.transaction(async tx => {
      const user = await this.lockUser(tx, initial.id);
      if (!user || user.passwordHash !== initial.passwordHash || !(await this.usable(tx, user))) return null;
      const mfa = await tx.mfa_credentials.findUnique({ where: { user_id: user.id } });
      if (kind === 'WEB_ADMIN' && (user.systemRole !== 'OWNER' && !(await this.managers(tx, user.id)).length)) return null;
      if (kind === 'WEB_ADMIN' && !mfa?.enabled) return { enrollment_required: true } as const;
      if (rehash) await tx.user.update({ where: { id: user.id }, data: { passwordHash: rehash } });
      if (kind === 'WEB_ADMIN' && mfa?.enabled) return { mfa_required: true, challenge: await this.challenge(tx, user, 'LOGIN_MFA', 5, kind) } as const;
      return this.newSession(tx, user, kind, false, requestId);
    });
    if (!result) throw rejected();
    if ('enrollment_required' in result) throw new HttpException({ code: 'MFA_REQUIRED', message: 'Enroll MFA using a launcher session' }, 403);
    return result;
  }
  async newSession(tx: Tx, user: User, kind: session_client_kind, mfa: boolean, requestId: string): Promise<TokenPair> {
    const now = new Date(); const native = kind === 'LAUNCHER';
    const session = await tx.sessions.create({ data: { user_id: user.id, client_kind: kind, auth_epoch: user.authEpoch,
      absolute_expires_at: after(native ? 30 * DAY : 12 * 60 * MINUTE, now), idle_expires_at: after(native ? 7 * DAY : 30 * MINUTE, now), mfa_verified_at: mfa ? now : null } });
    await this.audit(tx, user.id, 'SESSION_CREATED', requestId, session.id);
    return this.tokens(tx, session);
  }
  async tokens(tx: Tx, session: sessions): Promise<TokenPair> {
    const access = opaque('aa'); const refresh = opaque('ar');
    const now = new Date(); const accessExpiry = new Date(Math.min(after(10 * MINUTE, now).getTime(), session.absolute_expires_at.getTime(), session.idle_expires_at.getTime()));
    await tx.auth_tokens.createMany({ data: [
      { session_id: session.id, kind: 'ACCESS', token_hash: digest(access), expires_at: accessExpiry },
      { session_id: session.id, kind: 'REFRESH', token_hash: digest(refresh), expires_at: session.absolute_expires_at }
    ] });
    return { access_token: access, refresh_token: refresh, expires_in: Math.floor((accessExpiry.getTime() - now.getTime()) / 1000), session_id: session.id, absolute_expires_at: session.absolute_expires_at.toISOString() };
  }
  async sessionValid(tx: Tx, user: User, session: sessions, kind: session_client_kind): Promise<boolean> {
    const now = new Date();
    if (session.client_kind !== kind || session.revoked_at || session.auth_epoch !== user.authEpoch || session.absolute_expires_at <= now || session.idle_expires_at <= now) return false;
    if (kind === 'WEB_ADMIN') {
      const credential = await tx.mfa_credentials.findUnique({ where: { user_id: user.id } });
      if (!session.mfa_verified_at || !credential?.enabled || (user.systemRole !== 'OWNER' && !(await this.managers(tx, user.id)).length)) return false;
    }
    return true;
  }
  async access(token: string, kind: session_client_kind, consumeBudget = true, allowPasswordChange = false): Promise<Principal> {
    if (!validOpaque(token, 'aa')) throw rejected();
    const initial = await this.db.auth_tokens.findUnique({ where: { token_hash: digest(token) }, include: { session: true } });
    if (!initial || initial.kind !== 'ACCESS') throw rejected();
    const principal = await this.transaction(async tx => {
      const user = await this.lockUser(tx, initial.session.user_id);
      const current = await tx.auth_tokens.findUnique({ where: { id: initial.id }, include: { session: true } });
      if (!user || !(await this.usable(tx, user)) || !current || current.revoked_at || current.expires_at <= new Date() || !(await this.sessionValid(tx, user, current.session, kind))) return null;
      return { user, session: current.session, access_token_id: current.id, managers: await this.managers(tx, user.id) };
    });
    if (!principal) throw rejected();
    if (principal.user.passwordChangeRequired && !allowPasswordChange) throw new HttpException({code:'PASSWORD_CHANGE_REQUIRED',message:'Change the temporary password first'},403);
    if (consumeBudget) await this.limits('api', '', principal.user.id);
    return principal;
  }
  async refresh(token: string, kind: session_client_kind, ip: string, requestId: string): Promise<TokenPair> {
    await this.limits('login', ip); // Covers malformed/missing tokens as well.
    if (!validOpaque(token, 'ar')) throw rejected();
    const initial = await this.db.auth_tokens.findUnique({ where: { token_hash: digest(token) }, include: { session: true } });
    if (!initial || initial.kind !== 'REFRESH') throw rejected();
    await this.limits('api', '', initial.session.user_id);
    const result = await this.transaction(async tx => {
      const user = await this.lockUser(tx, initial.session.user_id);
      await tx.$queryRaw`SELECT id FROM sessions WHERE id = ${initial.session_id}::uuid FOR UPDATE`;
      const current = await tx.auth_tokens.findUnique({ where: { id: initial.id }, include: { session: true } });
      if (!user || !(await this.usable(tx, user)) || !current || current.session.client_kind !== kind) return null;
      // Revocation must COMMIT before the HTTP error: do not throw inside this transaction.
      if (current.used_at) { await this.revoke(tx, user.id, current.session_id); await this.audit(tx, user.id, 'REFRESH_REUSE_REVOKED', requestId, current.session_id); return null; }
      if (current.revoked_at || current.expires_at <= new Date() || !(await this.sessionValid(tx, user, current.session, kind))) return null;
      const now = new Date();
      await tx.auth_tokens.update({ where: { id: current.id }, data: { used_at: now } });
      await tx.auth_tokens.updateMany({ where: { session_id: current.session_id, kind: 'ACCESS', revoked_at: null }, data: { revoked_at: now } });
      const session = await tx.sessions.update({ where: { id: current.session_id }, data: { idle_expires_at: new Date(Math.min(current.session.absolute_expires_at.getTime(), after(kind === 'LAUNCHER' ? 7 * DAY : 30 * MINUTE, now).getTime())) } });
      return this.tokens(tx, session);
    });
    if (!result) throw rejected(); return result;
  }
  async mfaProof(tx: Tx, userId: string, proof: string, requireTotp = false): Promise<boolean> {
    const credential = await tx.mfa_credentials.findUnique({ where: { user_id: userId } });
    if (!credential?.enabled && !requireTotp) return false;
    if (!credential) return false;
    if (/^\d{6}$/.test(proof)) {
      const secret = this.crypto.decrypt(credential.secret_ciphertext, credential.key_id, userId);
      const step = this.crypto.step(secret, proof, Date.now());
      if (step === null || step <= credential.last_used_step) return false;
      await tx.mfa_credentials.update({ where: { user_id: userId }, data: { last_used_step: step } }); return true;
    }
    if (requireTotp || !validOpaque(proof, 'rc')) return false;
    const changed = await tx.mfa_recovery_codes.updateMany({ where: { user_id: userId, code_hash: digest(proof), used_at: null }, data: { used_at: new Date() } });
    return changed.count === 1;
  }
  async mfaLogin(token: string, proof: string, kind: session_client_kind, ip: string, requestId: string): Promise<TokenPair> {
    await this.limits('mfa', ip, digest(token));
    if (!validOpaque(token, 'ac')) throw rejected();
    const initial = await this.db.auth_challenges.findUnique({ where: { token_hash: digest(token) } });
    if (!initial) throw rejected();
    const result = await this.transaction(async tx => {
      const user = await this.lockUser(tx, initial.user_id); const challenge = await tx.auth_challenges.findUnique({ where: { id: initial.id } });
      if (!user || !(await this.usable(tx, user)) || !challenge || challenge.kind !== 'LOGIN_MFA' || challenge.client_kind !== kind || challenge.consumed_at || challenge.expires_at <= new Date() || challenge.auth_epoch !== user.authEpoch) return null;
      if (kind === 'WEB_ADMIN' && user.systemRole !== 'OWNER' && !(await this.managers(tx, user.id)).length) return null;
      if (!(await this.mfaProof(tx, user.id, proof))) return null;
      await tx.auth_challenges.update({ where: { id: challenge.id }, data: { consumed_at: new Date() } });
      return this.newSession(tx, user, kind, true, requestId);
    });
    if (!result) throw rejected(); return result;
  }
  async logout(token: string, kind: session_client_kind, requestId: string): Promise<void> {
    if (!validOpaque(token, 'ar')) return;
    const initial = await this.db.auth_tokens.findUnique({ where: { token_hash: digest(token) }, include: { session: true } });
    if (!initial || initial.kind !== 'REFRESH' || initial.session.client_kind !== kind) return;
    await this.transaction(async tx => { await this.lockUser(tx, initial.session.user_id); await this.revoke(tx, initial.session.user_id, initial.session_id); await this.audit(tx, initial.session.user_id, 'SESSION_LOGOUT', requestId, initial.session_id); });
  }
  async credentialAction<T>(principal: Principal, password: string, task: (tx: Tx, user: User, session: sessions) => Promise<T>): Promise<T> {
    await this.limits('reauth', '', principal.user.id);
    if (!(await this.crypto.verify(principal.user.passwordHash, password))) throw rejected();
    const result = await this.transaction(async tx => {
      const user = await this.lockUser(tx, principal.user.id); const session = await tx.sessions.findUnique({ where: { id: principal.session.id } });
      if (!user || user.passwordHash !== principal.user.passwordHash || !(await this.usable(tx, user)) || !session || !(await this.sessionValid(tx, user, session, principal.session.client_kind))) return { valid: false } as const;
      return { valid: true, data: await task(tx, user, session) } as const;
    });
    if (!result.valid) throw rejected(); return result.data;
  }
  async enroll(principal: Principal, password: string, requestId: string): Promise<{ otpauth_uri: string; expires_in: number }> {
    const totp = this.crypto.totp(undefined, principal.user.email);
    return this.credentialAction(principal, password, async (tx, user) => {
      const existing = await tx.mfa_credentials.findUnique({ where: { user_id: user.id } });
      if (existing?.enabled) throw new ForbiddenException('MFA is already enabled');
      const data = { secret_ciphertext: this.crypto.encrypt(totp.secret.base32, user.id), key_id: this.crypto.keyId, enabled: false, pending_expires_at: after(5 * MINUTE), last_used_step: -1n };
      await tx.mfa_credentials.upsert({ where: { user_id: user.id }, create: { user_id: user.id, ...data }, update: data });
      await this.audit(tx, user.id, 'MFA_ENROLLMENT_STARTED', requestId);
      return { otpauth_uri: totp.toString(), expires_in: 300 };
    });
  }
  async confirmMfa(principal: Principal, password: string, proof: string, requestId: string): Promise<string[]> {
    await this.limits('mfa', '', `enroll:${principal.user.id}`);
    const codes = Array.from({ length: 10 }, () => opaque('rc'));
    return this.credentialAction(principal, password, async (tx, user) => {
      const credential = await tx.mfa_credentials.findUnique({ where: { user_id: user.id } });
      if (!credential || credential.enabled || !credential.pending_expires_at || credential.pending_expires_at <= new Date() || !(await this.mfaProof(tx, user.id, proof, true))) throw rejected();
      await tx.mfa_credentials.update({ where: { user_id: user.id }, data: { enabled: true, pending_expires_at: null } });
      await tx.mfa_recovery_codes.createMany({ data: codes.map(code => ({ user_id: user.id, code_hash: digest(code) })) });
      await tx.user.update({ where: { id: user.id }, data: { authEpoch: { increment: 1 }, version: { increment: 1 } } });
      await this.revoke(tx, user.id); await this.audit(tx, user.id, 'MFA_ENABLED', requestId);
      return codes;
    });
  }
  async stepUp(principal: Principal, password: string, proof: string, requestId: string): Promise<void> {
    await this.limits('mfa', '', `step:${principal.session.id}`);
    await this.credentialAction(principal, password, async (tx, user, session) => {
      const mfa = await tx.mfa_credentials.findUnique({ where: { user_id: user.id } });
      if ((mfa?.enabled || session.client_kind === 'WEB_ADMIN') && !(await this.mfaProof(tx, user.id, proof))) throw rejected();
      await tx.sessions.update({ where: { id: session.id }, data: { last_step_up_at: new Date(), ...(mfa?.enabled ? { mfa_verified_at: new Date() } : {}) } });
      await this.audit(tx, user.id, 'SESSION_STEP_UP', requestId, session.id);
    });
  }
  async changePassword(principal: Principal, password: string, next: string, requestId: string): Promise<void> {
    if (password === next) throw new BadRequestException('Choose a different password');
    if (!validPassword(next)) throw new BadRequestException('Password must contain 15–128 Unicode characters');
    const hash = await this.crypto.hash(next);
    await this.credentialAction(principal, password, async (tx, user, session) => {
      const mfa = await tx.mfa_credentials.findUnique({ where: { user_id: user.id } });
      if (session.client_kind === 'WEB_ADMIN' && mfa?.enabled && (!session.last_step_up_at || session.last_step_up_at < after(-10 * MINUTE))) throw new ForbiddenException('Recent step-up required');
      await tx.user.update({ where: { id: user.id }, data: { passwordHash: hash, passwordChangeRequired:false, authEpoch: { increment: 1 }, version: { increment: 1 } } });
      await this.revoke(tx, user.id); await this.audit(tx, user.id, 'PASSWORD_CHANGED', requestId);
    });

  }
  profile(principal: Principal) {
    return { id: principal.user.id, login: principal.user.login, password_change_required: principal.user.passwordChangeRequired, email: principal.user.email, email_verified: !!principal.user.emailVerifiedAt, system_role: principal.user.systemRole,
      clan_management: principal.managers, session_id: principal.session.id, client_kind: principal.session.client_kind };
  }
  async listSessions(principal: Principal) {
    const rows = await this.db.sessions.findMany({ where: { user_id: principal.user.id, revoked_at: null, absolute_expires_at: { gt: new Date() }, idle_expires_at: { gt: new Date() } }, orderBy: { created_at: 'desc' }, take: 100 });
    return { items: rows.map(row => ({ id: row.id, client_kind: row.client_kind, created_at: row.created_at.toISOString(), absolute_expires_at: row.absolute_expires_at.toISOString(), current: row.id === principal.session.id })) };
  }
  async revokeSession(principal: Principal, target: string, requestId: string): Promise<void> {
    const ok = await this.transaction(async tx => {
      const user = await this.lockUser(tx, principal.user.id); const session = await tx.sessions.findUnique({ where: { id: principal.session.id } });
      if (!user || !(await this.usable(tx, user)) || !session || !(await this.sessionValid(tx, user, session, session.client_kind))) return false;
      const targetSession = await tx.sessions.findFirst({ where: { id: target, user_id: user.id } });
      if (!targetSession) throw new ForbiddenException();
      await this.revoke(tx, user.id, target); await this.audit(tx, user.id, 'SESSION_REVOKED', requestId, target);
      return true;
    });
    if(!ok) throw rejected();
  }
}
