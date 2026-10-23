import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Run against the local development backend. This test temporarily stops each
// Compose dependency and restores both even if an assertion fails.
const root = fileURLToPath(new URL('../', import.meta.url));
const dockerCandidate = process.platform === 'win32'
  ? path.join(process.env.LOCALAPPDATA ?? '', 'Programs/DockerDesktop/resources/bin/docker.exe') : 'docker';
const docker = existsSync(dockerCandidate) ? dockerCandidate : 'docker';
const composeArgs = ['compose', '--env-file', path.join(root, '.env'), '-f', path.join(root, 'infra/compose/compose.yaml')];
function compose(...args) {
  execFileSync(docker, [...composeArgs, ...args], { cwd: root, stdio: 'pipe', timeout: 120_000 });
}
async function request(endpoint, expectedStatus) {
  const response = await fetch(`http://127.0.0.1:3000${endpoint}`, { signal: AbortSignal.timeout(4000) });
  assert.equal(response.status, expectedStatus, endpoint);
  return response.json();
}
async function healthy() {
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = await fetch('http://127.0.0.1:3000/api/v1/health/ready', { signal: AbortSignal.timeout(4000) });
    if (response.status === 200) {
      assert.deepEqual(await response.json(), { status: 'ok', dependencies: { database: 'ok', rate_limits: 'ok' } });
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  assert.fail('Readiness did not recover');
}

await healthy();
assert.deepEqual(await request('/api/v1/clans', 200), { items: [], next_cursor: null });
try {
  for (const [service, dependency, other] of [['valkey', 'rate_limits', 'database'], ['postgres', 'database', 'rate_limits']]) {
    compose('stop', service);
    const ready = await request('/api/v1/health/ready', 503);
    assert.equal(ready.status, 'unavailable');
    assert.equal(ready.dependencies[dependency], 'unavailable');
    assert.equal(ready.dependencies[other], 'ok');
    await request('/api/v1/health/live', 200);
    await request('/api/v1/clans', service === 'postgres' ? 503 : 200);
    compose('up', '-d', '--wait', service);
    await healthy();
    console.log(`${service}: readiness 200 -> 503 -> 200, liveness and public read passed`);
  }
} finally {
  compose('up', '-d', '--wait', 'postgres', 'valkey');
}
