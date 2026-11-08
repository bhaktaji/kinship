"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { apiFetch, refreshAccessToken } from "./api";
import { clearOutbox } from "./outbox";
import type { AuthResponse, User } from "./types";

interface RegisterInput {
  email: string;
  password: string;
  displayName: string;
  timezone: string;
}

interface AuthContextValue {
  token: string | null;
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  /** Restores a session from the refresh cookie, e.g. after an offline period. */
  reauthenticate: () => Promise<string | null>;
  /** Re-reads the profile after a PATCH so screens share the new location/interests. */
  refreshProfile: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Cached API responses in the service worker belong to the session that fetched
 * them. Purge them whenever the session starts or ends so a second account on the
 * same device never sees the previous account's groups.
 */
function purgeSessionCaches() {
  if (typeof navigator !== "undefined" && "serviceWorker" in navigator) {
    navigator.serviceWorker.controller?.postMessage({ type: "PURGE_DATA" });
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const access = await refreshAccessToken();
        if (access) {
          const me = await apiFetch<User>("/v1/users/me", {}, access);
          if (!cancelled) {
            setToken(access);
            setUser(me);
          }
        }
      } catch {
        // Unauthenticated on first load is a normal state.
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    purgeSessionCaches();
    await clearOutbox();
    const response = await apiFetch<AuthResponse>("/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
    setToken(response.access_token);
    setUser(response.user);
  }, []);

  const register = useCallback(async (input: RegisterInput) => {
    purgeSessionCaches();
    await clearOutbox();
    const response = await apiFetch<AuthResponse>("/v1/auth/register", {
      method: "POST",
      body: JSON.stringify({
        email: input.email,
        password: input.password,
        display_name: input.displayName,
        timezone: input.timezone,
      }),
    });
    setToken(response.access_token);
    setUser(response.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await apiFetch<void>("/v1/auth/logout", { method: "POST" });
    } finally {
      purgeSessionCaches();
      await clearOutbox();
      setToken(null);
      setUser(null);
    }
  }, []);

  const reauthenticate = useCallback(async () => {
    const access = await refreshAccessToken();
    if (!access) {
      return null;
    }
    try {
      const me = await apiFetch<User>("/v1/users/me", {}, access);
      setUser(me);
    } catch {
      // The token is still usable even if the profile read failed.
    }
    setToken(access);
    return access;
  }, []);

  const refreshProfile = useCallback(async () => {
    if (!token) {
      return;
    }
    const me = await apiFetch<User>("/v1/users/me", {}, token);
    setUser(me);
  }, [token]);

  const value = useMemo(
    () => ({ token, user, loading, login, register, logout, reauthenticate, refreshProfile }),
    [token, user, loading, login, register, logout, reauthenticate, refreshProfile],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}
