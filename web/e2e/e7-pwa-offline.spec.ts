import { expect, test } from "@playwright/test";
import {
  API_BASE,
  WEB_BASE,
  cachedPrivateUrls,
  createGroup,
  createSeries,
  credentials,
  outboxCount,
  registerViaUi,
} from "./support/api";

async function waitForServiceWorker(page: import("@playwright/test").Page) {
  await expect
    .poll(
      async () =>
        page.evaluate(async () => {
          if (!("serviceWorker" in navigator)) {
            return false;
          }
          const registration = await navigator.serviceWorker.ready;
          return Boolean(registration.active);
        }),
      { timeout: 30_000 },
    )
    .toBe(true);
}

async function waitForDiscoveryCache(page: import("@playwright/test").Page) {
  await expect
    .poll(
      async () =>
        page.evaluate(async () => {
          for (const name of await caches.keys()) {
            const cache = await caches.open(name);
            for (const request of await cache.keys()) {
              if (request.url.includes("/v1/activities")) {
                return true;
              }
            }
          }
          return false;
        }),
      { timeout: 30_000 },
    )
    .toBe(true);
}

test("E7a: PWA manifest, service worker and cached discovery render offline", async ({
  page,
  request,
  context,
}) => {
  await registerViaUi(page, credentials("pwa"));
  await expect(page.locator('a[href^="/activities/"]').first()).toBeVisible();

  const manifestResponse = await request.get(`${WEB_BASE}/manifest.webmanifest`);
  expect(manifestResponse.status()).toBe(200);
  const manifest = await manifestResponse.json();
  expect(manifest.name).toContain("Kinship");
  expect(manifest.display).toBe("standalone");
  expect(manifest.start_url).toBe("/");
  expect(manifest.icons.length).toBeGreaterThanOrEqual(2);

  const serviceWorker = await request.get(`${WEB_BASE}/sw.js`);
  expect(serviceWorker.status()).toBe(200);
  expect(serviceWorker.headers()["cache-control"]).toContain("no-store");

  await waitForServiceWorker(page);
  await waitForDiscoveryCache(page);

  // No authenticated or private response may be stored in any cache.
  expect(await cachedPrivateUrls(page)).toEqual([]);

  // Browser storage holds no token and no user data; the only cookie is the
  // httpOnly refresh cookie set by the API.
  const storageKeys = await page.evaluate(() => [
    ...Object.keys(localStorage),
    ...Object.keys(sessionStorage),
  ]);
  expect(storageKeys).toEqual([]);
  const cookies = await context.cookies();
  expect(cookies.map((cookie) => cookie.name)).toEqual(["kinship_rt"]);
  expect(cookies[0].httpOnly).toBe(true);

  // Mobile layout does not overflow horizontally.
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(1);

  // Offline: the shell and the cached discovery page still render.
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText(/offline/i).first()).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('a[href^="/activities/"]').first()).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(/Showing a saved copy/)).toBeVisible();
  await context.setOffline(false);
});

test("E7b: an RSVP made offline is queued once and flushed on reconnect", async ({
  page,
  request,
  context,
}) => {
  const creds = credentials("queue");
  const token = await registerViaUi(page, creds);
  const group = await createGroup(request, token, {
    name: `E2E offline ${Date.now().toString(36)}`,
  });
  const series = await createSeries(request, token, group.group_id);

  await waitForServiceWorker(page);
  await page.goto(`/events/${series.eventId}`);
  await expect(page.getByRole("button", { name: "RSVP going" })).toBeVisible();
  await expect(page.getByText("0/6")).toBeVisible();

  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole("button", { name: "RSVP going" })).toBeVisible({ timeout: 20_000 });
  await page.getByRole("button", { name: "RSVP going" }).click();
  await expect(page.getByText(/Saved offline/)).toBeVisible();
  await expect(page.getByText("RSVP queued")).toBeVisible();
  expect(await outboxCount(page)).toBe(1);

  await context.setOffline(false);

  await expect
    .poll(
      async () => {
        const response = await request.get(`${API_BASE}/v1/events/${series.eventId}`, {
          headers: { Authorization: `Bearer ${token}` },
        });
        return (await response.json()).my_status;
      },
      { timeout: 45_000 },
    )
    .toBe("GOING");

  expect(await outboxCount(page)).toBe(0);
  await expect(page.getByText("1/6")).toBeVisible({ timeout: 45_000 });
});
