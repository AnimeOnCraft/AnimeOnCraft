// Read-only check of the running launcher dev server, including optimized dependency URLs.
import assert from 'node:assert/strict';
const origin = 'http://127.0.0.1:1420';
const htmlResponse = await fetch(origin, { signal: AbortSignal.timeout(6000) });
assert.equal(htmlResponse.status, 200);
const html = await htmlResponse.text();
assert.match(html, /Загрузка AnimeOnCraft/);
const pending = [...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(match => new URL(match[1], origin).href);
const visited = new Set();
while (pending.length) {
  const url = pending.shift(); if (visited.has(url)) continue;
  assert.equal(new URL(url).origin, origin); visited.add(url);
  assert.ok(visited.size <= 100, 'Unexpected module graph growth');
  const response = await fetch(url, { signal: AbortSignal.timeout(6000) });
  assert.equal(response.status, 200, `Module failed: ${new URL(url).pathname}`);
  assert.match(response.headers.get('content-type') ?? '', /javascript|typescript/);
  const source = await response.text();
  for (const match of source.matchAll(/(?:from\s*|import\s*)["']([^"']+)["']/g)) {
    if (match[1].startsWith('/') || match[1].startsWith('.')) pending.push(new URL(match[1], url).href);
  }
}
for (const resource of ['health/ready', 'launcher/compatibility', 'clans?limit=20', 'news?limit=20']) {
  const response = await fetch(`${origin}/api/v1/${resource}`, { signal: AbortSignal.timeout(6000) });
  assert.equal(response.status, 200, `Public proxy failed: ${resource}`);
  assert.match(response.headers.get('content-type') ?? '', /application\/json/);
  const value = await response.json();
  if (resource.startsWith('clans') || resource.startsWith('news')) assert.ok(Array.isArray(value.items));
}
process.stdout.write(`Launcher dev smoke: ${visited.size} JavaScript modules and four public API reads passed\n`);
