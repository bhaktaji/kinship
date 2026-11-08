"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Alert } from "@/components/Alert";
import { Icon } from "@/components/Icon";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      router.push("/");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Unable to sign in. Please retry.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto grid max-w-4xl gap-0 overflow-hidden rounded-xl border border-line bg-surface shadow-card lg:mt-6 lg:grid-cols-[0.9fr_1.1fr]">
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-forest p-8 text-white lg:flex">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 opacity-30"
          style={{
            backgroundImage:
              "radial-gradient(circle at 80% 12%, rgba(255,255,255,0.35), transparent 42%), radial-gradient(circle at 10% 88%, rgba(255,255,255,0.22), transparent 46%)",
          }}
        />
        <div className="relative">
          <span className="inline-flex items-center gap-2 text-small font-semibold uppercase tracking-[0.18em] text-white/80">
            <Icon name="leaf" size={18} />
            Kinship
          </span>
          <h2 className="mt-10 font-display text-[2.4rem] leading-[1.08] tracking-tight">
            Good things happen in small circles.
          </h2>
          <p className="mt-4 max-w-xs text-body leading-relaxed text-white/75">
            Recurring meetups, familiar faces, and a group that actually knows your name.
          </p>
        </div>
        <div className="relative mt-10 space-y-3">
          {[
            { icon: "users" as const, text: "Groups of four to six, not crowds" },
            { icon: "repeat" as const, text: "The same people, again and again" },
            { icon: "map-pin" as const, text: "Close to home, easy to show up" },
          ].map((item) => (
            <div key={item.text} className="flex items-center gap-3 text-small text-white/80">
              <span className="flex h-8 w-8 items-center justify-center rounded-full bg-white/10">
                <Icon name={item.icon} size={15} />
              </span>
              {item.text}
            </div>
          ))}
        </div>
      </aside>

      <div className="p-6 sm:p-8 lg:p-10">
        <div className="lg:hidden">
          <span className="inline-flex items-center gap-2 text-small font-semibold uppercase tracking-[0.18em] text-brand">
            <Icon name="leaf" size={17} />
            Kinship
          </span>
        </div>
        <h1 className="mt-4 font-display text-h1 text-ink lg:mt-0">Welcome back</h1>
        <p className="mt-2 text-body text-muted">
          Sign in to see your groups and the next meetup.
        </p>

        <form className="mt-6 space-y-4" onSubmit={onSubmit}>
          <div className="field-group">
            <label htmlFor="login-email" className="label">
              Email
            </label>
            <input
              id="login-email"
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
            <label htmlFor="login-password" className="label">
              Password
            </label>
            <div className="relative">
              <input
                id="login-password"
                className="field field-lg pr-12"
                type={showPassword ? "text" : "password"}
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="current-password"
                placeholder="Your password"
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
          </div>

          {error ? <Alert tone="danger">{error}</Alert> : null}

          <button type="submit" className="btn-primary w-full" disabled={submitting}>
            {submitting ? (
              <>
                <Icon name="loader" size={15} className="animate-spin" />
                Signing in…
              </>
            ) : (
              "Sign in"
            )}
          </button>
        </form>

        <p className="mt-6 border-t border-line pt-5 text-small text-muted">
          New here?{" "}
          <Link href="/register" className="font-semibold text-brand hover:underline">
            Create an account
          </Link>
        </p>
      </div>
    </div>
  );
}
