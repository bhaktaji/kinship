"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { apiFetch } from "@/lib/api";
import {
  OUTBOX_CHANGED_EVENT,
  flushOutbox,
  pendingCount,
} from "@/lib/outbox";
import { useOnlineStatus } from "@/lib/use-online";
import { Icon } from "./Icon";

/**
 * Offline/queued-action banner.
 *
 * Actions queued while offline (see lib/outbox.ts) are replayed once when the
 * browser reports that it is online again, using a freshly refreshed token. The
 * queue entries are deleted on success, so a reconnect flushes exactly once.
 */
export function OfflineBanner() {
  const online = useOnlineStatus();
  const { token, reauthenticate } = useAuth();
  const queryClient = useQueryClient();
  const [pending, setPending] = useState(0);
  const [delivered, setDelivered] = useState(0);
  const [busy, setBusy] = useState(false);

  const refreshCount = useCallback(async () => {
    try {
      setPending(await pendingCount());
    } catch {
      setPending(0);
    }
  }, []);

  const flush = useCallback(async () => {
    if (busy) {
      return;
    }
    const queued = await pendingCount().catch(() => 0);
    if (queued === 0) {
      return;
    }
    setBusy(true);
    try {
      const activeToken = token ?? (await reauthenticate());
      if (!activeToken) {
        return;
      }
      const result = await flushOutbox(async (action) => {
        await apiFetch(
          action.url,
          { method: action.method, body: action.body ?? undefined },
          activeToken,
        );
      });
      if (result.sent > 0) {
        setDelivered(result.sent);
        // Let React publish the reauthenticated token to mounted queries before
        // they refetch; otherwise a flushed RSVP would refetch with the stale,
        // pre-offline (null) token.
        await new Promise((resolve) => setTimeout(resolve, 250));
        await queryClient.invalidateQueries();
      }
    } finally {
      setBusy(false);
      await refreshCount();
    }
  }, [busy, token, reauthenticate, queryClient, refreshCount]);

  const flushRef = useRef(flush);
  flushRef.current = flush;

  useEffect(() => {
    void refreshCount();
    const onChanged = () => {
      void refreshCount();
      if (navigator.onLine) {
        void flushRef.current();
      }
    };
    const onOnline = () => void flushRef.current();
    window.addEventListener(OUTBOX_CHANGED_EVENT, onChanged);
    window.addEventListener("online", onOnline);
    if (typeof navigator !== "undefined" && navigator.onLine) {
      void flushRef.current();
    }
    return () => {
      window.removeEventListener(OUTBOX_CHANGED_EVENT, onChanged);
      window.removeEventListener("online", onOnline);
    };
  }, [refreshCount]);

  useEffect(() => {
    if (delivered === 0) {
      return;
    }
    const timer = setTimeout(() => setDelivered(0), 5000);
    return () => clearTimeout(timer);
  }, [delivered]);

  if (online && pending === 0 && delivered === 0) {
    return null;
  }

  if (!online) {
    return (
      <div
        className="border-b border-warning/20 bg-warning/[0.09] px-4 py-2 text-center text-small font-medium text-warning"
        role="status"
        aria-live="polite"
      >
        <span className="inline-flex items-center gap-2">
          <Icon name="info" size={15} />
          You&rsquo;re offline &mdash; showing saved content.
          {pending > 0 ? ` ${pending} action${pending === 1 ? "" : "s"} queued.` : ""}
        </span>
      </div>
    );
  }

  return (
    <div
      className="border-b border-success/20 bg-success/[0.08] px-4 py-2 text-center text-small font-medium text-success"
      role="status"
      aria-live="polite"
    >
      <span className="inline-flex items-center gap-2">
        <Icon name="check-circle" size={15} />
        {pending > 0
          ? `Back online — sending ${pending} queued action${pending === 1 ? "" : "s"}…`
          : `${delivered} queued action${delivered === 1 ? "" : "s"} sent.`}
      </span>
    </div>
  );
}
