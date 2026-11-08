import { expect, test } from "@playwright/test";
import { API_BASE, credentials, discovery, registerViaUi } from "./support/api";

test("E2: open an activity, join its group, see the next occurrence", async ({ page, request }) => {
  const creds = credentials("e2");
  const token = await registerViaUi(page, creds);

  const activities = await discovery(request, token);
  const activity = activities.find((item) => item.next_event) ?? activities[0];
  expect(activity).toBeTruthy();

  await page.goto(`/activities/${activity.activity_id}`);
  await expect(page.getByRole("heading", { name: activity.title, exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Join group" }).first().click();
  await expect(page.getByRole("status").first()).toContainText(/joined|must approve/i);

  await page.goto(`/groups/${activity.group_id}`);
  await expect(page.getByRole("heading", { name: "Upcoming occurrences" })).toBeVisible();
  await expect(page.locator('a[href^="/events/"]').first()).toBeVisible();
  await expect(page.getByText(/active member/)).toBeVisible();

  // The joined group now appears in "My groups" with its next meetup.
  await page.goto("/groups");
  await expect(page.locator('a[href^="/groups/"]').first()).toBeVisible();

  // Leave the seeded group so repeated suite runs do not slowly fill it to
  // capacity and change which activities discovery can return.
  expect(
    (
      await request.delete(`${API_BASE}/v1/groups/${activity.group_id}/members/me`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).status(),
  ).toBe(204);
});
