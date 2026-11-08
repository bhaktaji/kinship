import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.E2E_PORT ?? 3000);
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${port}`;

/**
 * E2E runs against a production build (the service worker is only registered in
 * production) and a real API with the demo seed. Tests are serialised because
 * they share the seeded database; each test creates its own users and groups.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    ...devices["Pixel 7"],
    viewport: { width: 390, height: 844 },
  },
  projects: [{ name: "mobile-chromium", use: { browserName: "chromium", viewport: { width: 390, height: 844 } } }],
  // Reuse an already-running server (docker compose in CI, `npm run start` locally).
  // Build first: `npm run build`.
  webServer: process.env.E2E_NO_SERVER
    ? undefined
    : {
        command: "npm run start",
        url: baseURL,
        reuseExistingServer: true,
        timeout: 120_000,
      },
});
