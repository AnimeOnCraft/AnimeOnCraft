import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes } from 'node:crypto';
import { Injectable, OnModuleInit, ServiceUnavailableException } from '@nestjs/common';
import * as argon2 from 'argon2';
import * as OTPAuth from 'otpauth';
import { authSecret } from './auth-config.js';

export const PASSWORD_OPTIONS = { type: argon2.argon2id, memoryCost: 65536, timeCost: 3, parallelism: 1 } as const;
export const digest = (value: string) => createHash('sha256').update(value).digest('hex');
export const opaque = (prefix: string) => `${prefix}_${randomBytes(32).toString('base64url')}`;
export const validOpaque = (value: string, prefix: string) => new RegExp(`^${prefix}_[A-Za-z0-9_-]{43}$`).test(value);
export const validPassword = (value: string) => typeof value === 'string' && [...value].length >= 15 && [...value].length <= 128 && Buffer.byteLength(value) <= 1024;

@Injectable()
export class AuthCrypto implements OnModuleInit {
  private active = 0;
  private dummy = '';
  private readonly encryptionKey = authSecret('AOC_MFA_KEY');
  readonly keyId = process.env.AOC_MFA_KEY_ID ?? 'development-v1';
  private readonly privacyKey = authSecret('AOC_AUTH_PRIVACY_KEY');
  constructor() {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(this.keyId)) throw new Error('Invalid MFA key ID');
  }
  async onModuleInit(): Promise<void> { this.dummy = await this.hash(opaque('dummy')); }
  private async bounded<T>(task: () => Promise<T>): Promise<T> {
    // No unbounded password queue and at most 128 MiB of Argon2 working memory per process.
    if (this.active >= 2) throw new ServiceUnavailableException();
    this.active++;
    try { return await task(); } finally { this.active--; }
  }
  hash(password: string): Promise<string> { return this.bounded(() => argon2.hash(password, PASSWORD_OPTIONS)); }
  async verify(hash: string | undefined, password: string): Promise<boolean> {
    return this.bounded(async () => { try { return await argon2.verify(hash ?? this.dummy, password); } catch { return false; } });
  }
  needsRehash(hash: string): boolean { return argon2.needsRehash(hash, PASSWORD_OPTIONS); }
  accountKey(email: string): string { return createHmac('sha256', this.privacyKey).update(email).digest('hex'); }
  encrypt(secret: string, userId: string): string {
    const iv = randomBytes(12); const cipher = createCipheriv('aes-256-gcm', this.encryptionKey, iv);
    cipher.setAAD(Buffer.from(`${this.keyId}:${userId}`));
    const encrypted = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()]);
    return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString('base64url');
  }
  decrypt(ciphertext: string, keyId: string, userId: string): string {
    if (keyId !== this.keyId) throw new ServiceUnavailableException();
    try {
      const data = Buffer.from(ciphertext, 'base64url');
      const decipher = createDecipheriv('aes-256-gcm', this.encryptionKey, data.subarray(0, 12));
      decipher.setAAD(Buffer.from(`${keyId}:${userId}`)); decipher.setAuthTag(data.subarray(12, 28));
      return Buffer.concat([decipher.update(data.subarray(28)), decipher.final()]).toString('utf8');
    } catch { throw new ServiceUnavailableException(); }
  }
  totp(secret?: string, label = 'AnimeOnCraft'): OTPAuth.TOTP {
    return new OTPAuth.TOTP({ issuer: 'AnimeOnCraft', label, algorithm: 'SHA1', digits: 6, period: 30,
      secret: secret ? OTPAuth.Secret.fromBase32(secret) : new OTPAuth.Secret({ size: 20 }) });
  }
  step(secret: string, token: string, now: number): bigint | null {
    if (!/^\d{6}$/.test(token)) return null;
    const delta = this.totp(secret).validate({ token, window: 1, timestamp: now });
    return delta === null ? null : BigInt(Math.floor(now / 30000) + delta);
  }
}
