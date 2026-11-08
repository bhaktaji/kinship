/**
 * Status attribution without k6.
 *
 *   1. Burst /v1/activities as one account -> expects 429 once past 120/min.
 *   2. Burst /v1/users/me (no limiter) at high concurrency -> DB/pool signal.
 *
 * Usage: node perf/status-probe.mjs
 */
import { readFileSync } from 'node:fs';

const API = process.env.API || 'http://localhost:8080';
const PASSWORD = process.env.PASSWORD || 'Load12345!';
const accounts = JSON.parse(readFileSync(new URL('./.users.json', import.meta.url), 'utf8'));

async function login(account) {
  const response = await fetch(`${API}/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: account.email, password: PASSWORD }),
  });
  if (!response.ok) throw new Error(`login ${response.status}`);
  return (await response.json()).access_token;
}

function tally(bucket, status) {
  bucket[status] = (bucket[status] || 0) + 1;
}

async function burst({ label, path, total, concurrency, token }) {
  const codes = {};
  let next = 0;
  const workers = Array.from({ length: concurrency }, async () => {
    while (next < total) {
      next += 1;
      try {
        const response = await fetch(`${API}${path}`, { headers: { Authorization: `Bearer ${token}` } });
        tally(codes, response.status);
        await response.arrayBuffer();
      } catch (error) {
        tally(codes, `ERR:${error.name}`);
      }
    }
  });
  const started = Date.now();
  await Promise.all(workers);
  const elapsed = Date.now() - started;
  console.log(`${label}: total=${total} concurrency=${concurrency} in ${elapsed}ms ` +
    `(${(total / (elapsed / 1000)).toFixed(1)} rps) statuses=${JSON.stringify(codes)}`);
  return codes;
}

const token = await login(accounts[0]);
if (process.env.ONLY !== 'me') await burst({
  label: 'discovery-burst (limiter 120/min)',
  path: '/v1/activities?lat=52.52&lng=13.405&radius_km=10&limit=20',
  total: 200,
  concurrency: 40,
  token,
});
await new Promise((resolve) => setTimeout(resolve, 1000));
if (process.env.ONLY !== 'discovery')
await burst({
  label: 'users-me-burst (no limiter)',
  path: '/v1/users/me',
  total: 1200,
  concurrency: 150,
  token,
});
