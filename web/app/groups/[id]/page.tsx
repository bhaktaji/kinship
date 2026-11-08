"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert } from "@/components/Alert";
import { Avatar } from "@/components/Avatar";
import { CapacityMeter } from "@/components/CapacityMeter";
import { CategoryArt } from "@/components/CategoryArt";
import { ErrorState } from "@/components/ErrorState";
import { EventRow } from "@/components/EventRow";
import { Icon } from "@/components/Icon";
import { BackLink } from "@/components/PageHeader";
import { PageSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { apiFetch, ApiError } from "@/lib/api";
import { formatCoordinates, formatDateTime } from "@/lib/format";
import { useOnlineStatus } from "@/lib/use-online";
import type { EventSummary, GroupDetail, ReviewSummary } from "@/lib/types";

const DAY_CODES = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];

function tomorrow(): string {
  const value = new Date();
  value.setDate(value.getDate() + 1);
  return value.toLocaleDateString("en-CA");
}

function Stars({ value, size = 14 }: { value: number; size?: number }) {
  return (
    <span className="inline-flex items-center gap-0.5 text-gold" aria-label={`${value.toFixed(1)} out of 5`}>
      {[1, 2, 3, 4, 5].map((index) => (
        <Icon
          key={index}
          name="star"
          size={size}
          className={index <= Math.round(value) ? "fill-current" : "text-line"}
        />
      ))}
    </span>
  );
}

export default function GroupDetailPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { token } = useAuth();
  const online = useOnlineStatus();
  const groupId = params.id;

  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showEventForm, setShowEventForm] = useState(false);
  const [joinReason, setJoinReason] = useState("");
  const [shareWorkplace, setShareWorkplace] = useState(false);
  const [showJoinRequest, setShowJoinRequest] = useState(false);
  const [title, setTitle] = useState("Weekly meetup");
  const [startsOn, setStartsOn] = useState(tomorrow());
  const [startTime, setStartTime] = useState("18:30");
  const [freq, setFreq] = useState("WEEKLY");
  const [capacity, setCapacity] = useState<number | "">("");

  const query = useQuery({
    queryKey: ["group", groupId, token],
    queryFn: () => apiFetch<GroupDetail>("/v1/groups/" + groupId, {}, token),
    // Offline the service worker serves the cached, read-only copy of the group.
    enabled: Boolean(token) || !online,
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["group", groupId] });
  const reviews = useQuery({
    queryKey: ["group-reviews", groupId],
    queryFn: () => apiFetch<ReviewSummary>("/v1/groups/" + groupId + "/reviews", {}, token),
    enabled: Boolean(token) || !online,
  });

  const fail = (caught: unknown) => {
    setNotice(null);
    setError(caught instanceof ApiError ? caught.message : "The action could not be completed.");
  };

  const join = useMutation({
    mutationFn: () => apiFetch<{ status: string }>("/v1/groups/" + groupId + "/join", { method: "POST" }, token),
    onSuccess: (result) => {
      setError(null);
      setNotice(result.status === "PENDING" ? "Join request sent. The owner must approve it." : "You joined the group.");
      refresh();
    },
    onError: fail,
  });

  const requestJoin = useMutation({
    mutationFn: () =>
      apiFetch<{ status: string }>("/v1/groups/" + groupId + "/join-requests", {
        method: "POST",
        body: JSON.stringify({ reason: joinReason, share_workplace: shareWorkplace }),
      }, token),
    onSuccess: () => {
      setError(null);
      setNotice("Join request submitted. Pending approval.");
      setShowJoinRequest(false);
      setJoinReason("");
      refresh();
    },
    onError: fail,
  });

  const leave = useMutation({
    mutationFn: () => apiFetch<void>("/v1/groups/" + groupId + "/members/me", { method: "DELETE" }, token),
    onSuccess: () => {
      setError(null);
      setNotice("You left the group.");
      void queryClient.invalidateQueries({ queryKey: ["my-groups"] });
      router.push("/groups");
    },
    onError: fail,
  });

  const memberAction = useMutation({
    mutationFn: ({ userId, status }: { userId: string; status: "ACTIVE" | "REMOVED" }) =>
      apiFetch<void>("/v1/groups/" + groupId + "/members/" + userId, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      }, token),
    onSuccess: () => {
      setError(null);
      setNotice("Membership updated.");
      refresh();
    },
    onError: fail,
  });

  const createSeries = useMutation({
    mutationFn: () => {
      const day = DAY_CODES[new Date(startsOn + "T12:00:00").getDay()];
      const recurrence: Record<string, unknown> = {
        freq,
        interval: 1,
        start_time: startTime,
        starts_on: startsOn,
      };
      if (freq !== "MONTHLY") {
        recurrence.byday = [day];
      }
      return apiFetch<{ event_series_id: string; occurrences: unknown[] }>(
        "/v1/groups/" + groupId + "/events",
        {
          method: "POST",
          body: JSON.stringify({
            title,
            timezone: "Europe/Berlin",
            capacity: capacity === "" ? undefined : Number(capacity),
            recurrence,
          }),
        },
        token,
      );
    },
    onSuccess: (result) => {
      setError(null);
      setNotice("Recurring event created with " + result.occurrences.length + " occurrences.");
      setShowEventForm(false);
      refresh();
    },
    onError: fail,
  });

  if (!token && online) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
          <Icon name="lock" size={20} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Sign in to view this group</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          Group details, members and meetups are visible to signed-in members.
        </p>
        <Link href="/login" className="btn-primary mt-5">
          Sign in
        </Link>
      </div>
    );
  }

  if (query.isPending) {
    return <PageSkeleton />;
  }

  if (query.isError || !query.data) {
    return <ErrorState message="Group not found, or it is private and you are not a member." title="Group unavailable" />;
  }

  const { group, members, next_events } = query.data;
  const isOwner = group.my_role === "OWNER" && group.my_status === "ACTIVE";
  const isMember = group.my_status === "ACTIVE";
  const isPending = group.my_status === "PENDING";
  const pendingMembers = members.filter((member) => member.status === "PENDING");
  const activeMembers = members.filter((member) => member.status === "ACTIVE");
  const summary = reviews.data;
  const place = group.location.address ?? "Berlin";

  return (
    <div className="space-y-6">
      <BackLink href="/groups">Back to my groups</BackLink>

      <section className="card overflow-hidden">
        <div className="relative">
          <CategoryArt category={group.category} className="h-32 sm:h-40" detail />
          <div className="absolute inset-x-0 bottom-0 flex flex-wrap items-center gap-2 p-4">
            <span className="pill bg-surface/90 text-ink backdrop-blur">{group.category}</span>
            <span className="pill bg-surface/90 text-ink backdrop-blur">
              {group.visibility === "private" ? "Private" : "Public"}
            </span>
            {isOwner ? <span className="pill bg-forest text-white">Owner</span> : null}
            {isPending ? <span className="pill bg-warning text-white">Pending approval</span> : null}
            {group.status === "ARCHIVED" ? <span className="pill bg-danger text-white">Archived</span> : null}
          </div>
        </div>

        <div className="p-5 sm:p-6">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <h1 className="font-display text-h1 text-ink">{group.name}</h1>
              <p className="mt-2 max-w-2xl text-body leading-relaxed text-muted">
                {group.description ?? "A small recurring group."}
              </p>
              <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-small text-muted">
                <span className="inline-flex items-center gap-1.5">
                  <Icon name="users" size={14} />
                  <span>
                    {group.active_member_count}/{group.max_members} active members
                  </span>
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <Icon name="map-pin" size={14} />
                  {place}
                  <span className="text-faint">·</span>
                  <span className="text-[0.75rem] text-faint">{formatCoordinates(group.location.lat, group.location.lng)}</span>
                </span>
              </div>
            </div>

            <div className="flex w-full flex-wrap gap-2 sm:w-auto">
              {!isMember && !isPending && group.join_policy !== "APPROVAL" ? (
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => join.mutate()}
                  disabled={join.isPending || !online}
                >
                  {join.isPending ? "Joining…" : online ? "Join group" : "Offline"}
                </button>
              ) : null}
              {!isMember && !isPending && group.join_policy === "APPROVAL" ? (
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => setShowJoinRequest(true)}
                  disabled={!online}
                >
                  Request to join
                </button>
              ) : null}
              {isPending ? (
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => leave.mutate()}
                  disabled={leave.isPending || !online}
                >
                  Cancel request
                </button>
              ) : null}
              {isMember ? (
                <>
                  <Link href={"/groups/" + group.group_id + "/chat"} className="btn-primary">
                    <Icon name="message" size={16} />
                    Open chat
                  </Link>
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={() => leave.mutate()}
                    disabled={leave.isPending || !online}
                  >
                    Leave group
                  </button>
                </>
              ) : null}
            </div>
          </div>

          {showJoinRequest ? (
            <div className="mt-5 space-y-3 rounded-md border border-line bg-canvas p-4">
              <label className="field-group">
                <span className="label">Why would you like to join?</span>
                <textarea
                  className="field min-h-20 resize-y"
                  placeholder="Tell the organiser a little about yourself."
                  value={joinReason}
                  onChange={(event) => setJoinReason(event.target.value)}
                />
              </label>
              <label className="flex items-center gap-2 text-small text-muted">
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-line text-forest focus:ring-forest/20"
                  checked={shareWorkplace}
                  onChange={(event) => setShareWorkplace(event.target.checked)}
                />
                Share workplace with admins
              </label>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  className="btn-primary btn-sm"
                  onClick={() => requestJoin.mutate()}
                  disabled={requestJoin.isPending || !joinReason.trim()}
                >
                  {requestJoin.isPending ? "Submitting…" : "Submit request"}
                </button>
                <button type="button" className="btn-ghost btn-sm" onClick={() => setShowJoinRequest(false)}>
                  Cancel
                </button>
              </div>
            </div>
          ) : null}

          <div className="mt-4 space-y-2">
            {notice ? <Alert tone="success">{notice}</Alert> : null}
            {!online ? (
              <Alert tone="warning">
                You are offline. Saved group details stay readable; joining, leaving and messaging need a connection.
              </Alert>
            ) : null}
            {error ? <Alert tone="danger">{error}</Alert> : null}
          </div>
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="min-w-0 space-y-6">
          <section className="space-y-3" aria-labelledby="occurrences-title">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 id="occurrences-title" className="font-display text-h2 text-ink">
                  Upcoming occurrences
                </h2>
                <p className="mt-1 text-small text-muted">
                  {next_events.length === 0
                    ? "The next meetup will appear here."
                    : `${next_events.length} ${next_events.length === 1 ? "date" : "dates"} scheduled`}
                </p>
              </div>
              {isOwner ? (
                <button
                  type="button"
                  className="btn-secondary btn-sm"
                  onClick={() => setShowEventForm((value) => !value)}
                >
                  <Icon name="calendar-plus" size={15} />
                  {showEventForm ? "Close form" : "Create recurring event"}
                </button>
              ) : null}
            </div>

            {showEventForm && isOwner ? (
              <form
                className="card space-y-4 p-4 sm:p-5"
                onSubmit={(event) => {
                  event.preventDefault();
                  createSeries.mutate();
                }}
              >
                <div>
                  <h3 className="text-h3 font-semibold text-ink">New recurring event</h3>
                  <p className="mt-0.5 text-small text-muted">
                    Kinship creates the next occurrences automatically.
                  </p>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="field-group sm:col-span-2">
                    <span className="label">Title</span>
                    <input
                      className="field"
                      required
                      maxLength={120}
                      value={title}
                      onChange={(event) => setTitle(event.target.value)}
                    />
                  </label>
                  <label className="field-group">
                    <span className="label">First date</span>
                    <input
                      className="field"
                      type="date"
                      required
                      value={startsOn}
                      min={tomorrow()}
                      onChange={(event) => setStartsOn(event.target.value)}
                    />
                  </label>
                  <label className="field-group">
                    <span className="label">Start time (Europe/Berlin)</span>
                    <input
                      className="field"
                      type="time"
                      required
                      value={startTime}
                      onChange={(event) => setStartTime(event.target.value)}
                    />
                  </label>
                  <label className="field-group">
                    <span className="label">Repeat</span>
                    <select className="field" value={freq} onChange={(event) => setFreq(event.target.value)}>
                      <option value="WEEKLY">Weekly</option>
                      <option value="BIWEEKLY">Every two weeks</option>
                      <option value="MONTHLY">Monthly</option>
                    </select>
                  </label>
                  <label className="field-group">
                    <span className="label">Capacity (optional)</span>
                    <input
                      className="field"
                      type="number"
                      min={1}
                      max={100}
                      value={capacity}
                      onChange={(event) => setCapacity(event.target.value === "" ? "" : Number(event.target.value))}
                    />
                  </label>
                </div>
                <div className="flex flex-wrap gap-2 border-t border-line pt-4">
                  <button type="submit" className="btn-primary btn-sm" disabled={createSeries.isPending}>
                    {createSeries.isPending ? "Creating…" : "Create event"}
                  </button>
                  <button type="button" className="btn-ghost btn-sm" onClick={() => setShowEventForm(false)}>
                    Cancel
                  </button>
                </div>
              </form>
            ) : null}

            {next_events.length === 0 ? (
              <div className="card px-5 py-8 text-center">
                <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-full bg-canvas text-muted">
                  <Icon name="calendar" size={20} />
                </span>
                <p className="mt-3 text-small font-medium text-ink">No upcoming occurrences yet.</p>
                <p className="mt-1 text-small text-muted">
                  {isOwner ? "Create a recurring event to get the group on the calendar." : "Check back soon."}
                </p>
              </div>
            ) : (
              <div className="space-y-2">
                {next_events.map((event: EventSummary) => (
                  <EventRow key={event.event_id} event={event} />
                ))}
              </div>
            )}
          </section>

          <section className="space-y-3" aria-labelledby="members-title">
            <div className="flex flex-wrap items-end justify-between gap-2">
              <h2 id="members-title" className="font-display text-h2 text-ink">
                Members
              </h2>
              <span className="text-small text-muted">
                {activeMembers.length} active{pendingMembers.length > 0 ? ` · ${pendingMembers.length} pending` : ""}
              </span>
            </div>

            <div className="grid gap-2 sm:grid-cols-2">
              {activeMembers.map((member) => (
                <div key={member.user_id} className="flex items-center justify-between gap-3 rounded-md border border-line bg-surface p-3.5">
                  <div className="flex min-w-0 items-center gap-3">
                    <Avatar name={member.display_name} size="md" />
                    <div className="min-w-0">
                      <p className="truncate text-small font-semibold text-ink">{member.display_name}</p>
                      <p className="text-[0.75rem] text-muted">
                        {member.role === "OWNER" ? "Organiser" : "Member"}
                      </p>
                    </div>
                  </div>
                  {isOwner && member.role !== "OWNER" ? (
                    <button
                      type="button"
                      className="text-[0.75rem] font-semibold text-danger hover:underline"
                      onClick={() => memberAction.mutate({ userId: member.user_id, status: "REMOVED" })}
                      disabled={memberAction.isPending}
                    >
                      Remove
                    </button>
                  ) : null}
                </div>
              ))}
            </div>

            {isOwner && pendingMembers.length > 0 ? (
              <div className="space-y-2 rounded-md border border-warning/25 bg-warning/[0.05] p-3">
                <h3 className="px-1 text-small font-semibold text-warning">Join requests</h3>
                {pendingMembers.map((member) => (
                  <div key={member.user_id} className="flex flex-wrap items-center justify-between gap-3 rounded-sm bg-surface p-3">
                    <div className="flex items-center gap-2.5">
                      <Avatar name={member.display_name} size="sm" />
                      <span className="text-small font-medium text-ink">{member.display_name}</span>
                    </div>
                    <div className="flex gap-2">
                      <button
                        type="button"
                        className="btn-primary btn-sm"
                        onClick={() => memberAction.mutate({ userId: member.user_id, status: "ACTIVE" })}
                        disabled={memberAction.isPending}
                      >
                        Approve
                      </button>
                      <button
                        type="button"
                        className="btn-secondary btn-sm"
                        onClick={() => memberAction.mutate({ userId: member.user_id, status: "REMOVED" })}
                        disabled={memberAction.isPending}
                      >
                        Decline
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : null}
          </section>

          <section className="space-y-3" aria-labelledby="reviews-title">
            <h2 id="reviews-title" className="font-display text-h2 text-ink">
              Reviews
            </h2>
            {reviews.isPending ? (
              <div className="skeleton h-24 rounded-lg" />
            ) : summary ? (
              <div className="card p-4 sm:p-5">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div>
                      <p className="font-display text-3xl leading-none text-ink">
                        {summary.count === 0 ? "—" : (summary.avg_overall ?? 0).toFixed(1)}
                      </p>
                      <p className="mt-1 text-[0.75rem] text-muted">
                        {summary.count === 0 ? "No reviews yet" : `${summary.count} review${summary.count === 1 ? "" : "s"}`}
                      </p>
                    </div>
                    {summary.count > 0 ? <Stars value={summary.avg_overall ?? 0} size={16} /> : null}
                  </div>
                  {summary.count > 0 ? (
                    <div className="flex flex-wrap gap-3 text-[0.75rem] text-muted">
                      <span>Atmosphere {(summary.avg_atmosphere ?? 0).toFixed(1)}</span>
                      <span>Organisation {(summary.avg_organization ?? 0).toFixed(1)}</span>
                    </div>
                  ) : null}
                </div>
                {summary.recent.length > 0 ? (
                  <ul className="mt-4 space-y-2 border-t border-line pt-4">
                    {summary.recent.map((review) => (
                      <li key={review.id} className="rounded-sm bg-canvas/70 p-3">
                        <div className="flex items-center gap-2">
                          <Stars value={review.overall_rating} size={12} />
                          <span className="text-[0.75rem] font-medium text-ink">{review.overall_rating}/5</span>
                        </div>
                        {review.feedback ? (
                          <p className="mt-1 text-small text-muted">{review.feedback}</p>
                        ) : null}
                        {review.vibe_tags?.length ? (
                          <div className="mt-1.5 flex flex-wrap gap-1">
                            {review.vibe_tags.map((tag) => (
                              <span key={tag} className="pill bg-brand/10 text-brand">
                                {tag}
                              </span>
                            ))}
                          </div>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            ) : null}
          </section>
        </div>

        <aside className="space-y-4">
          <section className="card p-4">
            <h2 className="text-small font-semibold text-ink">Group details</h2>
            <dl className="mt-3 space-y-3 text-small">
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Visibility</dt>
                <dd className="font-medium capitalize text-ink">{group.visibility}</dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Join policy</dt>
                <dd className="font-medium text-ink">
                  {group.join_policy === "APPROVAL" ? "Approval" : "Open"}
                </dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Capacity</dt>
                <dd className="font-medium text-ink">
                  {group.active_member_count}/{group.max_members}
                </dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Next meetup</dt>
                <dd className="text-right font-medium text-ink">
                  {next_events[0] ? formatDateTime(next_events[0].start_time) : "Not scheduled"}
                </dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Location</dt>
                <dd className="text-right font-medium text-ink">{place}</dd>
              </div>
            </dl>
            <div className="mt-4">
              <CapacityMeter current={group.active_member_count} max={group.max_members} />
            </div>
          </section>

          <section className="rounded-lg border border-line bg-brand-soft p-4">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="heart" size={16} />
              <h2 className="text-small font-semibold">Keep it small</h2>
            </div>
            <p className="mt-1.5 text-[0.75rem] leading-relaxed text-brand/80">
              Kinship groups work best at four to six people. Small circles make it easier for everyone
              to show up and be heard.
            </p>
          </section>
        </aside>
      </div>
    </div>
  );
}
