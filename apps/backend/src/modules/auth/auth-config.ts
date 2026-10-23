import {friendsProductionConfig} from '../../friends-deployment.js';
export function authEnabled(): boolean {
  if (process.env.AOC_AUTH_ENABLED !== 'true') return false;
  if(process.env.NODE_ENV==='production'){friendsProductionConfig();return true;}
  if (!['development', 'test'].includes(process.env.NODE_ENV ?? '')) throw new Error('Authentication release checks are incomplete; development/test only');
  if (process.env.PUBLIC_HOST !== '127.0.0.1') throw new Error('Development authentication must bind to 127.0.0.1');
  return true;
}

export function authSecret(name: string): Buffer {
  const value = process.env[name] ?? '';
  if (!/^[a-f0-9]{64}$/.test(value)) throw new Error(`Missing or invalid ${name}`);
  return Buffer.from(value, 'hex');
}

export function authOrigin(): string {
  const value = process.env.AOC_AUTH_ORIGIN ?? '';
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.origin !== value || url.username || url.password) throw new Error('AOC_AUTH_ORIGIN must be an exact HTTPS origin');
  return value;
}
