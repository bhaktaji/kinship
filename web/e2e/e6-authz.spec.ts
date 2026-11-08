import { expect, test } from "@playwright/test";
import {
  API_BASE,
  createGroup,
  credentials,
  joinGroup,
  loginViaUi,
  registerViaApi,
} from "./support/api";

test("E6a: signed-out users are redirected to sign in and the API rejects them", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Sign in to see activities" })).toBeVisible();

  await page.goto("/groups");
  await expect(page.getByText("Sign in to see your groups")).toBeVisible();

  const unauthenticated = await request.get(
    `${API_BASE}/v1/activities?lat=52.52&lng=13.405&radius_km=10`,
  );
  expect(unauthenticated.status()).toBe(401);
  expect((await unauthenticated.json()).error.code).toBe("UNAUTHENTICATED");
});

test("E6b: private groups and owner-only actions are restricted", async ({ page, request }) => {
  const owner = credentials("e6o");
  const outsider = credentials("e6x");
  const ownerAuth = await registerViaApi(request, owner);
  const outsiderAuth = await registerViaApi(request, outsider);

  const privateGroup = await createGroup(request, ownerAuth.token, {
    name: `E2E private ${Date.now().toString(36)}`,
    visibility: "private",
  });

  const detail = await request.get(`${API_BASE}/v1/groups/${privateGroup.group_id}`, {
    headers: { Authorization: `Bearer ${outsiderAuth.token}` },
  });
  expect([403, 404]).toContain(detail.status());

  await loginViaUi(page, outsider);
  await page.goto(`/groups/${privateGroup.group_id}`);
  await expect(page.getByText(/Group not found, or it is private/)).toBeVisible();

  await page.goto(`/groups/${privateGroup.group_id}/chat`);
  await expect(
    page.getByText(/not an active member|must be an active member/i).first(),
  ).toBeVisible();

  // A plain member cannot manage membership or create owner-only events.
  const publicGroup = await createGroup(request, ownerAuth.token, {
    name: `E2E member ${Date.now().toString(36)}`,
  });
  expect(await joinGroup(request, outsiderAuth.token, publicGroup.group_id)).toBe(200);

  const forbidden = await request.patch(
    `${API_BASE}/v1/groups/${publicGroup.group_id}/members/${outsiderAuth.userId}`,
    {
      headers: { Authorization: `Bearer ${outsiderAuth.token}` },
      data: { status: "ACTIVE" },
    },
  );
  expect([403, 404]).toContain(forbidden.status());

  await page.goto(`/groups/${publicGroup.group_id}`);
  await expect(page.getByRole("link", { name: "Open chat" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Create recurring event" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Remove" })).toHaveCount(0);
});
