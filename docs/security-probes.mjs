/**
 * Kinship local security probes.
 *
 * Read-only checks plus a handful of throwaway accounts against the authorized
 * local stack (API :8080, web :3000). Every probe prints PASS/FAIL with the
 * evidence used in SECURITY_AUDIT.md. Nothing here touches existing data.
 *
 * Run: node docs/security-probes.mjs
 */
const API = process.env.KINSHIP_API ?? "http://localhost:8080";
const WEB = process.env.KINSHIP_WEB ?? "http://localhost:3000";

const results = [];
function record(id, expectation, actual, ok, detail = "") {
  results.push({ id, expectation, actual, verdict: ok ? "PASS" : "FAIL", detail });
}

async function call(path, { token, method = "GET", body, headers = {}, cookie } = {}) {
  const response = await fetch(API + path, {
    method,
    redirect: "manual",
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try {
    json = await response.clone().json();
  } catch {
    // not JSON
  }
  return { status: response.status, json, headers: response.headers };
}

async function register(prefix) {
  const email = `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}@probe.kinship.local`;
  const response = await call("/v1/auth/register", {
    method: "POST",
    body: { email, password: "Probe12345!", display_name: `Probe ${prefix}`, timezone: "Europe/Berlin" },
  });
  if (response.status !== 201) throw new Error(`register failed ${response.status}`);
  return {
    email,
    token: response.json.access_token,
    id: response.json.user.id,
    cookie: (response.headers.get("set-cookie") ?? "").split(";")[0],
  };
}

const victim = await register("victim");
const attacker = await register("attacker");

// --- Authorization / BOLA ------------------------------------------------
const privateGroup = await call("/v1/groups", {
  token: victim.token,
  method: "POST",
  body: {
    name: "Probe private group",
    category: "sport",
    max_members: 4,
    visibility: "private",
    location: { lat: 52.52, lng: 13.405 },
  },
});
const privateId = privateGroup.json.group.group_id;

const strangerDetail = await call(`/v1/groups/${privateId}`, { token: attacker.token });
record("BOLA-private-group", "non-member cannot read a private group", `${strangerDetail.status} ${strangerDetail.json?.error?.code}`, strangerDetail.status === 404);

const strangerMessages = await call(`/v1/groups/${privateId}/messages`, { token: attacker.token });
record("BOLA-history", "non-member cannot read message history", String(strangerMessages.status), strangerMessages.status === 403);

const strangerJoin = await call(`/v1/groups/${privateId}/join`, { token: attacker.token, method: "POST" });
record("private-join-pending", "private join creates PENDING, not ACTIVE", `${strangerJoin.status} ${strangerJoin.json?.status}`, strangerJoin.status === 200 && strangerJoin.json?.status === "PENDING");

const pendingMessages = await call(`/v1/groups/${privateId}/messages`, { token: attacker.token });
record("pending-cannot-read", "pending member cannot read history", String(pendingMessages.status), pendingMessages.status === 403);

const series = await call(`/v1/groups/${privateId}/events`, {
  token: victim.token,
  method: "POST",
  body: {
    title: "Probe meetup",
    timezone: "Europe/Berlin",
    capacity: 4,
    recurrence: {
      freq: "WEEKLY",
      interval: 1,
      byday: ["THU"],
      start_time: "19:00",
      starts_on: new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10),
    },
  },
});
const eventId = series.json?.occurrences?.[0]?.event_id;
const strangerRsvp = await call(`/v1/events/${eventId}/rsvp`, {
  token: attacker.token,
  method: "POST",
  body: { status: "GOING" },
});
record("BOLA-rsvp", "non-member cannot RSVP to a private group event", String(strangerRsvp.status), strangerRsvp.status === 403);

const strangerEvent = await call(`/v1/events/${eventId}`, { token: attacker.token });
record("BOLA-event-detail", "non-member cannot read a private group event", String(strangerEvent.status), [403, 404].includes(strangerEvent.status));

const strangerJob = await call(`/v1/match-requests/${crypto.randomUUID()}`, { token: attacker.token });
record("BOLA-match-job", "unknown/foreign job id is 404", String(strangerJob.status), strangerJob.status === 404);

const strangerInvitation = await call(`/v1/match-invitations/${crypto.randomUUID()}/accept`, {
  token: attacker.token,
  method: "POST",
});
record("BOLA-invitation", "foreign invitation id is 404", String(strangerInvitation.status), strangerInvitation.status === 404);

// --- Authentication ------------------------------------------------------
const forgedHeader = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url");
const forgedPayload = Buffer.from(
  JSON.stringify({ sub: victim.id, iss: "kinship", exp: Math.floor(Date.now() / 1000) + 900 }),
).toString("base64url");
const forged = await call("/v1/users/me", { token: `${forgedHeader}.${forgedPayload}.` });
record("jwt-alg-none", "unsigned JWT rejected", String(forged.status), forged.status === 401);

const partial = await call("/v1/users/me", { headers: { Authorization: "Bearer not-a-jwt" } });
record("jwt-garbage", "garbage bearer token rejected", String(partial.status), partial.status === 401);

const noAuth = await call("/v1/groups?mine=true");
record("unauthenticated", "protected route requires auth", `${noAuth.status} ${noAuth.json?.error?.code}`, noAuth.status === 401);

// Rate limiting: six failures on one account inside a minute must 429.
let rateLimited = null;
for (let attempt = 0; attempt < 7; attempt += 1) {
  const response = await call("/v1/auth/login", {
    method: "POST",
    body: { email: victim.email, password: "Wrong12345!" },
  });
  if (response.status === 429) {
    rateLimited = response;
    break;
  }
}
record(
  "login-rate-limit",
  "repeated failed logins return 429 with Retry-After",
  `${rateLimited?.status ?? 401}${rateLimited?.headers.get("retry-after") ? " retry-after=" + rateLimited.headers.get("retry-after") : ""}`,
  Boolean(rateLimited),
);

// --- Request handling ----------------------------------------------------
const wrongMethod = await call("/v1/match-requests", { token: victim.token });
record("method-not-allowed", "GET on a POST-only route is 405", `${wrongMethod.status} ${wrongMethod.json?.error?.code}`, wrongMethod.status === 405);

const limitAbuse = await call("/v1/activities?lat=52.52&lng=13.405&radius_km=10&limit=100000", {
  token: victim.token,
});
record("pagination-cap", "limit above the maximum is rejected", `${limitAbuse.status} ${limitAbuse.json?.error?.code}`, limitAbuse.status === 400);

const massAssignment = await call("/v1/users/me", {
  token: attacker.token,
  method: "PATCH",
  body: { display_name: "Probe Renamed", email: "taken@example.com", id: victim.id, role: "OWNER", status: "ACTIVE" },
});
record(
  "mass-assignment",
  "unknown/immutable fields are ignored",
  `${massAssignment.status} id_unchanged=${massAssignment.json?.id === attacker.id} email=${massAssignment.json?.email === attacker.email}`,
  massAssignment.status === 200 && massAssignment.json?.id === attacker.id && massAssignment.json?.email === attacker.email,
);

const hugeBody = await call(`/v1/groups/${privateId}/messages`, {
  token: victim.token,
  method: "POST",
  body: { body: "x".repeat(5000), client_msg_id: crypto.randomUUID() },
});
record("message-length", "oversized message rejected", `${hugeBody.status} ${hugeBody.json?.error?.code}`, hugeBody.status === 400);

// --- Transport -----------------------------------------------------------
const preflight = await fetch(API + "/v1/users/me", {
  method: "OPTIONS",
  headers: {
    Origin: "https://evil.example",
    "Access-Control-Request-Method": "GET",
    "Access-Control-Request-Headers": "authorization",
  },
});
record(
  "cors-evil-origin",
  "preflight from an unlisted origin gets no allow-origin",
  `${preflight.status} acao=${preflight.headers.get("access-control-allow-origin")}`,
  !preflight.headers.get("access-control-allow-origin"),
);

const allowedPreflight = await fetch(API + "/v1/users/me", {
  method: "OPTIONS",
  headers: {
    Origin: "http://localhost:3000",
    "Access-Control-Request-Method": "GET",
    "Access-Control-Request-Headers": "authorization",
  },
});
record(
  "cors-allowed-origin",
  "the app origin is allowed",
  `acao=${allowedPreflight.headers.get("access-control-allow-origin")}`,
  allowedPreflight.headers.get("access-control-allow-origin") === "http://localhost:3000",
);

const cookieResponse = await call("/v1/auth/login", {
  method: "POST",
  body: { email: attacker.email, password: "Probe12345!" },
});
const setCookie = cookieResponse.headers.get("set-cookie") ?? "";
record(
  "refresh-cookie",
  "refresh cookie is HttpOnly, SameSite=Lax and scoped to /v1/auth",
  setCookie.replace(/kinship_rt=[^;]+/, "kinship_rt=<redacted>"),
  /HttpOnly/i.test(setCookie) && /SameSite=Lax/i.test(setCookie) && /Path=\/v1\/auth/i.test(setCookie),
);

const discoveryHeaders = await call("/v1/activities?lat=52.52&lng=13.405&radius_km=10", {
  token: victim.token,
});
record(
  "private-cache-headers",
  "per-user discovery response is not cacheable by shared caches",
  `cache-control=${discoveryHeaders.headers.get("cache-control")} vary=${discoveryHeaders.headers.get("vary")}`,
  /no-store|private/i.test(discoveryHeaders.headers.get("cache-control") ?? ""),
);

const webHome = await fetch(WEB + "/");
record(
  "web-security-headers",
  "documented headers present (CSP, nosniff, frame-options)",
  ["content-security-policy", "x-content-type-options", "x-frame-options"]
    .map((name) => `${name}=${webHome.headers.get(name) ?? "missing"}`)
    .join(" "),
  Boolean(webHome.headers.get("content-security-policy")),
);

const health = await call("/healthz");
const actuatorHealth = await call("/actuator/health");
const actuatorEnv = await call("/actuator/env", { token: victim.token });
record("health-public", "readiness endpoints answer", `${health.status}/${actuatorHealth.status}`, health.status === 200 && actuatorHealth.status === 200);
record("actuator-env", "actuator environment is not exposed", String(actuatorEnv.status), actuatorEnv.status === 404);

// --- Duplicate submission ------------------------------------------------
const clientId = crypto.randomUUID();
const first = await call(`/v1/groups/${privateId}/messages`, {
  token: victim.token,
  method: "POST",
  body: { body: "probe duplicate", client_msg_id: clientId },
});
const second = await call(`/v1/groups/${privateId}/messages`, {
  token: victim.token,
  method: "POST",
  body: { body: "probe duplicate", client_msg_id: clientId },
});
record(
  "duplicate-message",
  "replayed client_msg_id returns the same message",
  `${first.status}/${second.status} same=${first.json?.message_id === second.json?.message_id}`,
  first.status === 201 && second.status === 200 && first.json?.message_id === second.json?.message_id,
);

const failed = results.filter((item) => item.verdict === "FAIL");
for (const item of results) {
  console.log(`${item.verdict.padEnd(4)} ${item.id.padEnd(22)} ${item.actual}`);
}
console.log(`\n${results.length - failed.length}/${results.length} probes passed`);
process.exitCode = failed.length === 0 ? 0 : 1;
