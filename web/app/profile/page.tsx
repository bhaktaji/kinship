"use client";

import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Alert } from "@/components/Alert";
import { Avatar } from "@/components/Avatar";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { PageSkeleton } from "@/components/Skeletons";
import { useAuth } from "@/lib/auth-context";
import { apiFetch, ApiError } from "@/lib/api";
import { dayName, formatCoordinates } from "@/lib/format";
import type { InterestsResponse, User } from "@/lib/types";

const DAYS = [
  { value: 1, label: "Monday" },
  { value: 2, label: "Tuesday" },
  { value: 3, label: "Wednesday" },
  { value: 4, label: "Thursday" },
  { value: 5, label: "Friday" },
  { value: 6, label: "Saturday" },
  { value: 7, label: "Sunday" },
];

export default function ProfilePage() {
  const { token, user, loading, refreshProfile } = useAuth();
  const [displayName, setDisplayName] = useState("");
  const [lat, setLat] = useState("52.52");
  const [lng, setLng] = useState("13.405");
  const [selectedInterests, setSelectedInterests] = useState<number[]>([]);
  const [dayOfWeek, setDayOfWeek] = useState(4);
  const [startLocal, setStartLocal] = useState("18:00");
  const [endLocal, setEndLocal] = useState("21:00");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [photoId, setPhotoId] = useState<string | undefined>(undefined);

  const interests = useQuery({
    queryKey: ["interests"],
    queryFn: () => apiFetch<InterestsResponse>("/v1/interests", {}, token),
    enabled: Boolean(token),
    staleTime: 5 * 60_000,
  });

  useEffect(() => {
    if (!user) {
      return;
    }
    setDisplayName(user.display_name);
    if (user.location) {
      // The API stores float32 coordinates; avoid showing 52.52000045776367 in the field.
      setLat(String(Number(user.location.lat.toFixed(5))));
      setLng(String(Number(user.location.lng.toFixed(5))));
    }
    setSelectedInterests(user.interests.map((interest) => interest.interest_id));
    setPhotoId(user.profile_photo_asset_id);
    if (user.availability.length > 0) {
      const slot = user.availability[0];
      setDayOfWeek(slot.day_of_week);
      setStartLocal(slot.start_local);
      setEndLocal(slot.end_local);
    }
  }, [user]);

  const save = useMutation({
    mutationFn: () =>
      apiFetch<User>(
        "/v1/users/me",
        {
          method: "PATCH",
          body: JSON.stringify({
            display_name: displayName,
            location: { lat: Number(lat), lng: Number(lng) },
            availability: [
              { day_of_week: dayOfWeek, start_local: startLocal, end_local: endLocal },
            ],
            interests: selectedInterests.map((id) => ({ interest_id: id, weight: 1 })),
            profile_photo_asset_id: photoId,
          }),
        },
        token,
      ),
    onSuccess: async () => {
      setError(null);
      setNotice("Profile saved. Discovery now uses your location.");
      await refreshProfile();
    },
    onError: (caught) => {
      setNotice(null);
      setError(
        caught instanceof ApiError
          ? caught.message
          : "We could not save your profile. Check your connection and try again.",
      );
    },
  });

  function toggleInterest(id: number) {
    setSelectedInterests((current) =>
      current.includes(id) ? current.filter((value) => value !== id) : [...current, id],
    );
  }

  if (loading) {
    return <PageSkeleton />;
  }

  if (!token) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
          <Icon name="user" size={20} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Sign in to edit your profile</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          Your location, interests and availability power discovery and matching.
        </p>
        <Link href="/login" className="btn-primary mt-5">
          Sign in
        </Link>
      </div>
    );
  }

  const readyForMatching =
    selectedInterests.length >= 2 &&
    lat !== "" &&
    lng !== "" &&
    startLocal < endLocal;

  const coordinateLabel =
    lat !== "" && lng !== "" && Number.isFinite(Number(lat)) && Number.isFinite(Number(lng))
      ? formatCoordinates(Number(lat), Number(lng))
      : "No location set";

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <PageHeader
        eyebrow="Account"
        title="Your profile"
        description="Discovery uses your location, and matching uses your interests and availability."
      />

      <form
        className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_19rem]"
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <div className="space-y-4">
          <section className="card p-5 sm:p-6" aria-labelledby="identity-title">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="user" size={16} />
              <h2 id="identity-title" className="text-small font-semibold">
                About you
              </h2>
            </div>

            <div className="mt-4 flex flex-col items-center gap-5 sm:flex-row sm:items-start">
              <div className="relative shrink-0">
                <Avatar name={displayName || "?"} size="xl" />
                <label className="absolute -bottom-1 -right-1 flex h-9 w-9 cursor-pointer items-center justify-center rounded-full bg-forest text-white shadow-md transition hover:bg-forest/90 focus-within:outline focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-brand">
                  <span className="sr-only">Upload profile photo</span>
                  <Icon name="image" size={15} />
                  <input
                    className="sr-only"
                    type="file"
                    accept="image/*"
                    onChange={async (event) => {
                      const file = event.target.files?.[0];
                      if (!file || !token) return;
                      const form = new FormData();
                      form.append("file", file);
                      const res = await fetch(
                        (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080") + "/v1/images?type=PROFILE",
                        { method: "POST", headers: { Authorization: "Bearer " + token }, body: form, credentials: "include" },
                      );
                      if (res.ok) {
                        const body = await res.json();
                        setPhotoId(body.image_id);
                        setNotice("Photo uploaded. Save profile to apply.");
                      } else {
                        setError("Photo upload failed. Try a smaller image.");
                      }
                    }}
                  />
                </label>
              </div>

              <div className="w-full space-y-3">
                <label className="field-group block">
                  <span className="label">Display name</span>
                  <input
                    className="field"
                    required
                    minLength={2}
                    maxLength={50}
                    value={displayName}
                    onChange={(event) => setDisplayName(event.target.value)}
                    autoComplete="nickname"
                  />
                </label>
                <div className="flex flex-wrap items-center gap-2 text-small">
                  {photoId ? (
                    <>
                      <span className="pill bg-success/10 text-success">Photo uploaded</span>
                      <button
                        type="button"
                        className="font-semibold text-danger hover:underline"
                        onClick={() => {
                          setPhotoId(undefined);
                          setNotice("Photo removed. Save profile to apply.");
                        }}
                      >
                        Remove photo
                      </button>
                    </>
                  ) : (
                    <span className="text-muted">
                      No photo yet — a default avatar is used. Square JPG, PNG or WebP up to 5 MB.
                    </span>
                  )}
                </div>
              </div>
            </div>
          </section>

          <section className="card p-5 sm:p-6" aria-labelledby="location-title">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="map-pin" size={16} />
              <h2 id="location-title" className="text-small font-semibold">
                Location
              </h2>
            </div>
            <p className="mt-1.5 text-small text-muted">
              Used to sort activities by distance. Never shown to other members.
            </p>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <label className="field-group block">
                <span className="label">Latitude</span>
                <input
                  className="field"
                  type="number"
                  step="0.0001"
                  min={-90}
                  max={90}
                  required
                  value={lat}
                  onChange={(event) => setLat(event.target.value)}
                />
              </label>
              <label className="field-group block">
                <span className="label">Longitude</span>
                <input
                  className="field"
                  type="number"
                  step="0.0001"
                  min={-180}
                  max={180}
                  required
                  value={lng}
                  onChange={(event) => setLng(event.target.value)}
                />
              </label>
            </div>
            <p className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-canvas px-3 py-1.5 text-[0.75rem] font-medium text-muted">
              <Icon name="compass" size={13} />
              {coordinateLabel}
            </p>
          </section>

          <section className="card p-5 sm:p-6" aria-labelledby="interests-title">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2 text-brand">
                <Icon name="heart" size={16} />
                <h2 id="interests-title" className="text-small font-semibold">
                  Interests
                </h2>
              </div>
              <span className="text-[0.75rem] font-medium text-muted">
                {selectedInterests.length} selected
              </span>
            </div>
            <p className="mt-1.5 text-small text-muted">Choose at least two to be eligible for matching.</p>

            {interests.isPending ? (
              <div className="mt-4 flex flex-wrap gap-2">
                {[0, 1, 2, 3, 4, 5].map((index) => (
                  <div key={index} className="skeleton h-9 w-24 rounded-full" />
                ))}
              </div>
            ) : interests.isError ? (
              <Alert tone="danger" className="mt-4">
                Interests could not be loaded. Reconnect and try again.
              </Alert>
            ) : (
              <div className="mt-4 flex flex-wrap gap-2">
                {(interests.data?.items ?? []).map((interest) => {
                  const active = selectedInterests.includes(interest.id);
                  return (
                    <label
                      key={interest.id}
                      className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-full border px-3.5 py-2 text-small font-medium transition ${
                        active
                          ? "border-forest bg-forest text-white"
                          : "border-line bg-surface text-muted hover:border-ink/25 hover:text-ink"
                      }`}
                    >
                      <input
                        type="checkbox"
                        className="sr-only"
                        checked={active}
                        onChange={() => toggleInterest(interest.id)}
                      />
                      {active ? <Icon name="check" size={14} /> : null}
                      {interest.name}
                    </label>
                  );
                })}
              </div>
            )}
          </section>

          <section className="card p-5 sm:p-6" aria-labelledby="availability-title">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="calendar" size={16} />
              <h2 id="availability-title" className="text-small font-semibold">
                Availability
              </h2>
            </div>
            <p className="mt-1.5 text-small text-muted">
              One recurring window is enough for the first version of matching.
            </p>
            <div className="mt-4 grid gap-3 sm:grid-cols-3">
              <label className="field-group block">
                <span className="label">Day</span>
                <select
                  className="field"
                  value={dayOfWeek}
                  onChange={(event) => setDayOfWeek(Number(event.target.value))}
                >
                  {DAYS.map((day) => (
                    <option key={day.value} value={day.value}>
                      {day.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field-group block">
                <span className="label">From</span>
                <input
                  className="field"
                  type="time"
                  required
                  value={startLocal}
                  onChange={(event) => setStartLocal(event.target.value)}
                />
              </label>
              <label className="field-group block">
                <span className="label">Until</span>
                <input
                  className="field"
                  type="time"
                  required
                  value={endLocal}
                  onChange={(event) => setEndLocal(event.target.value)}
                />
              </label>
            </div>
            {startLocal >= endLocal ? (
              <p className="mt-3 text-small text-danger" role="alert">
                The end time must be after the start time.
              </p>
            ) : null}
          </section>

          <div className="space-y-2">
            {notice ? <Alert tone="success">{notice}</Alert> : null}
            {error ? <Alert tone="danger">{error}</Alert> : null}
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="submit"
              className="btn-primary"
              disabled={save.isPending || startLocal >= endLocal}
            >
              {save.isPending ? (
                <>
                  <Icon name="loader" size={15} className="animate-spin" />
                  Saving…
                </>
              ) : (
                "Save profile"
              )}
            </button>
            <span className="text-small text-muted">
              {readyForMatching
                ? "You are eligible for matching."
                : "Add a location, two interests and a valid availability window to match."}
            </span>
          </div>
        </div>

        <aside className="space-y-4">
          <section className="card p-4 sm:p-5">
            <h2 className="text-small font-semibold text-ink">Profile preview</h2>
            <div className="mt-4 flex flex-col items-center rounded-md bg-canvas/70 p-5 text-center">
              <Avatar name={displayName || "?"} size="lg" />
              <p className="mt-3 text-body font-semibold text-ink">
                {displayName || "Your name"}
              </p>
              <p className="mt-0.5 text-small text-muted">{coordinateLabel}</p>
              <div className="mt-3 flex flex-wrap justify-center gap-1.5">
                {(interests.data?.items ?? [])
                  .filter((interest) => selectedInterests.includes(interest.id))
                  .slice(0, 4)
                  .map((interest) => (
                    <span key={interest.id} className="pill bg-brand/10 text-brand">
                      {interest.name}
                    </span>
                  ))}
                {selectedInterests.length === 0 ? (
                  <span className="text-[0.75rem] text-faint">No interests selected</span>
                ) : null}
              </div>
            </div>
          </section>

          <section className="card p-4 sm:p-5">
            <h2 className="text-small font-semibold text-ink">Matching readiness</h2>
            <ul className="mt-3 space-y-2.5 text-small">
              <li className="flex items-start gap-2">
                <Icon
                  name={selectedInterests.length >= 2 ? "check-circle" : "alert-circle"}
                  size={16}
                  className={selectedInterests.length >= 2 ? "mt-0.5 text-success" : "mt-0.5 text-warning"}
                />
                <span className="text-muted">At least two interests</span>
              </li>
              <li className="flex items-start gap-2">
                <Icon
                  name={lat !== "" && lng !== "" ? "check-circle" : "alert-circle"}
                  size={16}
                  className={lat !== "" && lng !== "" ? "mt-0.5 text-success" : "mt-0.5 text-warning"}
                />
                <span className="text-muted">A saved location</span>
              </li>
              <li className="flex items-start gap-2">
                <Icon
                  name={startLocal < endLocal ? "check-circle" : "alert-circle"}
                  size={16}
                  className={startLocal < endLocal ? "mt-0.5 text-success" : "mt-0.5 text-warning"}
                />
                <span className="text-muted">A valid availability window</span>
              </li>
            </ul>
            <p className="mt-3 border-t border-line pt-3 text-[0.75rem] text-muted">
              Your window: {dayName(dayOfWeek)}, {startLocal}–{endLocal}
            </p>
          </section>

          <Link href="/invitations" className="btn-secondary w-full">
            <Icon name="sparkles" size={16} />
            Go to matches
          </Link>
        </aside>
      </form>
    </div>
  );
}
