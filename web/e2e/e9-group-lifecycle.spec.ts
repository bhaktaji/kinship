import { expect, test } from "@playwright/test";
import {
  API_BASE,
  createGroup,
  createSeries,
  credentials,
  joinGroup,
  loginViaUi,
  registerViaApi,
  type RegisteredUser,
} from "./support/api";

function auth(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

async function addMember(
  request: Parameters<typeof registerViaApi>[0],
  token: string,
  displayName: string,
): Promise<RegisteredUser> {
  return registerViaApi(request, {
    email: `${displayName}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}@e2e.kinship.local`,
    password: "Kinship12345",
    displayName: `E2E ${displayName}`,
  });
}

test("E9a: private join request, owner approval, membership revocation", async ({ page, request }) => {
  const owner = credentials("e9o");
  const member = credentials("e9m");
  const ownerAuth = await registerViaApi(request, owner);
  const memberAuth = await registerViaApi(request, member);

  const priv = await createGroup(request, ownerAuth.token, {
    name: `E2E private ${Date.now().toString(36)}`,
    visibility: "private",
  });

  const join = await request.post(`${API_BASE}/v1/groups/${priv.group_id}/join`, {
    headers: auth(memberAuth.token),
  });
  expect(join.status()).toBe(200);
  expect((await join.json()).status).toBe("PENDING");

  // Pending is not active: no history, no messaging, no RSVP.
  expect(
    (
      await request.get(`${API_BASE}/v1/groups/${priv.group_id}/messages`, {
        headers: auth(memberAuth.token),
      })
    ).status(),
  ).toBe(403);

  // The owner approves from the group page.
  await loginViaUi(page, owner);
  await page.goto(`/groups/${priv.group_id}`);
  // A cold context installs the service worker on first navigation, which can
  // delay the first authenticated fetch; wait for the group itself first.
  await expect(page.getByRole("heading", { name: priv.name })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("Join requests")).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByRole("status").first()).toContainText("Membership updated.");
  await expect(page.getByText("Join requests")).toHaveCount(0);

  // The approved member can now read and post.
  expect(
    (
      await request.get(`${API_BASE}/v1/groups/${priv.group_id}/messages`, {
        headers: auth(memberAuth.token),
      })
    ).status(),
  ).toBe(200);
  expect(
    (
      await request.post(`${API_BASE}/v1/groups/${priv.group_id}/messages`, {
        headers: auth(memberAuth.token),
        data: { body: "approved and posting", client_msg_id: crypto.randomUUID() },
      })
    ).status(),
  ).toBe(201);

  // Leaving revokes access again, including private group visibility.
  expect(
    (
      await request.delete(`${API_BASE}/v1/groups/${priv.group_id}/members/me`, {
        headers: auth(memberAuth.token),
      })
    ).status(),
  ).toBe(204);
  expect(
    (
      await request.get(`${API_BASE}/v1/groups/${priv.group_id}/messages`, {
        headers: auth(memberAuth.token),
      })
    ).status(),
  ).toBe(403);
  expect(
    (
      await request.get(`${API_BASE}/v1/groups/${priv.group_id}`, {
        headers: auth(memberAuth.token),
      })
    ).status(),
  ).toBe(404);
});

test("E9b: group and event capacity are enforced atomically", async ({ page, request }) => {
  const owner = credentials("e9cap");
  const ownerAuth = await registerViaApi(request, owner);
  const group = await createGroup(request, ownerAuth.token, {
    name: `E2E capacity ${Date.now().toString(36)}`,
    maxMembers: 4,
  });

  const members: RegisteredUser[] = [];
  for (let index = 0; index < 3; index += 1) {
    const registered = await addMember(request, ownerAuth.token, `e9c${index}`);
    expect(await joinGroup(request, registered.token, group.group_id)).toBe(200);
    members.push(registered);
  }

  const outsiderCreds = credentials("e9x");
  const outsider = await registerViaApi(request, outsiderCreds);
  expect(await joinGroup(request, outsider.token, group.group_id)).toBe(409);

  const series = await createSeries(request, ownerAuth.token, group.group_id, 2);
  const rsvp = (token: string, status: "GOING" | "NOT_GOING") =>
    request.post(`${API_BASE}/v1/events/${series.eventId}/rsvp`, {
      headers: auth(token),
      data: { status },
    });

  expect((await rsvp(members[0].token, "GOING")).status()).toBe(200);
  expect((await rsvp(members[1].token, "GOING")).status()).toBe(200);

  const full = await rsvp(members[2].token, "GOING");
  expect(full.status()).toBe(409);
  expect((await full.json()).error.code).toBe("EVENT_FULL");

  // Repeating the same RSVP is idempotent instead of double counting.
  const repeated = await rsvp(members[0].token, "GOING");
  expect(repeated.status()).toBe(200);
  expect((await repeated.json()).confirmed_count).toBe(2);

  // Releasing a seat frees capacity for the waiting member.
  expect((await rsvp(members[0].token, "NOT_GOING")).status()).toBe(200);
  expect((await rsvp(members[2].token, "GOING")).status()).toBe(200);

  // A non-member sees the full occurrence with the join action disabled.
  await loginViaUi(page, outsiderCreds);
  await page.goto(`/events/${series.eventId}`);
  await expect(page.getByText("2/2")).toBeVisible();
  await expect(page.getByRole("button", { name: "Event full" })).toBeDisabled();
});
