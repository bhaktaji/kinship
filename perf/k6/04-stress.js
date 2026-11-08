import { sleep } from 'k6';
import { discovery, me } from './lib.js';

export const options = {
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '1m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '2m', target: 200 },
        { duration: '1m', target: 200 },
        { duration: '1m', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:discovery}': ['p(95)<1000'],
  },
};

export default function () {
  discovery();
  me();
  sleep(0.2);
}
