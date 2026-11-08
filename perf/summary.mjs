/** Prints a compact table for every k6 summary export in perf/results. */
import { readdirSync, readFileSync } from "node:fs";

const dir = "perf/results";
const files = readdirSync(dir).filter((name) => name.endsWith(".json") && /^\d\d-/.test(name)).sort();

const pick = (metrics, key) => metrics[key] ?? null;
const sec = (value) => (value === null || value === undefined ? "-" : `${Math.round(value * 100) / 100}`);

for (const file of files) {
  const summary = JSON.parse(readFileSync(`${dir}/${file}`, "utf8"));
  const metrics = summary.metrics;
  const reqs = pick(metrics, "http_reqs");
  const failed = pick(metrics, "http_req_failed");
  const all = pick(metrics, "http_req_duration");
  const discovery = pick(metrics, "http_req_duration{name:discovery}");
  const me = pick(metrics, "http_req_duration{name:me}");
  const checks = summary.root_group?.checks ?? {};
  const checkTotal = Object.values(checks).reduce((sum, item) => sum + (item.passes ?? 0) + (item.fails ?? 0), 0);
  const checkFails = Object.values(checks).reduce((sum, item) => sum + (item.fails ?? 0), 0);
  console.log([
    file.padEnd(22),
    `reqs=${reqs?.count ?? "-"}`.padEnd(14),
    `fail=${failed?.rate ?? "-"}`.padEnd(10),
    `p95=${sec(all?.["p(95)"])}ms`.padEnd(14),
    `discovery_p95=${sec(discovery?.["p(95)"])}ms`.padEnd(24),
    `me_p95=${sec(me?.["p(95)"])}ms`.padEnd(16),
    checkTotal ? `checks=${checkTotal - checkFails}/${checkTotal}` : "",
  ].join(""));
}
