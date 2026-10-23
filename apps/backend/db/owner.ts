import 'reflect-metadata';
import '../src/environment.js';
import { DatabaseService } from '../src/infrastructure/database.service.js';
import { RateLimitStoreService } from '../src/infrastructure/rate-limit-store.service.js';
import { AuthCrypto } from '../src/modules/auth/auth-crypto.js';

import { AuthService } from '../src/modules/auth/auth.service.js';
import { ownerCommand, type OwnerCommand } from '../src/modules/auth/owner-governance.js';

// Secrets are read on stdin from the interactive PowerShell wrapper, never argv/environment/logs.
let database: DatabaseService | undefined;
let rate: RateLimitStoreService | undefined;
try {
  const url = new URL(process.env.MIGRATION_DATABASE_URL ?? '');
  if (process.env.NODE_ENV !== 'development' || url.hostname !== '127.0.0.1' || url.port !== '54329' || url.pathname !== '/animeoncraft') throw new Error('Local operator only');
  process.env.DATABASE_URL = url.toString();
  let raw = '';
  for await (const chunk of process.stdin) { raw += chunk.toString(); if (raw.length > 8192) throw new Error('Input too large'); }
  const value = JSON.parse(raw) as OwnerCommand; raw = '';
  if (!['bootstrap','transfer','recover-mfa'].includes(value.action) || typeof value.password !== 'string' || typeof value.proof !== 'string') throw new Error('Invalid input');
  database = new DatabaseService(); rate = new RateLimitStoreService();
  const crypto = new AuthCrypto(); await crypto.onModuleInit();

  const auth = new AuthService(database, rate, crypto);
  for(const field of ['user_id','target_id'] as const){
    const valueId=value[field];if(!valueId||/^[a-f0-9-]{36}$/i.test(valueId))continue;
    const key=valueId.trim().toLowerCase();const row=await auth.db.user.findUnique({where:key.includes('@')?{emailKey:key}:{loginKey:key}});
    if(!row)throw new Error('Account not found');value[field]=row.id;
  }

  await ownerCommand(auth, value);

  console.log('Local OWNER command committed and audited. Existing sessions revoked.');
} catch {
  console.error('OWNER command failed. Inspect account state/audit as the local operator; credentials were not logged.'); process.exitCode = 1;
} finally {
  await database?.onApplicationShutdown(); await rate?.onApplicationShutdown();
}
