import { expect, test } from "@playwright/test";
import {
  cachedPrivateUrls,
  createGroup,
  credentials,
  joinGroup,
  registerViaApi,
  registerViaUi,
} from "./support/api";

test("E10: switching accounts on one device leaks no cached group data", async ({ page, request }) => {
  const owner = credentials("e10o");
  const ownerAuth = await registerViaApi(request, owner);
  const privateGroup = await createGroup(request, ownerAuth.token, {
    name: `E2E hidden ${Date.now().toString(36)}`,
    visibility: "private",
  });
  const publicGroup = await createGroup(request, ownerAuth.token, {
    name: `E2E visible ${Date.now().toString(36)}`,
  });

  const firstCreds = credentials("e10a");
  const firstToken = await registerViaUi(page, firstCreds);
  expect(await joinGroup(request, firstToken, publicGroup.group_id)).toBe(200);
  await joinGroup(request, firstToken, privateGroup.group_id);

  await page.goto("/groups");
  await expect(page.getByText(publicGroup.name)).toBeVisible();
  await expect(page.getByText(privateGroup.name)).toBeVisible();

  // Warm the service worker cache for the signed-in account.
  await page.goto("/");
  await expect.poll(() => cachedPrivateUrls(page)).toEqual([]);

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toBeVisible();

  // A second account on the same browser must not inherit the first one's data.
  const secondCreds = credentials("e10b");
  await registerViaUi(page, secondCreds);
  await page.goto("/groups");
  await expect(page.getByText("You have not joined a group yet")).toBeVisible();
  await expect(page.getByText(publicGroup.name)).toHaveCount(0);
  await expect(page.getByText(privateGroup.name)).toHaveCount(0);

  // No token or profile material is kept in browser storage, and no private API
  // response is in any cache.
  expect(
    await page.evaluate(() => [...Object.keys(localStorage), ...Object.keys(sessionStorage)]),
  ).toEqual([]);
  expect(await cachedPrivateUrls(page)).toEqual([]);
});
