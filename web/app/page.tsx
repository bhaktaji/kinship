"use client";

import Link from "next/link";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { ActivityCardView } from "@/components/ActivityCardView";
import { Avatar } from "@/components/Avatar";
import { CategoryArt } from "@/components/CategoryArt";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Icon } from "@/components/Icon";
import { CardGridSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { apiFetch, apiFetchConditional } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/format";
import { useOnlineStatus } from "@/lib/use-online";
import type { DiscoveryPage as DiscoveryPageResponse, GroupPage } from "@/lib/types";

const CATEGORIES = [
  { value: "", label: "All" },
  { value: "sport", label: "Sport" },
  { value: "music", label: "Music" },
  { value: "outdoors", label: "Outdoors" },
];

const DEMO_LAT = 52.52;
const DEMO_LNG = 13.405;

/**
 * ETag + body cache for discovery. The API answers a repeat request carrying
 * If-None-Match with 304, so the previously parsed page is reused.
 */
const pageCache = new Map<string, { etag: string | null; data: DiscoveryPageResponse }>();

function discoveryUrl(
  pageToken: string | undefined,
  category: string,
  radiusKm: number,
  sizeMax: string,
  lat: number,
  lng: number,
): string {
  let url =
    "/v1/activities?lat=" +
    lat +
    "&lng=" +
    lng +
    "&radius_km=" +
    radiusKm +
    "&limit=9";
  if (category) {
    url += "&category=" + encodeURIComponent(category);
  }
  if (sizeMax) {
    url += "&group_size_max=" + sizeMax;
  }
  if (pageToken) {
    url += "&page_token=" + encodeURIComponent(pageToken);
  }
  return url;
}

export default function DiscoverPage() {
  const { token, loading, user } = useAuth();
  const online = useOnlineStatus();
  const [category, setCategory] = useState("");
  const [radiusKm, setRadiusKm] = useState(10);
  const [sizeMax, setSizeMax] = useState("");
  const [search, setSearch] = useState("");
  const lat = user?.location?.lat ?? DEMO_LAT;
  const lng = user?.location?.lng ?? DEMO_LNG;

  const query = useInfiniteQuery({
    queryKey: ["activities", token, category, radiusKm, sizeMax, lat, lng],
    queryFn: async ({ pageParam }) => {
      const url = discoveryUrl(pageParam || undefined, category, radiusKm, sizeMax, lat, lng);
      const cached = pageCache.get(url);
      const result = await apiFetchConditional<DiscoveryPageResponse>(
        url,
        {},
        token,
        cached?.etag ?? null,
      );
      const data = result.notModified && cached ? cached.data : result.data;
      if (!data) {
        throw new Error("Discovery response was empty");
      }
      pageCache.set(url, { etag: result.etag ?? cached?.etag ?? null, data });
      if (pageCache.size > 60) {
        pageCache.clear();
        pageCache.set(url, { etag: result.etag ?? null, data });
      }
      return data;
    },
    initialPageParam: "",
    getNextPageParam: (last) => last.next_page_token ?? undefined,
    // Offline the cached discovery response is served by the service worker even
    // though no access token could be refreshed; signing in is still required
    // online.
    enabled: !loading && (Boolean(token) || !online),
  });

  const items = useMemo(
    () => query.data?.pages.flatMap((page) => page.items) ?? [],
    [query.data],
  );
  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) {
      return items;
    }
    return items.filter((activity) =>
      [activity.title, activity.group_name, activity.category, activity.description ?? ""]
        .join(" ")
        .toLowerCase()
        .includes(needle),
    );
  }, [items, search]);

  const myGroups = useQuery({
    queryKey: ["home-groups", token],
    queryFn: () => apiFetch<GroupPage>("/v1/groups?mine=true&limit=3", {}, token),
    enabled: Boolean(token),
  });
  const groups = (myGroups.data?.items ?? []).slice(0, 3);

  const showSignedOutHero = !loading && !user;

  return (
    <div className="space-y-9">
      <section className="relative overflow-hidden rounded-xl border border-line bg-surface px-5 py-7 sm:px-8 sm:py-9 lg:grid lg:grid-cols-[1.15fr_0.85fr] lg:items-center lg:gap-10 lg:px-10 lg:py-10">
        <div className="relative z-10">
          <div className="flex flex-wrap items-center gap-2">
            <span className="eyebrow">Kinship · Berlin pilot</span>
            <span className="hidden h-1 w-1 rounded-full bg-line sm:block" aria-hidden="true" />
            <span className="text-small text-muted">Small groups, real life</span>
          </div>
          <h1 className="mt-3 max-w-2xl font-display text-display text-ink">
            Find a small group that keeps showing up.
          </h1>
          <p className="mt-3 max-w-xl text-body leading-relaxed text-muted">
            Join four to six people around a shared interest, meet weekly or monthly, and let the
            same faces become familiar.
          </p>

          {showSignedOutHero ? (
            <div className="mt-6 flex flex-wrap items-center gap-3">
              <Link href="/register" className="btn-primary">
                Create your account
                <Icon name="arrow-right" size={16} />
              </Link>
              <Link href="/login" className="btn-secondary">
                Sign in
              </Link>
            </div>
          ) : (
            <div className="mt-5 flex flex-wrap items-center gap-x-5 gap-y-2 text-small text-muted">
              <span className="inline-flex items-center gap-1.5">
                <Icon name="users" size={15} className="text-sage" />
                Groups of 4–6
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Icon name="repeat" size={15} className="text-sage" />
                Recurring meetups
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Icon name="map-pin" size={15} className="text-sage" />
                Near you
              </span>
            </div>
          )}
        </div>

        <div className="relative mt-7 hidden lg:mt-0 lg:block" aria-hidden="true">
          <div className="ml-auto w-[92%] rotate-[1.5deg] rounded-lg border border-line bg-canvas p-3 shadow-card">
            <CategoryArt category="music" className="h-28 rounded-md" />
            <div className="flex items-center justify-between gap-3 px-1 pt-3">
              <div>
                <p className="text-small font-semibold text-ink">Live Music Night</p>
                <p className="text-[0.6875rem] text-muted">Thu · 18:30 · Neukölln</p>
              </div>
              <span className="pill bg-success/10 text-success">4/6 joined</span>
            </div>
          </div>
          <div className="absolute -bottom-5 left-0 w-56 -rotate-2 rounded-md border border-line bg-surface p-3 shadow-lift">
            <div className="flex items-center gap-2">
              <div className="flex -space-x-2">
                <Avatar name="Mara Klein" size="xs" ring />
                <Avatar name="Jonas Weber" size="xs" ring />
                <Avatar name="Ana Ruiz" size="xs" ring />
              </div>
              <p className="text-[0.6875rem] font-medium text-muted">People you&rsquo;ll meet</p>
            </div>
          </div>
        </div>
      </section>

      {user && user.onboarding_completed === false ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-brand/25 bg-brand-soft px-4 py-3">
          <p className="flex items-center gap-2 text-small font-medium text-brand">
            <Icon name="sparkles" size={16} />
            Finish your profile to unlock better matches and nearby circles.
          </p>
          <Link href="/onboarding" className="btn-primary btn-sm">
            Finish profile
            <Icon name="arrow-right" size={14} />
          </Link>
        </div>
      ) : null}

      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_19rem]">
        <div className="min-w-0 space-y-6">
          <section className="space-y-4" aria-labelledby="activities-title">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <div>
                <h2 id="activities-title" className="font-display text-h2 text-ink">
                  Activities near you
                </h2>
                <p className="mt-1 text-small text-muted">
                  {user?.location
                    ? "Centred on your saved location. Use the filters to narrow the list."
                    : "Centred on central Berlin. Save a location in your profile to personalise it."}
                </p>
              </div>
              {user ? (
                <StatusPill label={`${items.length} ${items.length === 1 ? "activity" : "activities"}`} tone="neutral" dot={false} />
              ) : null}
            </div>

            <div className="rounded-lg border border-line bg-surface p-3 sm:p-4">
              <div className="flex flex-col gap-3 lg:flex-row lg:items-end">
                <label className="block flex-1">
                  <span className="sr-only">Search activities</span>
                  <span className="relative block">
                    <Icon
                      name="search"
                      size={16}
                      className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-faint"
                    />
                    <input
                      className="field pl-10"
                      type="search"
                      placeholder="Search activities or circles"
                      value={search}
                      onChange={(event) => setSearch(event.target.value)}
                    />
                  </span>
                </label>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:w-[28rem]">
                  <label className="field-group col-span-2 sm:col-span-1">
                    <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.08em] text-faint">
                      Category
                    </span>
                    <select
                      className="field !px-2.5"
                      value={category}
                      onChange={(event) => setCategory(event.target.value)}
                    >
                      {CATEGORIES.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="field-group">
                    <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.08em] text-faint">
                      Radius
                    </span>
                    <select
                      className="field !px-2.5"
                      value={radiusKm}
                      onChange={(event) => setRadiusKm(Number(event.target.value))}
                    >
                      {[2, 5, 10, 20, 50].map((value) => (
                        <option key={value} value={value}>
                          {value} km
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="field-group">
                    <span className="text-[0.6875rem] font-semibold uppercase tracking-[0.08em] text-faint">
                      Group size
                    </span>
                    <select
                      className="field !px-2.5"
                      value={sizeMax}
                      onChange={(event) => setSizeMax(event.target.value)}
                    >
                      <option value="">Any</option>
                      <option value="4">Up to 4</option>
                      <option value="6">Up to 6</option>
                      <option value="8">Up to 8</option>
                    </select>
                  </label>
                </div>
              </div>
            </div>

            {loading || (query.isPending && (token || !online)) ? (
              <CardGridSkeleton />
            ) : !token && online ? (
              <EmptyState
                title="Sign in to see activities"
                body="Discovery and joining groups require an account. It takes less than a minute to join Kinship."
                actionHref="/login"
                actionLabel="Sign in"
                secondaryHref="/register"
                secondaryLabel="Create account"
                icon="compass"
              />
            ) : query.isError ? (
              <ErrorState
                message={
                  !online
                    ? "You are offline and no discovery page is cached yet. Reconnect, then open the list once."
                    : "We could not load activities right now. Check your connection and try again."
                }
                onRetry={() => void query.refetch()}
              />
            ) : filtered.length === 0 ? (
              <EmptyState
                title={items.length === 0 ? "No activities match these filters" : "No activities match your search"}
                body={
                  items.length === 0
                    ? "Try a wider radius or another category."
                    : "Clear the search or adjust the filters to see more circles."
                }
                actionHref={items.length === 0 ? "/groups" : undefined}
                actionLabel={items.length === 0 ? "See my groups" : undefined}
                icon="search"
              />
            ) : (
              <>
                <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                  {filtered.map((activity) => (
                    <ActivityCardView key={activity.activity_id} activity={activity} />
                  ))}
                </div>
                {!online ? (
                  <p className="text-center text-small text-muted">
                    Showing a saved copy. Reload when you are back online for the latest activities.
                  </p>
                ) : null}
                {query.hasNextPage ? (
                  <div className="flex justify-center pt-1">
                    <button
                      type="button"
                      className="btn-secondary"
                      onClick={() => void query.fetchNextPage()}
                      disabled={query.isFetchingNextPage || !online}
                    >
                      {query.isFetchingNextPage ? "Loading…" : online ? "Load more" : "Offline"}
                    </button>
                  </div>
                ) : null}
              </>
            )}
          </section>
        </div>

        <aside className="space-y-5" aria-label="Your Kinship">
          <section className="card overflow-hidden">
            <div className="flex items-center justify-between gap-2 border-b border-line px-4 py-3.5">
              <h2 className="text-small font-semibold text-ink">Your circles</h2>
              <Link href="/groups" className="text-small font-medium text-brand hover:underline">
                View all
              </Link>
            </div>
            {!token ? (
              <div className="px-4 py-5 text-small text-muted">
                Sign in to see the circles you belong to.
              </div>
            ) : myGroups.isPending ? (
              <div className="space-y-2 p-4">
                <div className="skeleton h-12" />
                <div className="skeleton h-12" />
              </div>
            ) : groups.length === 0 ? (
              <div className="px-4 py-5">
                <p className="text-small text-muted">You haven&rsquo;t joined a circle yet.</p>
                <Link href="/groups" className="mt-3 inline-flex items-center gap-1 text-small font-semibold text-brand">
                  Find your first group <Icon name="arrow-right" size={14} />
                </Link>
              </div>
            ) : (
              <ul className="divide-y divide-line">
                {groups.map((group) => (
                  <li key={group.group_id}>
                    <Link href={"/groups/" + group.group_id} className="flex items-center gap-3 px-4 py-3 transition hover:bg-canvas/70">
                      <CategoryArt category={group.category} className="h-10 w-10 shrink-0 rounded-sm" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-small font-semibold text-ink">{group.name}</p>
                        <p className="mt-0.5 truncate text-[0.6875rem] text-muted">
                          {group.next_event_at ? `${formatDate(group.next_event_at)} · ${formatTime(group.next_event_at)}` : "No upcoming event"}
                        </p>
                      </div>
                      <Icon name="chevron-right" size={15} className="shrink-0 text-faint" />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card p-4">
            <h2 className="text-small font-semibold text-ink">How Kinship works</h2>
            <ol className="mt-3 space-y-3">
              {[
                { icon: "compass" as const, title: "Find an interest", body: "Browse local activities and small circles." },
                { icon: "users" as const, title: "Join a group", body: "Four to six people who meet regularly." },
                { icon: "calendar" as const, title: "Keep showing up", body: "RSVP to the next meetup and stay in touch." },
              ].map((step, index) => (
                <li key={step.title} className="flex gap-3">
                  <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand">
                    <Icon name={step.icon} size={14} />
                  </span>
                  <div>
                    <p className="text-small font-semibold text-ink">
                      {index + 1}. {step.title}
                    </p>
                    <p className="text-[0.75rem] leading-relaxed text-muted">{step.body}</p>
                  </div>
                </li>
              ))}
            </ol>
          </section>

          <section className="rounded-lg border border-line bg-forest p-4 text-white">
            <div className="flex items-center gap-2">
              <Icon name="sparkles" size={16} />
              <h2 className="text-small font-semibold">Let Kinship match you</h2>
            </div>
            <p className="mt-1.5 text-[0.75rem] leading-relaxed text-white/75">
              Share your interests and availability, and we&rsquo;ll propose a small group that fits.
            </p>
            <Link
              href="/invitations"
              className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-white px-3.5 py-2 text-[0.75rem] font-semibold text-forest transition hover:bg-white/90"
            >
              See matches <Icon name="arrow-right" size={13} />
            </Link>
          </section>
        </aside>
      </div>
    </div>
  );
}
