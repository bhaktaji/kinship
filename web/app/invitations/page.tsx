"use client";

import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Alert } from "@/components/Alert";
import { Avatar } from "@/components/Avatar";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { RowSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { apiFetch, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { InvitationPage, MatchInvitation, MatchJob, MatchJobCreated } from "@/lib/types";

export default function InvitationsPage() {
  const { token } = useAuth();
  const queryClient = useQueryClient();
  const [jobId, setJobId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const invitations = useQuery({
    queryKey: ["match-invitations", token],
    queryFn: () => apiFetch<InvitationPage>("/v1/match-invitations?status=INVITED", {}, token),
    enabled: Boolean(token),
  });

  const request = useMutation({
    mutationFn: () =>
      apiFetch<MatchJobCreated>("/v1/match-requests", {
        method: "POST",
        body: JSON.stringify({ max_distance_km: 10, preferred_group_size: 4 }),
      }, token),
    onSuccess: (result) => {
      setError(null);
      setJobId(result.job_id);
      setNotice(null);
    },
    onError: (caught) => {
      setError(
        caught instanceof ApiError && caught.code === "PROFILE_INCOMPLETE"
          ? "Add a location, at least two interests and availability to your profile before matching."
          : caught instanceof ApiError ? caught.message : "Could not start matching.",
      );
    },
  });

  const job = useQuery({
    queryKey: ["match-job", jobId, token],
    queryFn: () => apiFetch<MatchJob>("/v1/match-requests/" + jobId, {}, token),
    enabled: Boolean(jobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "DONE" || status === "FAILED" ? false : 2000;
    },
  });

  const respond = useMutation({
    mutationFn: ({ invitationId, action }: { invitationId: string; action: "accept" | "decline" }) =>
      apiFetch<{ status: string; group_id?: string | null }>(
        "/v1/match-invitations/" + invitationId + "/" + action,
        { method: "POST" },
        token,
      ),
    onSuccess: (result) => {
      setError(null);
      setNotice(result.group_id ? "Invitation accepted — your new group is ready." : "Response saved.");
      void queryClient.invalidateQueries({ queryKey: ["match-invitations"] });
      void queryClient.invalidateQueries({ queryKey: ["my-groups"] });
    },
    onError: (caught) => {
      setNotice(null);
      setError(caught instanceof ApiError ? caught.message : "Could not respond to the invitation.");
    },
  });

  const items = invitations.data?.items ?? [];
  const jobStatus = job.data?.status;

  useEffect(() => {
    if (jobStatus !== "DONE") {
      return;
    }
    // The worker creates invitations after the job is queued, so the list fetched
    // at page load is stale until the job reports completion.
    void queryClient.invalidateQueries({ queryKey: ["match-invitations"] });
  }, [jobStatus, queryClient]);

  if (!token) {
    return (
      <EmptyState
        title="Sign in to see invitations"
        body="Matching invitations and accept/decline actions require an account."
        actionHref="/login"
        actionLabel="Sign in"
        icon="sparkles"
      />
    );
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <PageHeader
        eyebrow="Matching"
        title="Matches"
        description="Kinship proposes small groups from compatible interests, availability and distance. Invitations expire after 48 hours."
        action={
          <button
            type="button"
            className="btn-primary"
            onClick={() => request.mutate()}
            disabled={request.isPending}
          >
            {request.isPending ? (
              <>
                <Icon name="loader" size={15} className="animate-spin" />
                Starting…
              </>
            ) : (
              <>
                <Icon name="sparkles" size={16} />
                Find a match
              </>
            )}
          </button>
        }
      />

      <div className="space-y-2">
        {jobId ? (
          <Alert tone={jobStatus === "FAILED" ? "danger" : "info"} live={false}>
            Matching job: <strong>{jobStatus ?? "QUEUED"}</strong>
            {job.data?.error_code ? " · " + job.data.error_code : ""}
            {jobStatus === "DONE" ? " · Invitations will appear below." : ""}
          </Alert>
        ) : null}
        {notice ? <Alert tone="success">{notice}</Alert> : null}
        {error ? <Alert tone="danger">{error}</Alert> : null}
      </div>

      {invitations.isPending ? (
        <RowSkeleton count={2} />
      ) : invitations.isError ? (
        <ErrorState
          message="Could not load invitations."
          title="Invitations unavailable"
          onRetry={() => void invitations.refetch()}
        />
      ) : items.length === 0 ? (
        <EmptyState
          title="No pending invitations"
          body="Start a match or check back after the matching worker runs. New proposals appear here for 48 hours."
          actionHref="/groups"
          actionLabel="My groups"
          icon="sparkles"
        />
      ) : (
        <div className="space-y-4">
          {items.map((invitation: MatchInvitation) => (
            <article key={invitation.invitation_id} className="card overflow-hidden">
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line bg-canvas/70 px-5 py-4">
                <div className="flex items-center gap-2.5">
                  <span className="flex h-9 w-9 items-center justify-center rounded-full bg-brand/10 text-brand">
                    <Icon name="users" size={16} />
                  </span>
                  <div>
                    <h2 className="text-h3 font-semibold text-ink">Proposed group</h2>
                    <p className="text-[0.75rem] text-muted">
                      {invitation.members.length} people with shared interests
                    </p>
                  </div>
                </div>
                <StatusPill label={"Expires " + formatDateTime(invitation.expires_at)} tone="warning" />
              </div>

              <div className="p-5">
                <ul className="grid gap-2 sm:grid-cols-2">
                  {invitation.members.map((member) => (
                    <li
                      key={member.user_id}
                      className="flex items-start gap-3 rounded-md border border-line bg-canvas/40 p-3"
                    >
                      <Avatar name={member.display_name} size="sm" />
                      <div className="min-w-0">
                        <p className="truncate text-small font-semibold text-ink">
                          {member.display_name}
                        </p>
                        <p className="text-[0.75rem] text-muted">
                          {member.distance_km.toFixed(1)} km away
                        </p>
                        {member.interests.length > 0 ? (
                          <p className="mt-1 line-clamp-1 text-[0.75rem] text-faint">
                            {member.interests.slice(0, 3).join(" · ")}
                          </p>
                        ) : null}
                      </div>
                    </li>
                  ))}
                </ul>

                <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-line pt-4">
                  <button
                    type="button"
                    className="btn-primary btn-sm"
                    onClick={() => respond.mutate({ invitationId: invitation.invitation_id, action: "accept" })}
                    disabled={respond.isPending}
                  >
                    <Icon name="check" size={14} />
                    Accept
                  </button>
                  <button
                    type="button"
                    className="btn-secondary btn-sm"
                    onClick={() => respond.mutate({ invitationId: invitation.invitation_id, action: "decline" })}
                    disabled={respond.isPending}
                  >
                    Decline
                  </button>
                  <span className="text-[0.75rem] text-muted">
                    The group forms when everyone accepts.
                  </span>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-4 border-t border-line pt-5">
        <Link href="/groups" className="text-small font-medium text-muted transition hover:text-ink">
          Go to my groups
        </Link>
        <Link href="/profile" className="text-small font-medium text-muted transition hover:text-ink">
          Update matching preferences
        </Link>
      </div>
    </div>
  );
}
