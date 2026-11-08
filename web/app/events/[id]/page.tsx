"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert } from "@/components/Alert";
import { CapacityMeter } from "@/components/CapacityMeter";
import { ErrorState } from "@/components/ErrorState";
import { Icon } from "@/components/Icon";
import { BackLink } from "@/components/PageHeader";
import { PageSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { apiFetch, ApiError } from "@/lib/api";
import {
  formatDateTime,
  formatDayNumber,
  formatMonthShort,
  formatTimeRange,
  formatWeekday,
  statusLabel,
} from "@/lib/format";
import { queueAction } from "@/lib/outbox";
import { useOnlineStatus } from "@/lib/use-online";
import type { EventDetail, RsvpResponse } from "@/lib/types";

type RsvpStatus = "GOING" | "NOT_GOING";

export default function EventDetailPage() {
  const params = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const { token } = useAuth();
  const online = useOnlineStatus();
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [queued, setQueued] = useState<RsvpStatus | null>(null);

  const query = useQuery({
    queryKey: ["event", params.id, token],
    queryFn: () => apiFetch<EventDetail>("/v1/events/" + params.id, {}, token),
    // Offline the service worker serves the cached, read-only copy of the event.
    enabled: Boolean(token) || !online,
  });

  const rsvp = useMutation({
    mutationFn: (status: RsvpStatus) =>
      apiFetch<RsvpResponse>(
        "/v1/events/" + params.id + "/rsvp",
        {
          method: "POST",
          body: JSON.stringify({ status }),
        },
        token,
      ),
    onSuccess: (result) => {
      setError(null);
      setQueued(null);
      setNotice(
        result.status === "GOING"
          ? "You are going."
          : "You are not going. Your seat was released.",
      );
      void queryClient.invalidateQueries({ queryKey: ["event", params.id] });
      void queryClient.invalidateQueries({ queryKey: ["group"] });
      void queryClient.invalidateQueries({ queryKey: ["my-groups"] });
    },
    onError: (caught) => {
      setNotice(null);
      if (caught instanceof ApiError && caught.code === "EVENT_FULL") {
        setError("This occurrence just filled up. Try another upcoming date.");
        void queryClient.invalidateQueries({ queryKey: ["event", params.id] });
        return;
      }
      if (caught instanceof ApiError && caught.code === "RSVP_CLOSED") {
        setError("RSVPs closed because this occurrence has already started.");
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "Could not update your RSVP.");
    },
  });

  /**
   * Offline RSVPs go to the IndexedDB outbox and are replayed once on reconnect
   * (docs/mobile_optimization.md §4.3). Online RSVPs go straight to the API.
   */
  async function updateRsvp(status: RsvpStatus) {
    setError(null);
    if (!online) {
      const queuedOk = await queueAction(
        "/v1/events/" + params.id + "/rsvp",
        "POST",
        { status },
      );
      if (queuedOk) {
        setQueued(status);
        setNotice("Saved offline. Your RSVP is sent as soon as you reconnect.");
      } else {
        setError("You are offline and this action could not be queued. Reconnect and try again.");
      }
      return;
    }
    rsvp.mutate(status);
  }

  if (!token && online) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
          <Icon name="lock" size={20} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Sign in to view this event</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          Meetup details and RSVPs are visible to members of the group.
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
    return (
      <ErrorState
        message="Event not found, or you do not have access to its group."
        title="Event unavailable"
        onRetry={() => void query.refetch()}
      />
    );
  }

  const event = query.data;
  const started = new Date(event.start_time).getTime() <= Date.now();
  const cancelled = event.status === "CANCELLED";
  const full = event.confirmed_count >= event.capacity;
  const effectiveStatus = queued ?? event.my_status;
  const isGoing = effectiveStatus === "GOING";

  return (
    <div className="space-y-6">
      <BackLink href={"/groups/" + event.group.group_id}>Back to {event.group.name}</BackLink>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <section className="card overflow-hidden">
          <div className="flex items-center gap-4 border-b border-line bg-canvas/70 px-5 py-4 sm:px-6">
            <div className="flex h-16 w-16 shrink-0 flex-col items-center justify-center rounded-md border border-line bg-surface">
              <span className="text-micro font-semibold uppercase tracking-wider text-brand">
                {formatWeekday(event.start_time)}
              </span>
              <span className="font-display text-2xl leading-none text-ink">
                {formatDayNumber(event.start_time)}
              </span>
              <span className="text-[0.625rem] font-medium uppercase tracking-wide text-muted">
                {formatMonthShort(event.start_time)}
              </span>
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                {cancelled ? (
                  <StatusPill label="Cancelled" tone="danger" />
                ) : (
                  <StatusPill
                    label={event.group.visibility === "private" ? "Private group" : "Public group"}
                    tone="neutral"
                    dot={false}
                  />
                )}
                {queued ? <StatusPill label="RSVP queued" tone="warning" /> : null}
                {!queued && isGoing ? <StatusPill label="Going" tone="positive" /> : null}
              </div>
              <p className="mt-1.5 text-small font-medium text-ink">
                {formatTimeRange(event.start_time, event.end_time)}
              </p>
            </div>
          </div>

          <div className="p-5 sm:p-6">
            <h1 className="font-display text-h1 text-ink">{event.title}</h1>
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-small text-muted">
              <Link
                href={"/groups/" + event.group.group_id}
                className="inline-flex items-center gap-1.5 font-medium text-ink hover:text-brand"
              >
                <Icon name="users" size={14} />
                {event.group.name}
              </Link>
              <span className="inline-flex items-center gap-1.5">
                <Icon name="map-pin" size={14} />
                {event.location.address ?? "Location shared with the group"}
              </span>
            </div>
            <p className="mt-3 text-small text-muted">
              {formatDateTime(event.start_time)} &ndash; {formatDateTime(event.end_time)}
            </p>

            <div className="mt-6 grid gap-3 sm:grid-cols-3">
              <div className="stat">
                <p className="text-micro font-semibold uppercase tracking-wider text-faint">Going</p>
                <p className="mt-1 font-display text-2xl leading-none text-ink">
                  {event.confirmed_count}/{event.capacity}
                </p>
              </div>
              <div className="stat">
                <p className="text-micro font-semibold uppercase tracking-wider text-faint">Maybe</p>
                <p className="mt-1 font-display text-2xl leading-none text-ink">
                  {event.rsvp_counts.maybe}
                </p>
              </div>
              <div className="stat">
                <p className="text-micro font-semibold uppercase tracking-wider text-faint">
                  Your status
                </p>
                <p className="mt-1.5 text-small font-semibold text-ink">
                  {statusLabel(effectiveStatus)}
                </p>
              </div>
            </div>

            <div className="mt-4">
              <CapacityMeter
                current={event.confirmed_count}
                max={event.capacity}
                label="seats"
                format="words"
              />
            </div>

            <div className="mt-5 space-y-2">
              {notice ? <Alert tone="success">{notice}</Alert> : null}
              {error ? <Alert tone="danger">{error}</Alert> : null}
            </div>

            <div className="mt-5 flex flex-wrap gap-2">
              <button
                type="button"
                className="btn-primary"
                onClick={() => void updateRsvp("GOING")}
                disabled={rsvp.isPending || cancelled || started || (full && event.my_status !== "GOING")}
              >
                {rsvp.isPending ? (
                  <>
                    <Icon name="loader" size={15} className="animate-spin" />
                    Saving…
                  </>
                ) : effectiveStatus === "GOING" ? (
                  <>
                    <Icon name="check" size={15} />
                    Going
                  </>
                ) : full && effectiveStatus !== "GOING" ? (
                  "Event full"
                ) : (
                  "RSVP going"
                )}
              </button>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => void updateRsvp("NOT_GOING")}
                disabled={rsvp.isPending || cancelled || started}
              >
                Not going
              </button>
            </div>

            <div className="mt-4 space-y-1.5">
              {started ? (
                <p className="text-small text-muted">This event has started; RSVPs are closed.</p>
              ) : null}
              {cancelled ? (
                <p className="text-small text-danger">This occurrence was cancelled.</p>
              ) : null}
              {!cancelled && !started && full && event.my_status !== "GOING" ? (
                <p className="text-small text-brand">
                  All {event.capacity} seats are taken. Choose another upcoming occurrence from the group.
                </p>
              ) : null}
              {!online ? (
                <p className="text-small text-brand">
                  You are offline. RSVPs are saved on this device and sent once you reconnect.
                </p>
              ) : null}
            </div>
          </div>
        </section>

        <aside className="space-y-4">
          <section className="card p-4 sm:p-5">
            <h2 className="text-small font-semibold text-ink">Meetup details</h2>
            <dl className="mt-3 space-y-3 text-small">
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">When</dt>
                <dd className="text-right font-medium text-ink">{formatDateTime(event.start_time)}</dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Ends</dt>
                <dd className="text-right font-medium text-ink">{formatDateTime(event.end_time)}</dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Group</dt>
                <dd className="text-right font-medium text-ink">{event.group.name}</dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Seats</dt>
                <dd className="text-right font-medium text-ink">
                  {event.confirmed_count} of {event.capacity}
                </dd>
              </div>
            </dl>
            <Link
              href={"/groups/" + event.group.group_id}
              className="btn-secondary btn-sm mt-4 w-full"
            >
              View group
              <Icon name="arrow-right" size={14} />
            </Link>
          </section>

          <section className="rounded-lg border border-line bg-brand-soft p-4">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="heart" size={16} />
              <h2 className="text-small font-semibold">Show up for the circle</h2>
            </div>
            <p className="mt-1.5 text-[0.75rem] leading-relaxed text-brand/80">
              Small groups depend on everyone showing up. If plans change, update your RSVP so
              someone else can take the seat.
            </p>
          </section>
        </aside>
      </div>
    </div>
  );
}
