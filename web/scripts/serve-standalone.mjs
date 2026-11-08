/**
 * Runs the production standalone server.
 *
 * `output: "standalone"` means `next start` is not supported; the server needs
 * `.next/static` and `public` copied next to it, exactly like the Dockerfile does.
 * Run `npm run build` first.
 */
import { spawn } from "node:child_process";
import { cpSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const standalone = join(root, ".next", "standalone");
const server = join(standalone, "server.js");

if (!existsSync(server)) {
  console.error("No standalone build found. Run `npm run build` first.");
  process.exit(1);
}

cpSync(join(root, ".next", "static"), join(standalone, ".next", "static"), { recursive: true });
if (existsSync(join(root, "public"))) {
  cpSync(join(root, "public"), join(standalone, "public"), { recursive: true });
}

const env = { ...process.env, PORT: process.env.PORT ?? "3000", HOSTNAME: "0.0.0.0" };
const child = spawn(process.execPath, [server], { stdio: "inherit", env });

child.on("exit", (code) => process.exit(code ?? 0));
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}
