"use client";

import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Alert } from "@/components/Alert";
import { Icon } from "@/components/Icon";
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

const STEPS = [
  { id: 1, label: "About", icon: "user" as const },
  { id: 2, label: "Interests", icon: "heart" as const },
  { id: 3, label: "Schedule", icon: "calendar" as const },
];

export default function OnboardingPage() {
  const { token, user, loading, refreshProfile } = useAuth();
  const [step, setStep] = useState(1);
  const [displayName, setDisplayName] = useState("");
  const [lat, setLat] = useState("52.52");
  const [lng, setLng] = useState("13.405");
  const [selectedInterests, setSelectedInterests] = useState<number[]>([]);
  const [hobbies, setHobbies] = useState("");
  const [bio, setBio] = useState("");
  const [profession, setProfession] = useState("");
  const [workplace, setWorkplace] = useState("");
  const [shareWorkplace, setShareWorkplace] = useState(false);
  const [dayOfWeek, setDayOfWeek] = useState(4);
  const [startLocal, setStartLocal] = useState("18:00");
  const [endLocal, setEndLocal] = useState("21:00");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const interests = useQuery({
    queryKey: ["interests"],
    queryFn: () => apiFetch<InterestsResponse>("/v1/interests", {}, token),
    enabled: Boolean(token),
  });

  useEffect(() => {
    if (!user) return;
    setDisplayName(user.display_name);
    if (user.location) {
      setLat(String(Number(user.location.lat.toFixed(5))));
      setLng(String(Number(user.location.lng.toFixed(5))));
    }
    setSelectedInterests(user.interests.map((i) => i.interest_id));
    setHobbies((user.hobbies ?? []).join(", "));
    setBio(user.bio ?? "");
    setProfession(user.profession ?? "");
    setWorkplace(user.workplace ?? "");
    setShareWorkplace(Boolean(user.workplace_shared));
    if (user.availability.length > 0) {
      const s = user.availability[0];
      setDayOfWeek(s.day_of_week);
      setStartLocal(s.start_local);
      setEndLocal(s.end_local);
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
            availability: [{ day_of_week: dayOfWeek, start_local: startLocal, end_local: endLocal }],
            interests: selectedInterests.map((id) => ({ interest_id: id, weight: 1 })),
            hobbies: hobbies.split(",").map((h) => h.trim()).filter(Boolean),
            bio,
            profession,
            workplace,
            workplace_shared: shareWorkplace,
            onboarding_completed: true,
          }),
        },
        token,
      ),
    onSuccess: async () => {
      setError(null);
      setNotice("Profile complete. Redirecting…");
      await refreshProfile();
      window.location.href = "/";
    },
    onError: (caught) => {
      setNotice(null);
      setError(caught instanceof ApiError ? caught.message : "Could not save profile.");
    },
  });

  if (loading) return <PageSkeleton />;
  if (!token) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand">
          <Icon name="lock" size={20} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Sign in to complete onboarding</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          A short profile helps us suggest groups that fit your interests and week.
        </p>
        <Link href="/login" className="btn-primary mt-5">
          Sign in
        </Link>
      </div>
    );
  }
  if (user?.onboarding_completed) {
    return (
      <div className="card flex flex-col items-center px-6 py-12 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-success/10 text-success">
          <Icon name="check-circle" size={22} />
        </span>
        <h1 className="mt-4 font-display text-h2 text-ink">Your profile is already complete</h1>
        <p className="mt-2 max-w-sm text-body text-muted">
          You can update your details at any time from your profile.
        </p>
        <Link href="/profile" className="btn-primary mt-5">
          Edit profile
        </Link>
      </div>
    );
  }

  function toggleInterest(id: number) {
    setSelectedInterests((current) =>
      current.includes(id) ? current.filter((v) => v !== id) : [...current, id],
    );
  }

  const timeValid = startLocal < endLocal;
  const coordinateLabel =
    lat !== "" && lng !== "" && Number.isFinite(Number(lat)) && Number.isFinite(Number(lng))
      ? formatCoordinates(Number(lat), Number(lng))
      : "No location set";

  function goNext() {
    setError(null);
    if (step === 1 && displayName.trim().length < 2) {
      setError("Add a display name of at least two characters.");
      return;
    }
    if (step === 3 && !timeValid) {
      setError("The end time must be after the start time.");
      return;
    }
    setStep((current) => Math.min(3, current + 1));
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <p className="eyebrow">Welcome to Kinship</p>
        <h1 className="mt-1.5 font-display text-h1 text-ink">Complete your profile</h1>
        <p className="mt-2 max-w-xl text-body text-muted">
          Three short steps so discovery and matching can find the right small circle for you.
        </p>
      </div>

      <nav aria-label="Onboarding progress">
        <ol className="grid grid-cols-3 gap-2">
          {STEPS.map((item) => {
            const active = step === item.id;
            const complete = step > item.id;
            return (
              <li key={item.id}>
                <button
                  type="button"
                  onClick={() => setStep(item.id)}
                  aria-current={active ? "step" : undefined}
                  className={`flex w-full flex-col items-center justify-center gap-1 rounded-md border px-2 py-2 text-center transition sm:flex-row sm:justify-start sm:gap-2 sm:px-3 sm:py-2.5 sm:text-left ${
                    active
                      ? "border-forest bg-forest text-white"
                      : complete
                        ? "border-success/30 bg-success/[0.06] text-success"
                        : "border-line bg-surface text-muted hover:border-ink/20"
                  }`}
                >
                  <span
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[0.75rem] font-semibold ${
                      active
                        ? "bg-white/15 text-white"
                        : complete
                          ? "bg-success/15 text-success"
                          : "bg-canvas text-muted"
                    }`}
                  >
                    {complete ? <Icon name="check" size={13} /> : item.id}
                  </span>
                  <span className="text-[0.75rem] font-semibold leading-tight sm:text-small">
                    {item.label}
                  </span>
                </button>
              </li>
            );
          })}
        </ol>
      </nav>

      <form
        className="card p-5 sm:p-6"
        onSubmit={(event) => {
          event.preventDefault();
          if (step < 3) {
            goNext();
            return;
          }
          if (!timeValid) {
            setError("The end time must be after the start time.");
            return;
          }
          save.mutate();
        }}
      >
        {step === 1 ? (
          <section className="space-y-4" aria-labelledby="onboarding-about">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="user" size={16} />
              <h2 id="onboarding-about" className="text-small font-semibold">
                About you
              </h2>
            </div>

            <div className="field-group">
              <label htmlFor="onboarding-name" className="label">
                Display name
              </label>
              <input
                id="onboarding-name"
                className="field"
                required
                minLength={2}
                maxLength={50}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="How your group will see you"
              />
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="field-group">
                <label htmlFor="onboarding-profession" className="label">
                  Profession <span className="font-normal text-faint">(optional)</span>
                </label>
                <input
                  id="onboarding-profession"
                  className="field"
                  value={profession}
                  onChange={(event) => setProfession(event.target.value)}
                  placeholder="What you do"
                />
              </div>
              <div className="field-group">
                <label htmlFor="onboarding-workplace" className="label">
                  Workplace <span className="font-normal text-faint">(optional)</span>
                </label>
                <input
                  id="onboarding-workplace"
                  className="field"
                  value={workplace}
                  onChange={(event) => setWorkplace(event.target.value)}
                  placeholder="Where you work"
                />
              </div>
            </div>

            <div className="field-group">
              <label htmlFor="onboarding-bio" className="label">
                Short bio <span className="font-normal text-faint">(optional)</span>
              </label>
              <textarea
                id="onboarding-bio"
                className="field min-h-24 resize-y"
                maxLength={500}
                value={bio}
                onChange={(event) => setBio(event.target.value)}
                placeholder="A sentence or two about what you enjoy."
              />
              <p className="hint text-right">{bio.length}/500</p>
            </div>

            <label className="flex items-start gap-2.5 rounded-md border border-line bg-canvas/50 p-3 text-small text-muted">
              <input
                type="checkbox"
                className="mt-0.5 h-4 w-4 rounded border-line text-forest focus:ring-forest/20"
                checked={shareWorkplace}
                onChange={(event) => setShareWorkplace(event.target.checked)}
              />
              Share workplace with group admins
            </label>
          </section>
        ) : null}

        {step === 2 ? (
          <section className="space-y-4" aria-labelledby="onboarding-interests">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2 text-brand">
                <Icon name="heart" size={16} />
                <h2 id="onboarding-interests" className="text-small font-semibold">
                  What are you into?
                </h2>
              </div>
              <span className="text-[0.75rem] font-medium text-muted">
                {selectedInterests.length} selected
              </span>
            </div>
            <p className="text-small text-muted">
              Choose at least two. These shape your matches and discovery.
            </p>

            {interests.isPending ? (
              <div className="flex flex-wrap gap-2">
                {[0, 1, 2, 3, 4, 5].map((index) => (
                  <div key={index} className="skeleton h-11 w-24 rounded-full" />
                ))}
              </div>
            ) : interests.isError ? (
              <Alert tone="danger">Interests could not be loaded. Reconnect and try again.</Alert>
            ) : (
              <div className="flex flex-wrap gap-2">
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

            <div className="field-group">
              <label htmlFor="onboarding-hobbies" className="label">
                Hobbies <span className="font-normal text-faint">(optional)</span>
              </label>
              <input
                id="onboarding-hobbies"
                className="field"
                value={hobbies}
                onChange={(event) => setHobbies(event.target.value)}
                placeholder="Vinyl hunting, baking, bouldering"
              />
              <p className="hint">Separate each hobby with a comma.</p>
            </div>

            {selectedInterests.length > 0 && selectedInterests.length < 2 ? (
              <Alert tone="warning" live={false}>
                Pick one more interest to be eligible for matching.
              </Alert>
            ) : null}
          </section>
        ) : null}

        {step === 3 ? (
          <section className="space-y-5" aria-labelledby="onboarding-availability">
            <div className="flex items-center gap-2 text-brand">
              <Icon name="calendar" size={16} />
              <h2 id="onboarding-availability" className="text-small font-semibold">
                Where and when?
              </h2>
            </div>

            <div>
              <p className="label">Location</p>
              <p className="mt-1 text-small text-muted">
                Used to sort activities by distance. Never shown to other members.
              </p>
              <div className="mt-2 grid gap-3 sm:grid-cols-2">
                <div className="field-group">
                  <label htmlFor="onboarding-lat" className="text-[0.75rem] font-medium text-muted">
                    Latitude
                  </label>
                  <input
                    id="onboarding-lat"
                    className="field"
                    type="number"
                    step="0.0001"
                    min={-90}
                    max={90}
                    required
                    value={lat}
                    onChange={(event) => setLat(event.target.value)}
                  />
                </div>
                <div className="field-group">
                  <label htmlFor="onboarding-lng" className="text-[0.75rem] font-medium text-muted">
                    Longitude
                  </label>
                  <input
                    id="onboarding-lng"
                    className="field"
                    type="number"
                    step="0.0001"
                    min={-180}
                    max={180}
                    required
                    value={lng}
                    onChange={(event) => setLng(event.target.value)}
                  />
                </div>
              </div>
              <p className="mt-2 inline-flex items-center gap-1.5 rounded-full bg-canvas px-3 py-1.5 text-[0.75rem] font-medium text-muted">
                <Icon name="compass" size={13} />
                {coordinateLabel}
              </p>
            </div>

            <div>
              <p className="label">Availability</p>
              <p className="mt-1 text-small text-muted">
                One recurring window is enough to start matching.
              </p>
              <div className="mt-2 grid gap-3 sm:grid-cols-3">
                <div className="field-group">
                  <label htmlFor="onboarding-day" className="text-[0.75rem] font-medium text-muted">
                    Day
                  </label>
                  <select
                    id="onboarding-day"
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
                </div>
                <div className="field-group">
                  <label htmlFor="onboarding-start" className="text-[0.75rem] font-medium text-muted">
                    From
                  </label>
                  <input
                    id="onboarding-start"
                    className="field"
                    type="time"
                    required
                    value={startLocal}
                    onChange={(event) => setStartLocal(event.target.value)}
                  />
                </div>
                <div className="field-group">
                  <label htmlFor="onboarding-end" className="text-[0.75rem] font-medium text-muted">
                    Until
                  </label>
                  <input
                    id="onboarding-end"
                    className="field"
                    type="time"
                    required
                    value={endLocal}
                    onChange={(event) => setEndLocal(event.target.value)}
                  />
                </div>
              </div>
              {!timeValid ? (
                <p className="mt-2 text-small text-danger" role="alert">
                  The end time must be after the start time.
                </p>
              ) : (
                <p className="mt-2 text-small text-muted">
                  You are free on {dayName(dayOfWeek)}, {startLocal}–{endLocal}.
                </p>
              )}
            </div>
          </section>
        ) : null}

        <div className="mt-5 space-y-2">
          {notice ? <Alert tone="success">{notice}</Alert> : null}
          {error ? <Alert tone="danger">{error}</Alert> : null}
        </div>

        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5">
          <button
            type="button"
            className="btn-ghost"
            onClick={() => setStep((current) => Math.max(1, current - 1))}
            disabled={step === 1}
          >
            <Icon name="arrow-left" size={15} />
            Back
          </button>

          {step < 3 ? (
            <button type="button" className="btn-primary" onClick={goNext}>
              Continue
              <Icon name="arrow-right" size={15} />
            </button>
          ) : (
            <button type="submit" className="btn-primary" disabled={save.isPending || !timeValid}>
              {save.isPending ? (
                <>
                  <Icon name="loader" size={15} className="animate-spin" />
                  Saving…
                </>
              ) : (
                "Complete profile"
              )}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
