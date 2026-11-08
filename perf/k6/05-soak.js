import { sleep } from 'k6';
import { discovery, me } from './lib.js';

// The P6 acceptance target is a 2 hour soak; this run is parameterised so it can
// be reproduced at that length (DURATION=2h) without editing the script.
export const options = {
  scenarios: {
    soak: { executor: 'constant-vus', vus: 25, duration: __ENV.DURATION || '30m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{name:discovery}': ['p(95)<300'],
  },
};

export default function () {
  discovery();
  me();
  sleep(1);
}
