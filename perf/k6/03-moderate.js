import { sleep } from 'k6';
import { discovery, me } from './lib.js';

export const options = {
  scenarios: {
    moderate: { executor: 'constant-vus', vus: 50, duration: __ENV.DURATION || '5m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.005'],
    'http_req_duration{name:discovery}': ['p(95)<300'],
  },
};

export default function () {
  discovery();
  me();
  sleep(0.3);
}
