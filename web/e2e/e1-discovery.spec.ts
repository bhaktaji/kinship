import { expect, test } from "@playwright/test";
import { credentials, registerViaUi } from "./support/api";

test("E1: register, complete profile, discover nearby activities", async ({ page }) => {
  const creds = credentials("e1");
  await registerViaUi(page, creds);

  await expect(page.getByRole("heading", { name: /Find a small group/ })).toBeVisible();
  await expect(page.locator('a[href^="/activities/"]').first()).toBeVisible();

  await page.goto("/profile");
  await page.locator('input[type="number"]').nth(0).fill("52.52");
  await page.locator('input[type="number"]').nth(1).fill("13.405");
  await page.getByText("Bouldering", { exact: true }).click();
  await page.getByText("Live Music", { exact: true }).click();
  await page.getByText("Hiking", { exact: true }).click();
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByRole("status").first()).toContainText("Profile saved");

  // The saved location now centres discovery.
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Activities near you" })).toBeVisible();
  await expect(page.getByText(/Centred on your saved location/)).toBeVisible();
  await expect(page.locator('a[href^="/activities/"]').first()).toBeVisible();

  // Pagination loads the next cursor page when the seed has another page.
  const cards = page.locator('a[href^="/activities/"]');
  const before = await cards.count();
  const loadMore = page.getByRole("button", { name: "Load more" });
  if (await loadMore.isVisible()) {
    await loadMore.click();
    await expect.poll(async () => cards.count()).toBeGreaterThan(before);
  }

  // Category filter narrows the list; every remaining card is in that category.
  await page.locator("select").first().selectOption("music");
  await expect(cards.first()).toContainText("music");
  await expect.poll(async () => cards.count()).toBeGreaterThan(0);
  for (const text of await cards.allInnerTexts()) {
    expect(text.toLowerCase()).toContain("music");
  }
});
