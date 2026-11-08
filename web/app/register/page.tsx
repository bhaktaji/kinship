"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Alert } from "@/components/Alert";
import { Icon } from "@/components/Icon";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";

export default function RegisterPage() {
  const router = useRouter();
  const { register } = useAuth();
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const passwordReady = password.length >= 10 && /[a-zA-Z]/.test(password) && /\d/.test(password);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register({ email, password, displayName, timezone: "Europe/Berlin" });
      router.push("/");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Unable to create the account. Please retry.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto grid max-w-4xl gap-0 overflow-hidden rounded-xl border border-line bg-surface shadow-card lg:mt-6 lg:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-brand p-8 text-white lg:flex">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 opacity-30"
          style={{
            backgroundImage:
              "radial-gradient(circle at 85% 10%, rgba(255,255,255,0.4), transparent 44%), radial-gradient(circle at 8% 92%, rgba(255,255,255,0.24), transparent 48%)",
          }}
        />
        <div className="relative">
          <span className="inline-flex items-center gap-2 text-small font-semibold uppercase tracking-[0.18em] text-white/85">
            <Icon name="leaf" size={18} />
            Kinship
          </span>
          <h2 className="mt-10 font-display text-[2.4rem] leading-[1.08] tracking-tight">
            Find your people, not just a plan.
          </h2>
          <p className="mt-4 max-w-xs text-body leading-relaxed text-white/80">
            Join a small group built around what you love, then keep showing up together.
          </p>
        </div>
        <div className="relative mt-10 rounded-lg border border-white/20 bg-white/10 p-4">
          <div className="flex items-center gap-2 text-small font-semibold">
            <Icon name="sparkles" size={16} />
            How it works
          </div>
          <ol className="mt-3 space-y-2 text-small text-white/80">
            <li className="flex gap-2">
              <span className="font-semibold text-white">1.</span> Tell us what you enjoy.
            </li>
            <li className="flex gap-2">
              <span className="font-semibold text-white">2.</span> We suggest a compatible circle.
            </li>
            <li className="flex gap-2">
              <span className="font-semibold text-white">3.</span> Meet up, then meet again.
            </li>
          </ol>
        </div>
      </aside>

      <div className="p-6 sm:p-8 lg:p-10">
        <div className="lg:hidden">
          <span className="inline-flex items-center gap-2 text-small font-semibold uppercase tracking-[0.18em] text-brand">
            <Icon name="leaf" size={17} />
            Kinship
          </span>
        </div>
        <h1 className="mt-4 font-display text-h1 text-ink lg:mt-0">Join Kinship</h1>
        <p className="mt-2 text-body text-muted">
          Create a profile, find a group, and meet in person.
        </p>

        <form className="mt-6 space-y-4" onSubmit={onSubmit}>
          <div className="field-group">
            <label htmlFor="register-name" className="label">
              Display name
            </label>
            <input
              id="register-name"
              className="field field-lg"
              required
              minLength={2}
              maxLength={50}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              autoComplete="nickname"
              placeholder="How your group will see you"
            />
          </div>

          <div className="field-group">
            <label htmlFor="register-email" className="label">
              Email
            </label>
            <input
              id="register-email"
              className="field field-lg"
              type="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="email"
              placeholder="you@example.com"
            />
          </div>

          <div className="field-group">
            <label htmlFor="register-password" className="label">
              Password
            </label>
            <div className="relative">
              <input
                id="register-password"
                className="field field-lg pr-12"
                type={showPassword ? "text" : "password"}
                required
                minLength={10}
                maxLength={72}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="new-password"
                placeholder="At least 10 characters"
              />
              <button
                type="button"
                className="absolute right-1 top-1 flex h-11 w-11 items-center justify-center rounded-full text-muted transition hover:text-ink"
                onClick={() => setShowPassword((value) => !value)}
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                <Icon name={showPassword ? "eye-off" : "eye"} size={18} />
              </button>
            </div>
            <span
              className={`mt-1 block text-[0.75rem] ${
                password.length === 0
                  ? "text-muted"
                  : passwordReady
                    ? "text-success"
                    : "text-warning"
              }`}
            >
              At least 10 characters, with a letter and a digit.
            </span>
          </div>

          {error ? <Alert tone="danger">{error}</Alert> : null}

          <button type="submit" className="btn-primary w-full" disabled={submitting}>
            {submitting ? (
              <>
                <Icon name="loader" size={15} className="animate-spin" />
                Creating account…
              </>
            ) : (
              "Create account"
            )}
          </button>
        </form>

        <p className="mt-6 border-t border-line pt-5 text-small text-muted">
          Already a member?{" "}
          <Link href="/login" className="font-semibold text-brand hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}
