import { expect, test } from "@playwright/test";
import {
  credentials,
  createGroup,
  joinGroup,
  loginViaUi,
  messageHistory,
  registerViaApi,
  registerViaUi,
} from "./support/api";

test("E4: realtime delivery, reconnect and history backfill", async ({ browser, page, request }) => {
  const owner = credentials("e4o");
  const member = credentials("e4m");

  const ownerToken = await registerViaUi(page, owner);
  const memberAuth = await registerViaApi(request, member);
  const group = await createGroup(request, ownerToken, {
    name: `E2E chat ${Date.now().toString(36)}`,
  });
  expect(await joinGroup(request, memberAuth.token, group.group_id)).toBe(200);

  const memberContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const memberPage = await memberContext.newPage();
  await loginViaUi(memberPage, member);

  await page.goto(`/groups/${group.group_id}/chat`);
  await memberPage.goto(`/groups/${group.group_id}/chat`);
  await expect(page.getByText("Live")).toBeVisible({ timeout: 20_000 });
  await expect(memberPage.getByText("Live")).toBeVisible({ timeout: 20_000 });

  // Real-time fan-out: the other member receives the message on the socket.
  const first = `hello-${Date.now().toString(36)}`;
  await page.locator("#chat-message").fill(first);
  await page.getByRole("button", { name: "Send" }).click();
  await expect(page.getByText(first)).toBeVisible();
  await expect(memberPage.getByText(first)).toBeVisible({ timeout: 15_000 });

  // The member drops offline; a message sent meanwhile must not be lost.
  await memberContext.setOffline(true);
  const second = `backfill-${Date.now().toString(36)}`;
  await page.locator("#chat-message").fill(second);
  await page.getByRole("button", { name: "Send" }).click();
  await expect(page.getByText(second)).toBeVisible();

  await memberContext.setOffline(false);
  await memberPage.reload();
  await expect(memberPage.getByText(first)).toBeVisible({ timeout: 20_000 });
  await expect(memberPage.getByText(second)).toBeVisible({ timeout: 20_000 });
  await expect(memberPage.getByText("Live")).toBeVisible({ timeout: 20_000 });

  // Sender names: one header per run of consecutive messages from the same sender.
  const runA = `run-a-${Date.now().toString(36)}`;
  const runB = `run-b-${Date.now().toString(36)}`;
  for (const text of [runA, runB]) {
    await memberPage.locator("#chat-message").fill(text);
    await memberPage.getByRole("button", { name: "Send" }).click();
    await expect(memberPage.getByText(text)).toBeVisible();
  }
  await expect(page.getByText(runA)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(runB)).toBeVisible({ timeout: 15_000 });
  // Scope to <main>: the header also renders the signed-in display name.
  await expect(page.locator("main").getByText(member.displayName, { exact: true })).toHaveCount(1);
  // Own bubbles never repeat your own name.
  await expect(page.locator("main").getByText(owner.displayName, { exact: true })).toHaveCount(0);

  // A message from the other side starts a new run, so the name appears again.
  const ownerBack = `owner-back-${Date.now().toString(36)}`;
  await page.locator("#chat-message").fill(ownerBack);
  await page.getByRole("button", { name: "Send" }).click();
  await expect(memberPage.getByText(ownerBack)).toBeVisible({ timeout: 15_000 });
  const runC = `run-c-${Date.now().toString(36)}`;
  await memberPage.locator("#chat-message").fill(runC);
  await memberPage.getByRole("button", { name: "Send" }).click();
  await expect(page.getByText(runC)).toBeVisible({ timeout: 15_000 });
  await expect(page.locator("main").getByText(member.displayName, { exact: true })).toHaveCount(2);

  const history = await messageHistory(request, ownerToken, group.group_id);
  expect(history.map((item) => item.body)).toEqual(expect.arrayContaining([first, second]));
  expect(new Set(history.map((item) => item.sequence_id)).size).toBe(history.length);

  await memberContext.close();
});
