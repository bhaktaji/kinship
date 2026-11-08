import { expect, test } from "@playwright/test";
import { createGroup, credentials, registerViaUi } from "./support/api";

test("E3: owner creates a weekly series and RSVPs going", async ({ page, request }) => {
  const creds = credentials("e3");
  const token = await registerViaUi(page, creds);
  const group = await createGroup(request, token, { name: `E2E weekly ${Date.now().toString(36)}` });

  await page.goto(`/groups/${group.group_id}`);
  await expect(page.getByRole("button", { name: "Create recurring event" })).toBeVisible();
  await page.getByRole("button", { name: "Create recurring event" }).click();

  const form = page.locator("form").filter({ hasText: "New recurring event" });
  await form.locator("input").nth(0).fill("E2E weekly meetup");
  await form.locator("input").nth(3).fill("6");
  await form.getByRole("button", { name: "Create event" }).click();
  await expect(page.getByRole("status").first()).toContainText(/Recurring event created with \d+ occurrences/);

  const firstOccurrence = page.locator('a[href^="/events/"]').first();
  await expect(firstOccurrence).toBeVisible();
  await firstOccurrence.click();

  const rsvpButton = page.getByRole("button", { name: "RSVP going" });
  await expect(rsvpButton).toBeVisible();
  await rsvpButton.click();

  await expect(page.getByRole("status").first()).toContainText("You are going");
  await expect(page.getByText("1/6")).toBeVisible();
  await expect(page.getByText("Going", { exact: true }).first()).toBeVisible();
});
