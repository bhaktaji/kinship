"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Alert } from "@/components/Alert";
import { CapacityMeter } from "@/components/CapacityMeter";
import { CategoryArt } from "@/components/CategoryArt";
import { ErrorState } from "@/components/ErrorState";
import { Icon } from "@/components/Icon";
import { BackLink } from "@/components/PageHeader";
import { PageSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { apiFetch, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { ActivityDetail } from "@/lib/types";

interface JoinResult {
  group_id: string;
  status: "ACTIVE" | "PENDING";
}

export default function ActivityDetailPage() {
  const params = useParams<{ id: string }>();
  const { token } = useAuth();
  const [joined, setJoined] = useState<JoinResult | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ["activity", params.id, token],
    queryFn: () => apiFetch<ActivityDetail>("/v1/activities/" + params.id, {}, token),
    enabled: Boolean(token),
  });

  const join = useMutation({
    mutationFn: (groupId: string) =>
      apiFetch<JoinResult>("/v1/groups/" + groupId + "/join", { method: "POST" }, token),
    onSuccess: (result) => {
      setError(null);
      setJoined(result);
      setNotice(
        result.status === "PENDING"
          ? "Request sent — the owner must approve you."
          : "You joined the group.",
      );
    },
    onError: (caught) => {
      setNotice(null);
      setError(caught instanceof ApiError ? caught.message : "Could not join the group.");
    },
  });

  if (!token) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
          <Icon name="lock" size={20} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Sign in to view this activity</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          Activity details and the groups that meet around them are visible to signed-in members.
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
        message="Activity not found or unavailable."
        title="Activity unavailable"
        onRetry={() => void query.refetch()}
      />
    );
  }

  const activity = query.data;

  return (
    <div className="space-y-6">
      <BackLink href="/">Back to discovery</BackLink>

      <section className="card overflow-hidden">
        <div className="relative">
          <CategoryArt category={activity.category} className="h-36 sm:h-48" detail />
          <div className="absolute inset-x-0 bottom-0 flex flex-wrap items-center gap-2 p-4">
            <span className="pill bg-surface/90 capitalize text-ink backdrop-blur">{activity.category}</span>
            {activity.organization ? (
              <span className="pill bg-surface/90 text-ink backdrop-blur">
                Hosted by {activity.organization.name}
              </span>
            ) : null}
          </div>
        </div>

        <div className="p-5 sm:p-6">
          <h1 className="font-display text-h1 text-ink">{activity.title}</h1>
          <p className="mt-2 max-w-2xl text-body leading-relaxed text-muted">
            {activity.description ?? "A recurring activity group in Berlin."}
          </p>
          <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-small text-muted">
            <span className="inline-flex items-center gap-1.5">
              <Icon name="map-pin" size={14} />
              {activity.location.address ?? "Location shared with the group"}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <Icon name="users" size={14} />
              {activity.groups.length} {activity.groups.length === 1 ? "group" : "groups"} meeting
            </span>
          </div>
        </div>
      </section>

      {notice ? <Alert tone="success">{notice}</Alert> : null}
      {error ? <Alert tone="danger">{error}</Alert> : null}

      <section className="space-y-3" aria-labelledby="groups-title">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 id="groups-title" className="font-display text-h2 text-ink">
              Groups for this activity
            </h2>
            <p className="mt-1 text-small text-muted">
              Small circles that meet regularly around {activity.title.toLowerCase()}.
            </p>
          </div>
        </div>

        {activity.groups.length === 0 ? (
          <div className="card px-5 py-10 text-center">
            <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-full bg-canvas text-muted">
              <Icon name="users" size={20} />
            </span>
            <p className="mt-3 text-small font-medium text-ink">No open group right now.</p>
            <p className="mt-1 text-small text-muted">Check back soon, or start a circle of your own.</p>
            <Link href="/groups" className="btn-secondary btn-sm mt-4">
              Browse groups
            </Link>
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {activity.groups.map((group) => {
              const isJoined = joined?.group_id === group.group_id;
              return (
                <article key={group.group_id} className="card card-hover flex flex-col p-5">
                  <div className="flex items-start justify-between gap-2">
                    <h3 className="text-h3 font-semibold text-ink">{group.name}</h3>
                    {group.visibility === "private" ? (
                      <StatusPill label="Private" tone="warning" dot={false} />
                    ) : (
                      <StatusPill label="Open" tone="positive" dot={false} />
                    )}
                  </div>

                  <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-small text-muted">
                    <span className="inline-flex items-center gap-1.5">
                      <Icon name="users" size={13} />
                      {group.member_count}/{group.max_members} members
                    </span>
                    <span className="inline-flex items-center gap-1.5">
                      <Icon name="clock" size={13} />
                      {group.next_event
                        ? formatDateTime(group.next_event.start_time)
                        : "No upcoming occurrence"}
                    </span>
                  </div>

                  <div className="mt-3">
                    <CapacityMeter current={group.member_count} max={group.max_members} />
                  </div>

                  <div className="mt-auto flex flex-wrap items-center gap-2 pt-4">
                    <Link href={"/groups/" + group.group_id} className="btn-secondary btn-sm">
                      View group
                    </Link>
                    {isJoined ? (
                      <StatusPill
                        label={joined.status === "PENDING" ? "Pending approval" : "Joined"}
                        tone={joined.status === "PENDING" ? "warning" : "positive"}
                      />
                    ) : (
                      <button
                        type="button"
                        className="btn-primary btn-sm"
                        onClick={() => join.mutate(group.group_id)}
                        disabled={join.isPending}
                      >
                        {join.isPending && join.variables === group.group_id ? (
                          <>
                            <Icon name="loader" size={14} className="animate-spin" />
                            Joining…
                          </>
                        ) : (
                          "Join group"
                        )}
                      </button>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
