import { expect, test } from "@playwright/test";
import {
  acceptInvitation,
  completeProfile,
  credentials,
  interestIds,
  invitations,
  registerViaApi,
  registerViaUi,
} from "./support/api";

/**
 * Four accounts with an identical profile form one proposal. The interest set and
 * availability window are randomised per run so users created by earlier runs
 * cannot outrank this run's candidates.
 */
test("E5: request a match, accept the invitation, group appears", async ({ page, request }) => {
  // /v1/interests is authenticated, so the subject registers first to read it.
  const subject = credentials("e5");
  const subjectAuth = await registerViaApi(request, subject);
  const ids = await interestIds(request, subjectAuth.token);
  const allInterests = Object.values(ids);
  const shuffled = [...allInterests].sort(() => Math.random() - 0.5).slice(0, 3);
  const dayOfWeek = 1 + Math.floor(Math.random() * 7);
  const startHour = 8 + Math.floor(Math.random() * 10);
  const availability = {
    dayOfWeek,
    start: `${String(startHour).padStart(2, "0")}:00`,
    end: `${String(startHour + 2).padStart(2, "0")}:00`,
  };

  const candidates = [];
  for (let index = 0; index < 3; index += 1) {
    const creds = credentials(`e5c${index}`);
    const registered = await registerViaApi(request, creds);
    await completeProfile(request, registered.token, shuffled, availability);
    candidates.push(registered);
  }

  // subjectAuth is only used to read the authenticated interest catalogue; it
  // deliberately keeps an empty profile so it is not an eligible candidate.
  const subjectToken = await registerViaUi(page, credentials("e5ui"));
  await completeProfile(request, subjectToken, shuffled, availability);

  await page.goto("/invitations");
  await page.getByRole("button", { name: "Find a match" }).click();
  await expect(page.getByText(/Matching job/)).toBeVisible();

  // The worker polls every 10 seconds.
  await expect
    .poll(async () => (await invitations(request, subjectToken)).length, { timeout: 90_000 })
    .toBeGreaterThan(0);

  const subjectInvitations = await invitations(request, subjectToken);
  const matchGroupId = subjectInvitations[0].match_group_id;

  await page.reload();
  const card = page.locator("article").filter({ hasText: "Proposed group" }).first();
  await expect(card).toBeVisible();
  await expect(card).toContainText("Expires");
  await card.getByRole("button", { name: "Accept" }).click();
  await expect(page.getByRole("status").first()).toContainText(/Invitation accepted|Response saved/);

  // The remaining members accept the same proposal; the fourth acceptance forms
  // the group. This is fixture concurrency, not a UI shortcut.
  for (const candidate of candidates) {
    const list = await invitations(request, candidate.token);
    const invitation = list.find((item) => item.match_group_id === matchGroupId);
    expect(invitation, "candidate should be invited to the same proposal").toBeTruthy();
    await acceptInvitation(request, candidate.token, invitation!.invitation_id);
  }

  await page.goto("/groups");
  await expect(page.getByRole("link", { name: /Kinship/ }).first()).toBeVisible({ timeout: 20_000 });
});
