/**
 * P6 matching workload: one requester against the 5,000 seeded candidates.
 *
 * Run after `perf/seed-5k.sql`:
 *   node perf/match-5k.mjs
 *
 * Prints request → QUEUED, claim → DONE and total wall time, then writes
 * perf/results/match-5k.json. Exits non-zero if the job does not complete or
 * finishes outside the 5 minute acceptance target.
 */
import { writeFileSync, mkdirSync } from "node:fs";

const API = process.env.API ?? "http://localhost:8080";
const output = "perf/results/match-5k.json";

async function api(path, { token, method = "GET", body } = {}) {
  const response = await fetch(API + path, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // empty body
  }
  return { status: response.status, json, text };
}

const email = `perf-match-${Date.now().toString(36)}@perf.kinship.local`;
const password = "PerfMatch12345!";
const register = await api("/v1/auth/register", {
  method: "POST",
  body: { email, password, display_name: "Perf Matcher", timezone: "Europe/Berlin" },
});
if (register.status !== 201) {
  console.error("register failed", register.status, register.text);
  process.exit(1);
}
const token = register.json.access_token;

const prefs = await api("/v1/users/me", {
  token,
  method: "PATCH",
  body: {
    location: { lat: 52.52, lng: 13.405 },
    availability: [{ day_of_week: 4, start_local: "18:00", end_local: "21:00" }],
    interests: [{ interest_id: 1, weight: 1 }, { interest_id: 2, weight: 1 }],
  },
});
if (prefs.status !== 200) {
  console.error("profile update failed", prefs.status, prefs.text);
  process.exit(1);
}

const requestedAt = performance.now();
const request = await api("/v1/match-requests", {
  token,
  method: "POST",
  body: { max_distance_km: 10, preferred_group_size: 4 },
});
if (![200, 202].includes(request.status)) {
  console.error("match request failed", request.status, request.text);
  process.exit(1);
}
const jobId = request.json.job_id;
const queuedAt = performance.now();

let job = null;
let attempts = 0;
while (performance.now() - requestedAt < 5 * 60 * 1000) {
  attempts += 1;
  const poll = await api(`/v1/match-requests/${jobId}`, { token });
  job = poll.json;
  if (poll.status !== 200) {
    console.error("poll failed", poll.status, poll.text);
    process.exit(1);
  }
  if (["DONE", "FAILED"].includes(job.status)) {
    break;
  }
  await new Promise((resolve) => setTimeout(resolve, 500));
}
const finishedAt = performance.now();

const invitations = await api("/v1/match-invitations", { token });
const report = {
  jobId,
  requester: email,
  status: job?.status ?? "TIMEOUT",
  errorCode: job?.error_code ?? null,
  results: job?.results?.length ?? 0,
  invitations: invitations.json?.items?.length ?? 0,
  pollAttempts: attempts,
  queueMs: Math.round(queuedAt - requestedAt),
  totalMs: Math.round(finishedAt - requestedAt),
  withinFiveMinutes: finishedAt - requestedAt < 5 * 60 * 1000,
  checkedAt: new Date().toISOString(),
};

mkdirSync("perf/results", { recursive: true });
writeFileSync(output, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
process.exit(job?.status === "DONE" && report.withinFiveMinutes ? 0 : 1);
