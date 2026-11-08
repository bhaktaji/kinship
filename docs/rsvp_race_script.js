import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// L3 — RSVP capacity race.
// Precondition: the event has exactly one free seat (capacity = confirmed + 1)
// and the token belongs to an ACTIVE member of the owning group.
// Run: k6 run -e BASE_URL=... -e EVENT_ID=... -e TOKEN=... docs/rsvp_race_script.js

const success = new Counter('rsvp_success');
const conflict = new Counter('rsvp_conflict');
const unexpected = new Counter('rsvp_unexpected');

export const options = {
  scenarios: {
    rsvp_race: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    rsvp_success: ['count==1'],
    rsvp_conflict: ['count==9'],
    rsvp_unexpected: ['count==0'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate==1'],
  },
};

export default function () {
  const res = http.post(
    `${__ENV.BASE_URL}/v1/events/${__ENV.EVENT_ID}/rsvp`,
    JSON.stringify({ status: 'GOING' }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${__ENV.TOKEN}`,
      },
    }
  );

  if (res.status === 200) success.add(1);
  else if (res.status === 409) conflict.add(1);
  else unexpected.add(1);

  check(res, {
    'no 5xx': (r) => r.status < 500,
    'success or capacity conflict': (r) => r.status === 200 || r.status === 409,
  });
}
