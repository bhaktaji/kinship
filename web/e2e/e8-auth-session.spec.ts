import { expect, test } from "@playwright/test";
import {
  API_BASE,
  PASSWORD,
  WEB_BASE,
  credentials,
  loginViaUi,
  registerViaApi,
  registerViaUi,
} from "./support/api";

function b64url(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

test("E8a: registration and login reject invalid input with stable errors", async ({ page, request }) => {
  const existing = credentials("e8dup");
  await registerViaApi(request, existing);

  await page.goto(`${WEB_BASE}/register`);
  await page.locator('input[autocomplete="nickname"]').fill("E2E Duplicate");
  await page.locator('input[type="email"]').fill(existing.email);
  await page.locator('input[type="password"]').fill(existing.password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByText("Email is already registered")).toBeVisible();

  // API validation for the fields the browser cannot police.
  const shortPassword = await request.post(`${API_BASE}/v1/auth/register`, {
    data: {
      email: `short-${Date.now().toString(36)}@e2e.kinship.local`,
      password: "short1",
      display_name: "Short",
      timezone: "Europe/Berlin",
    },
  });
  expect(shortPassword.status()).toBe(400);
  expect((await shortPassword.json()).error.code).toBe("VALIDATION_ERROR");

  const badEmail = await request.post(`${API_BASE}/v1/auth/register`, {
    data: {
      email: "not-an-email",
      password: PASSWORD,
      display_name: "Bad",
      timezone: "Europe/Berlin",
    },
  });
  expect(badEmail.status()).toBe(400);

  // Wrong password and unknown account are indistinguishable.
  const wrong = await request.post(`${API_BASE}/v1/auth/login`, {
    data: { email: existing.email, password: "Wrong12345!" },
  });
  const unknown = await request.post(`${API_BASE}/v1/auth/login`, {
    data: { email: `nobody-${Date.now().toString(36)}@e2e.kinship.local`, password: PASSWORD },
  });
  expect(wrong.status()).toBe(401);
  expect(unknown.status()).toBe(401);
  expect((await wrong.json()).error.message).toBe((await unknown.json()).error.message);

  // The login form surfaces the same message and stays on the page.
  await page.goto(`${WEB_BASE}/login`);
  await page.locator('input[type="email"]').fill(existing.email);
  await page.locator('input[type="password"]').fill("Wrong12345!");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Invalid email or password")).toBeVisible();
  await expect(page).toHaveURL(`${WEB_BASE}/login`);

  // Valid credentials sign in, and a reload restores the session from the cookie.
  await loginViaUi(page, existing);
  await page.reload();
  await expect(page.getByRole("heading", { name: /Find a small group/ })).toBeVisible();
});

test("E8b: refresh tokens rotate once and replay revokes the family", async ({ playwright }) => {
  const ctx = await playwright.request.newContext({ baseURL: API_BASE });
  const creds = credentials("e8rt");
  const registered = await ctx.post("/v1/auth/register", {
    data: {
      email: creds.email,
      password: creds.password,
      display_name: creds.displayName,
      timezone: "Europe/Berlin",
    },
  });
  expect(registered.status()).toBe(201);
  const original = (registered.headers()["set-cookie"] ?? "").split(";")[0];
  expect(original).toContain("kinship_rt=");

  const rotated = await ctx.post("/v1/auth/refresh");
  expect(rotated.status()).toBe(200);
  const access = (await rotated.json()).access_token as string;

  // Replaying the rotated cookie in a clean context is treated as theft.
  const replayCtx = await playwright.request.newContext({ baseURL: API_BASE });
  const replay = await replayCtx.post("/v1/auth/refresh", { headers: { Cookie: original } });
  expect(replay.status()).toBe(401);

  // Reuse detection revokes the whole family, including the token the rotation
  // just issued.
  expect((await ctx.post("/v1/auth/refresh")).status()).toBe(401);

  // Documented boundary: an access token is a short-lived bearer credential and
  // is not revoked by refresh-token reuse; it dies with its 15 minute TTL.
  const me = await replayCtx.get("/v1/users/me", { headers: { Authorization: `Bearer ${access}` } });
  expect(me.status()).toBe(200);

  await ctx.dispose();
  await replayCtx.dispose();
});

test("E8c: logout invalidates the session and forged tokens are rejected", async ({ playwright }) => {
  const ctx = await playwright.request.newContext({ baseURL: API_BASE });
  const creds = credentials("e8lo");
  const registered = await ctx.post("/v1/auth/register", {
    data: {
      email: creds.email,
      password: creds.password,
      display_name: creds.displayName,
      timezone: "Europe/Berlin",
    },
  });
  expect(registered.status()).toBe(201);
  const body = await registered.json();
  const cookie = (registered.headers()["set-cookie"] ?? "").split(";")[0];

  const after = await playwright.request.newContext({ baseURL: API_BASE });
  expect((await ctx.post("/v1/auth/logout")).status()).toBe(204);
  expect((await after.post("/v1/auth/refresh", { headers: { Cookie: cookie } })).status()).toBe(401);

  const unsigned = `${b64url({ alg: "none", typ: "JWT" })}.${b64url({
    sub: body.user.id,
    iss: "kinship",
    exp: Math.floor(Date.now() / 1000) + 600,
  })}.`;
  expect(
    (
      await after.get("/v1/users/me", { headers: { Authorization: `Bearer ${unsigned}` } })
    ).status(),
  ).toBe(401);

  const tampered = `${body.access_token.slice(0, -2)}${body.access_token.endsWith("A") ? "BB" : "AA"}`;
  expect(
    (
      await after.get("/v1/users/me", { headers: { Authorization: `Bearer ${tampered}` } })
    ).status(),
  ).toBe(401);

  await ctx.dispose();
  await after.dispose();
});

test("E8d: signing out clears the browser session and survives a reload", async ({ page }) => {
  const creds = credentials("e8ui");
  await registerViaUi(page, creds);
  await page.getByRole("button", { name: "Sign out" }).click();
  // Signing out keeps the current page and switches the header to signed-out.
  await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign out" })).toHaveCount(0);

  const cookies = await page.context().cookies();
  const refresh = cookies.find((cookie) => cookie.name === "kinship_rt");
  expect(refresh?.value ?? "").toBe("");

  await page.goto("/groups");
  await expect(page.getByText("Sign in to see your groups")).toBeVisible();
  await page.reload();
  await expect(page.getByText("Sign in to see your groups")).toBeVisible();
});
