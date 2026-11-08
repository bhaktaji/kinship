import { sleep } from 'k6';
import { discovery, me } from './lib.js';

export const options = {
  scenarios: {
    baseline: { executor: 'constant-vus', vus: 20, duration: __ENV.DURATION || '3m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{name:discovery}': ['p(95)<300'],
    'http_req_duration{name:me}': ['p(95)<300'],
  },
};

export default function () {
  discovery();
  me();
  sleep(0.5);
}
