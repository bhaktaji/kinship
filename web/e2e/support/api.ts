import { APIRequestContext, Page, expect } from "@playwright/test";

export const WEB_BASE = process.env.E2E_BASE_URL ?? "http://localhost:3000";
export const API_BASE = process.env.E2E_API_BASE_URL ?? "http://localhost:8080";
export const PASSWORD = "Kinship12345";
export const BERLIN = { lat: 52.52, lng: 13.405 };

export interface Credentials {
  email: string;
  password: string;
  displayName: string;
}

export interface RegisteredUser {
  token: string;
  userId: string;
}

export function credentials(prefix: string): Credentials {
  const id = `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  return {
    email: `${id}@e2e.kinship.local`,
    password: PASSWORD,
    displayName: `E2E ${prefix} ${id.slice(-4)}`,
  };
}

function auth(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

export async function registerViaApi(
  request: APIRequestContext,
  creds: Credentials,
): Promise<RegisteredUser> {
  const response = await request.post(`${API_BASE}/v1/auth/register`, {
    data: {
      email: creds.email,
      password: creds.password,
      display_name: creds.displayName,
      timezone: "Europe/Berlin",
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  return { token: body.access_token as string, userId: body.user.id as string };
}

export async function loginViaApi(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<RegisteredUser> {
  const response = await request.post(`${API_BASE}/v1/auth/login`, {
    data: { email, password },
  });
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  return { token: body.access_token as string, userId: body.user.id as string };
}

export async function interestIds(
  request: APIRequestContext,
  token: string,
): Promise<Record<string, number>> {
  const response = await request.get(`${API_BASE}/v1/interests`, { headers: auth(token) });
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  const map: Record<string, number> = {};
  // The catalogue returns `id`; the profile's saved interests use `interest_id`.
  for (const item of body.items as { slug: string; id: number }[]) {
    map[item.slug] = item.id;
  }
  return map;
}

export interface Availability {
  dayOfWeek: number;
  start: string;
  end: string;
}

export const DEFAULT_AVAILABILITY: Availability = { dayOfWeek: 4, start: "18:00", end: "21:00" };

export async function completeProfile(
  request: APIRequestContext,
  token: string,
  interests: number[],
  availability: Availability = DEFAULT_AVAILABILITY,
): Promise<void> {
  const response = await request.patch(`${API_BASE}/v1/users/me`, {
    headers: auth(token),
    data: {
      location: BERLIN,
      availability: [
        {
          day_of_week: availability.dayOfWeek,
          start_local: availability.start,
          end_local: availability.end,
        },
      ],
      interests: interests.map((id) => ({ interest_id: id, weight: 1 })),
    },
  });
  expect(response.status(), await response.text()).toBe(200);
}

export interface GroupSummary {
  group_id: string;
  name: string;
}

export async function createGroup(
  request: APIRequestContext,
  token: string,
  options: { name: string; visibility?: "public" | "private"; maxMembers?: number } = {
    name: "E2E group",
  },
): Promise<GroupSummary> {
  const response = await request.post(`${API_BASE}/v1/groups`, {
    headers: auth(token),
    data: {
      name: options.name,
      description: "Created by the Playwright suite.",
      category: "sport",
      location: BERLIN,
      max_members: options.maxMembers ?? 6,
      visibility: options.visibility ?? "public",
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  return { group_id: body.group.group_id as string, name: body.group.name as string };
}

export async function joinGroup(
  request: APIRequestContext,
  token: string,
  groupId: string,
): Promise<number> {
  const response = await request.post(`${API_BASE}/v1/groups/${groupId}/join`, {
    headers: auth(token),
  });
  return response.status();
}

export interface CreatedSeries {
  seriesId: string;
  eventId: string;
}

/**
 * Creates a weekly series starting in two days at 19:00 Europe/Berlin and returns
 * the first generated occurrence.
 */
export async function createSeries(
  request: APIRequestContext,
  token: string,
  groupId: string,
  capacity = 6,
): Promise<CreatedSeries> {
  const startsOn = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const codes = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
  const day = codes[new Date(`${startsOn}T12:00:00`).getDay()];
  const response = await request.post(`${API_BASE}/v1/groups/${groupId}/events`, {
    headers: auth(token),
    data: {
      title: "E2E weekly meetup",
      timezone: "Europe/Berlin",
      capacity,
      recurrence: {
        freq: "WEEKLY",
        interval: 1,
        byday: [day],
        start_time: "19:00",
        starts_on: startsOn,
      },
    },
  });
  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  return {
    seriesId: body.event_series_id as string,
    eventId: body.occurrences[0].event_id as string,
  };
}

export interface DiscoveryItem {
  activity_id: string;
  title: string;
  category: string;
  group_id: string;
  next_event?: { event_id: string; start_time: string };
}

export async function discovery(
  request: APIRequestContext,
  token: string,
): Promise<DiscoveryItem[]> {
  const response = await request.get(
    `${API_BASE}/v1/activities?lat=${BERLIN.lat}&lng=${BERLIN.lng}&radius_km=10&limit=20`,
    { headers: auth(token) },
  );
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  return body.items as DiscoveryItem[];
}

export async function requestMatch(request: APIRequestContext, token: string): Promise<string> {
  const response = await request.post(`${API_BASE}/v1/match-requests`, {
    headers: auth(token),
    data: { max_distance_km: 10, preferred_group_size: 4 },
  });
  expect([200, 202]).toContain(response.status());
  const body = await response.json();
  return body.job_id as string;
}

export interface Invitation {
  invitation_id: string;
  match_group_id: string;
  expires_at: string;
  status: string;
}

export async function invitations(
  request: APIRequestContext,
  token: string,
): Promise<Invitation[]> {
  const response = await request.get(`${API_BASE}/v1/match-invitations?status=INVITED`, {
    headers: auth(token),
  });
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  return body.items as Invitation[];
}

export async function acceptInvitation(
  request: APIRequestContext,
  token: string,
  invitationId: string,
): Promise<void> {
  const response = await request.post(`${API_BASE}/v1/match-invitations/${invitationId}/accept`, {
    headers: auth(token),
  });
  expect(response.status(), await response.text()).toBe(200);
}

export async function messageHistory(
  request: APIRequestContext,
  token: string,
  groupId: string,
): Promise<{ body: string; sequence_id: number }[]> {
  const response = await request.get(`${API_BASE}/v1/groups/${groupId}/messages?limit=50`, {
    headers: auth(token),
  });
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  return body.items as { body: string; sequence_id: number }[];
}

/* ------------------------------------------------------------------ */
/* Browser session helpers                                             */
/* ------------------------------------------------------------------ */

export async function registerViaUi(page: Page, creds: Credentials): Promise<string> {
  const tokenResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/v1/auth/register") && response.request().method() === "POST",
  );
  await page.goto(`${WEB_BASE}/register`);
  await page.locator('input[autocomplete="nickname"]').fill(creds.displayName);
  await page.locator('input[type="email"]').fill(creds.email);
  await page.locator('input[type="password"]').fill(creds.password);
  await page.getByRole("button", { name: "Create account" }).click();
  const response = await tokenResponse;
  expect(response.status(), await response.text()).toBe(201);
  const body = await response.json();
  await expect(page).toHaveURL(`${WEB_BASE}/`);
  return body.access_token as string;
}

export async function loginViaUi(page: Page, creds: Credentials): Promise<string> {
  const tokenResponse = page.waitForResponse(
    (response) => response.url().endsWith("/v1/auth/login") && response.request().method() === "POST",
  );
  await page.goto(`${WEB_BASE}/login`);
  await page.locator('input[type="email"]').fill(creds.email);
  await page.locator('input[type="password"]').fill(creds.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  const response = await tokenResponse;
  expect(response.status(), await response.text()).toBe(200);
  const body = await response.json();
  await expect(page).toHaveURL(`${WEB_BASE}/`);
  return body.access_token as string;
}

/** Counts entries in the IndexedDB outbox (lib/outbox.ts) from the page. */
export async function outboxCount(page: Page): Promise<number> {
  return page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open("kinship");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    if (!db.objectStoreNames.contains("outbox")) {
      db.close();
      return 0;
    }
    const count = await new Promise<number>((resolve, reject) => {
      const transaction = db.transaction("outbox", "readonly");
      const request = transaction.objectStore("outbox").count();
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    db.close();
    return count;
  });
}

/** URLs of cached responses that should never be stored (auth, private API). */
export async function cachedPrivateUrls(page: Page): Promise<string[]> {
  return page.evaluate(async () => {
    const found: string[] = [];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      for (const request of await cache.keys()) {
        const path = new URL(request.url).pathname;
        if (
          /^\/v1\/(auth|users|realtime|match)/.test(path) ||
          /\/messages$/.test(path)
        ) {
          found.push(request.url);
        }
      }
    }
    return found;
  });
}
