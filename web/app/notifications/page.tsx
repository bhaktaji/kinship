"use client";

import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { Alert } from "@/components/Alert";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Icon, type IconName } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { RowSkeleton } from "@/components/Skeletons";
import { useAuth } from "@/lib/auth-context";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { Notification, NotificationPage } from "@/lib/types";

function notificationHref(n: Notification): string | null {
  if (n.type === "NEW_MESSAGE" && n.entity_id) {
    return "/groups/" + n.entity_id + "/chat";
  }
  if (n.entity_type === "GROUP" && n.entity_id) {
    return "/groups/" + n.entity_id;
  }
  if (n.entity_type === "EVENT" && n.entity_id) {
    return "/events/" + n.entity_id;
  }
  return null;
}

function notificationIcon(n: Notification): IconName {
  if (n.type === "NEW_MESSAGE") return "message";
  if (n.entity_type === "EVENT") return "calendar";
  if (n.entity_type === "GROUP") return "users";
  return "bell";
}

function dayGroup(value: string): string {
  const date = new Date(value);
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startOfDate = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const days = Math.round((startOfToday - startOfDate) / 86_400_000);
  if (days <= 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days < 7) return "This week";
  return "Earlier";
}

export default function NotificationsPage() {
  const { token } = useAuth();
  const router = useRouter();
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ["notifications", token],
    queryFn: () => apiFetch<NotificationPage>("/v1/notifications", {}, token),
    enabled: Boolean(token),
    refetchInterval: 30000,
  });

  const markAll = useMutation({
    mutationFn: () => apiFetch<void>("/v1/notifications/read-all", { method: "POST" }, token),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["notifications"] }),
  });
  const markOne = useMutation({
    mutationFn: (id: string) => apiFetch<void>("/v1/notifications/" + id + "/read", { method: "PATCH" }, token),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["notifications"] }),
  });

  function open(n: Notification) {
    if (!n.read_at) {
      markOne.mutate(n.id);
    }
    const href = notificationHref(n);
    if (href) {
      router.push(href);
    }
  }

  if (!token) {
    return (
      <EmptyState
        title="Sign in to see notifications"
        body="Group updates, new messages and meetup reminders appear here once you are signed in."
        actionHref="/login"
        actionLabel="Sign in"
        icon="bell"
      />
    );
  }

  const items = query.data?.items ?? [];
  const unread = query.data?.unread_count ?? 0;
  const groups = ["Today", "Yesterday", "This week", "Earlier"];

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader
        eyebrow="Inbox"
        title="Notifications"
        description={
          unread > 0
            ? `${unread} unread update${unread === 1 ? "" : "s"} from your circles.`
            : "You are all caught up."
        }
        action={
          <button
            type="button"
            className="btn-secondary btn-sm"
            onClick={() => markAll.mutate()}
            disabled={markAll.isPending || unread === 0}
          >
            <Icon name="check" size={14} />
            {markAll.isPending ? "Marking…" : "Mark all read"}
          </button>
        }
      />

      {markAll.isError ? <Alert tone="danger">Could not mark notifications as read.</Alert> : null}

      {query.isPending ? <RowSkeleton count={4} /> : null}

      {query.isError ? (
        <ErrorState
          message="Could not load notifications."
          title="Notifications unavailable"
          onRetry={() => void query.refetch()}
        />
      ) : null}

      {!query.isPending && !query.isError && items.length === 0 ? (
        <EmptyState
          title="No notifications yet"
          body="When someone messages your group or a meetup is announced, it will show up here."
          actionHref="/groups"
          actionLabel="Go to my groups"
          icon="bell"
        />
      ) : null}

      {items.length > 0 ? (
        <div className="space-y-6">
          {groups.map((group) => {
            const groupItems = items.filter((n) => dayGroup(n.created_at) === group);
            if (groupItems.length === 0) return null;
            return (
              <section key={group} className="space-y-2" aria-labelledby={"notif-" + group}>
                <h2
                  id={"notif-" + group}
                  className="px-1 text-micro font-semibold uppercase tracking-[0.16em] text-faint"
                >
                  {group}
                </h2>
                <div className="overflow-hidden rounded-lg border border-line bg-surface">
                  {groupItems.map((n, index) => {
                    const href = notificationHref(n);
                    const unreadItem = !n.read_at;
                    return (
                      <article
                        key={n.id}
                        className={`flex items-start gap-3 p-4 transition sm:p-5 ${
                          index > 0 ? "border-t border-line" : ""
                        } ${unreadItem ? "bg-brand-soft/30" : ""}`}
                      >
                        <span
                          className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${
                            unreadItem ? "bg-brand/10 text-brand" : "bg-canvas text-muted"
                          }`}
                        >
                          <Icon name={notificationIcon(n)} size={16} />
                        </span>

                        <div className="min-w-0 flex-1">
                          {href ? (
                            <Link
                              href={href}
                              onClick={() => {
                                if (unreadItem) markOne.mutate(n.id);
                              }}
                              className="group block rounded-sm"
                            >
                              <p className="text-small font-semibold text-ink group-hover:text-brand">
                                {n.title}
                              </p>
                              {n.body ? (
                                <p className="mt-0.5 text-small leading-relaxed text-muted">{n.body}</p>
                              ) : null}
                            </Link>
                          ) : (
                            <button
                              type="button"
                              onClick={() => open(n)}
                              className="block w-full rounded-sm text-left"
                            >
                              <p className="text-small font-semibold text-ink">{n.title}</p>
                              {n.body ? (
                                <p className="mt-0.5 text-small leading-relaxed text-muted">{n.body}</p>
                              ) : null}
                            </button>
                          )}
                          <p className="mt-1.5 flex flex-wrap items-center gap-2 text-[0.75rem] text-faint">
                            <span>{formatDateTime(n.created_at)}</span>
                            {unreadItem ? (
                              <>
                                <span aria-hidden="true">·</span>
                                <span className="font-semibold text-brand">Unread</span>
                              </>
                            ) : null}
                          </p>
                        </div>

                        {unreadItem ? (
                          <button
                            type="button"
                            onClick={() => markOne.mutate(n.id)}
                            disabled={markOne.isPending && markOne.variables === n.id}
                            className="shrink-0 rounded-full px-2.5 py-1.5 text-[0.75rem] font-semibold text-brand transition hover:bg-brand/10 disabled:opacity-50"
                            aria-label={"Mark " + n.title + " as read"}
                          >
                            Mark read
                          </button>
                        ) : null}
                      </article>
                    );
                  })}
                </div>
              </section>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
