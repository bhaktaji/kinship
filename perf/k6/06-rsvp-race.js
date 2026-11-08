import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { check } from 'k6';

/**
 * L6 — one event with two free seats, nineteen members RSVP at the same instant.
 * The invariant is exactly two GOING responses; every other attempt must 409.
 *
 * The seeded accounts carry 15 minute access tokens, so setup logs in again
 * rather than relying on the token written when the accounts were created.
 */
const success = new Counter('rsvp_success');
const conflict = new Counter('rsvp_conflict');
const unexpected = new Counter('rsvp_unexpected');
const RACERS = 19; // the owner occupies one of max_members=20 seats

const API = __ENV.API || 'http://api:8080';
const PASSWORD = __ENV.PASSWORD || 'Load12345!';
const accounts = JSON.parse(open(__ENV.USERS_FILE || '/perf/.users.json'));

export const options = {
  scenarios: {
    race: { executor: 'per-vu-iterations', vus: RACERS, iterations: 1, maxDuration: '1m' },
  },
};

function post(path, token, body) {
  return http.post(`${API}${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
  });
}

function login(account) {
  const response = http.post(`${API}/v1/auth/login`, JSON.stringify({
    email: account.email,
    password: PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });
  if (response.status !== 200) {
    throw new Error(`login failed for ${account.email}: ${response.status}`);
  }
  return { ...account, token: response.json('access_token') };
}

export function setup() {
  const owner = login(accounts[0]);
  const group = http.post(`${API}/v1/groups`, JSON.stringify({
    name: `Race ${Date.now()}`,
    category: 'sport',
    max_members: 20,
    visibility: 'public',
    location: { lat: 52.52, lng: 13.405 },
  }), {
    headers: { Authorization: `Bearer ${owner.token}`, 'Content-Type': 'application/json' },
  });
  check(group, { 'group created': (r) => r.status === 201 });
  const groupId = group.json('group.group_id');

  const racers = accounts.slice(1, RACERS + 1).map(login);
  for (const racer of racers) {
    const joined = post(`/v1/groups/${groupId}/join`, racer.token);
    if (joined.status !== 200) {
      throw new Error(`fixture join failed ${joined.status} ${joined.body}`);
    }
  }

  const startsOn = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  const codes = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
  const day = codes[new Date(`${startsOn}T12:00:00Z`).getUTCDay()];
  const series = http.post(`${API}/v1/groups/${groupId}/events`, JSON.stringify({
    title: `Race meetup ${Date.now()}`,
    timezone: 'Europe/Berlin',
    capacity: 2,
    recurrence: {
      freq: 'WEEKLY', interval: 1, byday: [day], start_time: '19:00', starts_on: startsOn,
    },
  }), {
    headers: { Authorization: `Bearer ${owner.token}`, 'Content-Type': 'application/json' },
  });
  check(series, { 'series created': (r) => r.status === 201 });
  return { groupId, eventId: series.json('occurrences.0.event_id'), racers, ownerToken: owner.token };
}

export default function (data) {
  const racer = data.racers[(__VU - 1) % data.racers.length];
  const response = post(`/v1/events/${data.eventId}/rsvp`, racer.token, { status: 'GOING' });
  if (response.status === 200) {
    success.add(1);
  } else if (response.status === 409 && response.json('error.code') === 'EVENT_FULL') {
    conflict.add(1);
  } else {
    unexpected.add(1);
    console.error(`unexpected rsvp ${response.status} ${response.body}`);
  }
}

export function teardown(data) {
  const response = http.get(`${API}/v1/events/${data.eventId}`, {
    headers: { Authorization: `Bearer ${data.ownerToken}` },
  });
  console.log(`confirmed_count=${response.json('confirmed_count')} capacity=${response.json('capacity')}`);
}
