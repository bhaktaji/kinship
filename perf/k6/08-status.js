import http from 'k6/http';
import { Counter } from 'k6/metrics';

/**
 * Attributes failures by HTTP status for a single endpoint.
 *
 *   -e VUS=50  -e ENDPOINT=/v1/activities  -> exercises the 120/min per-user limiter
 *   -e VUS=200 -e ENDPOINT=/v1/users/me    -> no limiter; isolates Hikari pool behaviour
 */
const API = __ENV.API || 'http://api:8080';
const PASSWORD = __ENV.PASSWORD || 'Load12345!';
const ENDPOINT = __ENV.ENDPOINT || '/v1/users/me';
const accounts = JSON.parse(open(__ENV.USERS_FILE || '/perf/.users.json'));
const counters = {};

function counter(status) {
  const key = `status_${status}`;
  if (!counters[key]) {
    counters[key] = new Counter(key);
  }
  counters[key].add(1);
}

export const options = {
  scenarios: {
    probe: { executor: 'constant-vus', vus: Number(__ENV.VUS || 50), duration: __ENV.DURATION || '60s' },
  },
};

let token = null;
let issuedAt = 0;

function authToken() {
  if (token && Date.now() - issuedAt < 10 * 60 * 1000) {
    return token;
  }
  const account = accounts[(__VU - 1) % accounts.length];
  const response = http.post(`${API}/v1/auth/login`, JSON.stringify({
    email: account.email,
    password: PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });
  token = response.json('access_token');
  issuedAt = Date.now();
  return token;
}

export default function () {
  const url = ENDPOINT === '/v1/activities'
    ? `${API}/v1/activities?lat=52.52&lng=13.405&radius_km=10&limit=20`
    : `${API}${ENDPOINT}`;
  const response = http.get(url, { headers: { Authorization: `Bearer ${authToken()}` } });
  counter(response.status);
}
