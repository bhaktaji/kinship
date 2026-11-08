import { check, sleep } from 'k6';
import { discovery, me } from './lib.js';

export const options = {
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 5, duration: __ENV.DURATION || '1m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:discovery}': ['p(95)<300'],
  },
};

export default function () {
  const page = discovery();
  check(page, { 'discovery 200': (r) => r.status === 200, 'has items': (r) => JSON.parse(r.body).items.length > 0 });
  const profile = me();
  check(profile, { 'me 200': (r) => r.status === 200 });
  sleep(1);
}
