import http from 'k6/http';
import { check } from 'k6';

export const API = __ENV.API || 'http://api:8080';
export const BERLIN = { lat: 52.52, lng: 13.405 };
const PASSWORD = __ENV.PASSWORD || 'Load12345!';

// open() is only available during init, so the account file is read here once.
const ACCOUNTS = JSON.parse(open(__ENV.USERS_FILE || '/perf/.users.json'));
let token = null;
let issuedAt = 0;

/** Each VU keeps one token and refreshes it before the 15 minute access TTL. */
export function authToken() {
  if (token !== null && Date.now() - issuedAt < 10 * 60 * 1000) {
    return token;
  }
  const account = ACCOUNTS[(__VU - 1) % ACCOUNTS.length];
  const response = http.post(`${API}/v1/auth/login`, JSON.stringify({
    email: account.email,
    password: PASSWORD,
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'login' },
  });
  check(response, { 'login 200': (r) => r.status === 200 });
  token = response.json('access_token');
  issuedAt = Date.now();
  return token;
}

export function headers() {
  return { Authorization: `Bearer ${authToken()}` };
}

export function discovery() {
  return http.get(
    `${API}/v1/activities?lat=${BERLIN.lat}&lng=${BERLIN.lng}&radius_km=10&limit=20`,
    { headers: headers(), tags: { name: 'discovery' } },
  );
}

export function me() {
  return http.get(`${API}/v1/users/me`, { headers: headers(), tags: { name: 'me' } });
}

export function interestIds(token) {
  const response = http.get(`${API}/v1/interests`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'interests' },
  });
  return response.json('items');
}
