/**
 * Realtime load and cross-instance delivery probe.
 *
 * Usage (servers must already be running):
 *   node perf/ws-load.mjs --sockets=50 --connect=http://localhost:8080 --send-via=http://localhost:8080
 *
 * Opens N authenticated group sockets, sends messages over HTTP, and reports
 * delivery count and per-message latency. It then reconnects a subset to prove
 * ticket re-issue and backfill after a drop. Exits non-zero if any expectation
 * fails so the result is usable as evidence rather than prose.
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";

const args = Object.fromEntries(
  process.argv.slice(2).map((item) => {
    const [key, value] = item.replace(/^--/, "").split("=");
    return [key, value ?? "true"];
  }),
);
const sockets = Number(args.sockets ?? 50);
const connectBase = args.connect ?? "http://localhost:8080";
const sendBase = args["send-via"] ?? connectBase;
const password = args.password ?? "Load12345!";
const output = args.out ?? "perf/results/ws-load.json";
const userFile = "./.users.json";

const accounts = JSON.parse(await import("node:fs").then(({ readFileSync }) =>
  readFileSync(new URL(userFile, import.meta.url), "utf8"),
));

async function api(base, path, { token, method = "GET", body } = {}) {
  const response = await fetch(base + path, {
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
    // empty or non-JSON body
  }
  return { status: response.status, json, text };
}

async function login(account) {
  const response = await api(connectBase, "/v1/auth/login", {
    method: "POST",
    body: { email: account.email, password },
  });
  if (response.status !== 200) {
    throw new Error(`login failed for ${account.email}: ${response.status}`);
  }
  return { ...account, token: response.json.access_token };
}

const owner = await login(accounts[0]);
const group = await api(connectBase, "/v1/groups", {
  token: owner.token,
  method: "POST",
  body: {
    name: `WS load ${Date.now()}`,
    category: "sport",
    max_members: 20,
    visibility: "public",
    location: { lat: 52.52, lng: 13.405 },
  },
});
if (group.status !== 201) {
  console.error("group creation failed", group.status, group.text);
  process.exit(1);
}
const groupId = group.json.group.group_id;

// Members beyond the owner join so each socket can carry a distinct identity.
const members = [];
for (const account of accounts.slice(1, 19)) {
  const member = await login(account);
  const joined = await api(connectBase, `/v1/groups/${groupId}/join`, { token: member.token, method: "POST" });
  if (joined.status !== 200) {
    throw new Error(`fixture join failed ${joined.status} ${joined.text}`);
  }
  members.push(member);
}
const participants = [owner, ...members];

async function connect(account) {
  const ticket = await api(connectBase, "/v1/realtime/tickets", {
    token: account.token,
    method: "POST",
    body: { group_id: groupId },
  });
  if (ticket.status !== 201) {
    throw new Error(`ticket ${ticket.status} ${ticket.text}`);
  }
  const url = connectBase.replace(/^http/, "ws") + `/v1/ws?ticket=${ticket.json.ticket}`;
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const state = { socket, frames: [], readyAt: 0, connectedAt: 0, error: null };
    socket.addEventListener("open", () => {
      state.connectedAt = performance.now();
    });
    socket.addEventListener("message", (event) => {
      const frame = JSON.parse(event.data);
      state.frames.push({ frame, at: performance.now() });
      if (frame.type === "ready" && !state.readyAt) {
        state.readyAt = performance.now();
        resolve(state);
      }
    });
    socket.addEventListener("error", (event) => {
      state.error = event.message ?? "socket error";
      reject(new Error(state.error));
    });
    socket.addEventListener("close", (event) => {
      state.closed = { code: event.code, at: performance.now() };
    });
    setTimeout(() => reject(new Error("ready timeout")), 10_000);
  });
}

const started = performance.now();
const sessions = [];
for (let index = 0; index < sockets; index += 1) {
  sessions.push(await connect(participants[index % participants.length]));
}
const connectMs = performance.now() - started;
const readyCount = sessions.filter((session) => session.frames.some((item) => item.frame.type === "ready")).length;

const results = [];
for (let round = 0; round < 3; round += 1) {
  const clientMsgId = crypto.randomUUID();
  const sentAt = performance.now();
  const sent = await api(sendBase, `/v1/groups/${groupId}/messages`, {
    token: owner.token,
    method: "POST",
    body: { body: `ws-load round ${round}`, client_msg_id: clientMsgId },
  });
  if (sent.status !== 201) {
    results.push({ round, sendStatus: sent.status, received: 0 });
    continue;
  }
  const messageId = sent.json.message_id;
  await new Promise((resolve) => setTimeout(resolve, 3000));
  const latencies = [];
  for (const session of sessions) {
    const hit = session.frames.find((item) => item.frame.message_id === messageId);
    if (hit) {
      latencies.push(hit.at - sentAt);
    }
  }
  latencies.sort((a, b) => a - b);
  results.push({
    round,
    sendStatus: sent.status,
    sentAt,
    received: latencies.length,
    expected: sessions.length,
    p50: latencies[Math.floor(latencies.length * 0.5)] ?? null,
    p95: latencies[Math.floor(latencies.length * 0.95)] ?? null,
    max: latencies.at(-1) ?? null,
  });
}

// Reconnect half of the sockets with fresh tickets and confirm history backfill.
const reconnectTargets = sessions.slice(0, Math.max(1, Math.floor(sessions.length / 2)));
for (const session of reconnectTargets) {
  session.socket.close();
}
await new Promise((resolve) => setTimeout(resolve, 500));
const reconnected = [];
for (let index = 0; index < reconnectTargets.length; index += 1) {
  reconnected.push(await connect(participants[index % participants.length]));
}
const latest = await api(sendBase, `/v1/groups/${groupId}/messages?limit=1`, { token: owner.token });
const currentSeq = latest.json.items[0]?.sequence_id ?? 0;
const backfilled = reconnected.filter((session) =>
  session.frames.some((item) => item.frame.type === "ready" && item.frame.last_sequence_id === currentSeq),
).length;

const totalReceived = results.reduce((sum, item) => sum + item.received, 0);
const totalExpected = results.reduce((sum, item) => sum + item.expected, 0);
const report = {
  groupId,
  connectBase,
  sendBase,
  sockets,
  connectMs: Math.round(connectMs),
  readyCount,
  rounds: results,
  delivery: { received: totalReceived, expected: totalExpected },
  reconnect: { sockets: reconnected.length, backfilled, currentSeq },
  checkedAt: new Date().toISOString(),
};

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));

for (const session of [...sessions, ...reconnected]) {
  session.socket.close();
}

const ok =
  readyCount === sockets &&
  totalReceived === totalExpected &&
  results.every((item) => item.sendStatus === 201) &&
  backfilled === reconnected.length;
process.exit(ok ? 0 : 1);
