import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// P1 smoke: 5 VUs for 2 minutes. One user/token per VU keeps each token under
// the documented 120 requests/minute discovery limit.
// Run: k6 run -e BASE_URL=http://localhost:8080 docs/k6_discovery_smoke.js
// Optional: -e TOKENS=token1,token2,... to reuse existing users.

const VUS = 5;
const cacheHitRate = new Rate('cache_hit_rate');

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: VUS,
      duration: '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
    cache_hit_rate: ['rate>0.80'],
  },
};

export function setup() {
  if (__ENV.TOKENS) {
    return { tokens: __ENV.TOKENS.split(',') };
  }
  if (__ENV.TOKEN) {
    return { tokens: [__ENV.TOKEN] };
  }
  const tokens = [];
  for (let i = 0; i < VUS; i++) {
    const email = `k6-${Date.now()}-${i}-${Math.floor(Math.random() * 1000000)}@example.com`;
    const res = http.post(
      `${__ENV.BASE_URL}/v1/auth/register`,
      JSON.stringify({
        email,
        password: 'Demo12345!',
        display_name: `K6 VU ${i + 1}`,
        timezone: 'Europe/Berlin',
      }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status !== 201) {
      throw new Error('k6 setup registration failed: ' + res.status + ' ' + res.body);
    }
    tokens.push(res.json('access_token'));
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const lat = 52.5200 + (Math.random() - 0.5) * 0.02;
  const lng = 13.4050 + (Math.random() - 0.5) * 0.02;
  const res = http.get(
    `${__ENV.BASE_URL}/v1/activities?lat=${lat}&lng=${lng}&radius_km=10&limit=9`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  cacheHitRate.add(res.headers['X-Cache'] === 'HIT');
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(1);
}
