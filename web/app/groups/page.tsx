"use client";

import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert } from "@/components/Alert";
import { Dialog } from "@/components/Dialog";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { GroupCardView } from "@/components/GroupCardView";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { CardGridSkeleton } from "@/components/Skeletons";
import { StatusPill } from "@/components/StatusPill";
import { useAuth } from "@/lib/auth-context";
import { apiFetch } from "@/lib/api";
import type { GroupPage } from "@/lib/types";

const AREAS = [
  { value: "centre", label: "Central Berlin", lat: "52.5200", lng: "13.4050" },
  { value: "kreuzberg", label: "Kreuzberg", lat: "52.4970", lng: "13.4230" },
  { value: "neukoelln", label: "Neukölln", lat: "52.4810", lng: "13.4350" },
  { value: "prenzlauerberg", label: "Prenzlauer Berg", lat: "52.5400", lng: "13.4240" },
  { value: "charlottenburg", label: "Charlottenburg", lat: "52.5160", lng: "13.3040" },
  { value: "wedding", label: "Wedding", lat: "52.5490", lng: "13.3650" },
];

const CATEGORIES = ["sport", "music", "outdoors", "food", "books", "art"];

export default function GroupsPage() {
  const { token, loading } = useAuth();
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("sport");
  const [area, setArea] = useState("centre");
  const [lat, setLat] = useState("52.52");
  const [lng, setLng] = useState("13.405");
  const [maxMembers, setMaxMembers] = useState("6");
  const [visibility, setVisibility] = useState("public");
  const [joinPolicy, setJoinPolicy] = useState("OPEN");
  const [createError, setCreateError] = useState<string | null>(null);

  const createGroup = useMutation({
    mutationFn: () =>
      apiFetch("/v1/groups", {
        method: "POST",
        body: JSON.stringify({
          name,
          description,
          category,
          location: { lat: Number(lat), lng: Number(lng) },
          max_members: Number(maxMembers),
          visibility,
          join_policy: joinPolicy,
        }),
      }, token),
    onSuccess: () => {
      setCreateError(null);
      setShowCreate(false);
      setName("");
      setDescription("");
      void queryClient.invalidateQueries({ queryKey: ["my-groups"] });
      void queryClient.invalidateQueries({ queryKey: ["home-groups"] });
    },
    onError: (caught) => setCreateError(caught instanceof Error ? caught.message : "Could not create group."),
  });

  const query = useInfiniteQuery({
    queryKey: ["my-groups", token],
    queryFn: ({ pageParam }) =>
      apiFetch<GroupPage>(
        "/v1/groups?mine=true&limit=20" + (pageParam ? "&page_token=" + encodeURIComponent(pageParam) : ""),
        {},
        token,
      ),
    initialPageParam: "",
    getNextPageParam: (last) => last.next_page_token ?? undefined,
    enabled: Boolean(token),
  });

  const items = query.data?.pages.flatMap((page) => page.items) ?? [];

  function chooseArea(value: string) {
    setArea(value);
    const match = AREAS.find((option) => option.value === value);
    if (match) {
      setLat(match.lat);
      setLng(match.lng);
    }
  }

  if (!loading && !token) {
    return (
      <EmptyState
        title="Sign in to see your groups"
        body="Your joined and pending groups appear here, along with the next time everyone meets."
        actionHref="/login"
        actionLabel="Sign in"
        secondaryHref="/register"
        secondaryLabel="Create account"
        icon="users"
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Your circles"
        title="My groups"
        description="The recurring circles you belong to, join requests and next meetups."
        action={
          <>
            <StatusPill label={`${items.length} ${items.length === 1 ? "group" : "groups"}`} dot={false} />
            <button type="button" className="btn-primary" onClick={() => setShowCreate(true)}>
              <Icon name="plus" size={17} />
              Create group
            </button>
          </>
        }
      />

      <Dialog
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title="Create a group"
        description="Start a small circle around something you already love doing."
        wide
      >
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            createGroup.mutate();
          }}
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="field-group sm:col-span-2">
              <span className="label">Group name</span>
              <input
                className="field"
                required
                maxLength={80}
                placeholder="Saturday morning bouldering"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <label className="field-group">
              <span className="label">Category</span>
              <select className="field capitalize" value={category} onChange={(event) => setCategory(event.target.value)}>
                {CATEGORIES.map((option) => (
                  <option key={option} value={option} className="capitalize">
                    {option}
                  </option>
                ))}
              </select>
            </label>
            <label className="field-group">
              <span className="label">Maximum members</span>
              <input
                className="field"
                type="number"
                min={2}
                max={20}
                required
                value={maxMembers}
                onChange={(event) => setMaxMembers(event.target.value)}
              />
            </label>
            <label className="field-group sm:col-span-2">
              <span className="label">What is this group about?</span>
              <textarea
                className="field min-h-24 resize-y"
                maxLength={500}
                placeholder="A short description helps the right people find you."
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
            </label>
            <label className="field-group">
              <span className="label">Area</span>
              <select className="field" value={area} onChange={(event) => chooseArea(event.target.value)}>
                {AREAS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <span className="hint">Approximate location used for discovery.</span>
            </label>
            <div className="grid grid-cols-2 gap-2">
              <label className="field-group">
                <span className="label">Latitude</span>
                <input
                  className="field"
                  type="number"
                  step="0.0001"
                  required
                  value={lat}
                  onChange={(event) => setLat(event.target.value)}
                />
              </label>
              <label className="field-group">
                <span className="label">Longitude</span>
                <input
                  className="field"
                  type="number"
                  step="0.0001"
                  required
                  value={lng}
                  onChange={(event) => setLng(event.target.value)}
                />
              </label>
            </div>
            <label className="field-group">
              <span className="label">Visibility</span>
              <select className="field" value={visibility} onChange={(event) => setVisibility(event.target.value)}>
                <option value="public">Public — anyone can find it</option>
                <option value="private">Private — invite or request only</option>
              </select>
            </label>
            <label className="field-group">
              <span className="label">Join policy</span>
              <select className="field" value={joinPolicy} onChange={(event) => setJoinPolicy(event.target.value)}>
                <option value="OPEN">Open — join instantly</option>
                <option value="APPROVAL">Approval — you review requests</option>
              </select>
            </label>
          </div>

          {createError ? <Alert tone="danger">{createError}</Alert> : null}

          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line pt-4">
            <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={createGroup.isPending}>
              {createGroup.isPending ? "Creating…" : "Create group"}
            </button>
          </div>
        </form>
      </Dialog>

      {loading || query.isPending ? (
        <CardGridSkeleton count={3} />
      ) : query.isError ? (
        <ErrorState message="Could not load your groups." onRetry={() => void query.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState
          title="You have not joined a group yet"
          body="Browse an activity and join a small recurring group, or start your own circle."
          actionHref="/"
          actionLabel="Discover activities"
          secondaryHref="/invitations"
          secondaryLabel="Find a match"
          icon="users"
        />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {items.map((group) => (
              <GroupCardView key={group.group_id} group={group} />
            ))}
          </div>
          {query.hasNextPage ? (
            <div className="flex justify-center">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => void query.fetchNextPage()}
                disabled={query.isFetchingNextPage}
              >
                {query.isFetchingNextPage ? "Loading…" : "Load more"}
              </button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
