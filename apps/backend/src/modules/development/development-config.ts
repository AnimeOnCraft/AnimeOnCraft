import '../../environment.js';

export function developmentApiEnabled(): boolean {
  if (process.env.AOC_LOCAL_BASE_API !== 'true') return false;
  if (process.env.AOC_AUTH_ENABLED === 'true') throw new Error('Local unauthenticated CRUD and authentication cannot be enabled together');
  if (process.env.NODE_ENV !== 'development' || (process.env.PUBLIC_HOST ?? '127.0.0.1') !== '127.0.0.1') {
    throw new Error('Local base API requires NODE_ENV=development and PUBLIC_HOST=127.0.0.1');
  }
  return true;
}
