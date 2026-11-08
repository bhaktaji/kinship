import { ApiError } from "./api";

/**
 * Offline action outbox (docs/mobile_optimization.md §4.3).
 *
 * Only idempotent, user-initiated actions are queued (currently RSVP). The queue
 * lives in IndexedDB, is capped at 20 entries, expires after 24 hours, and is
 * deleted as soon as the API accepts an entry so a reconnect flushes exactly once.
 */

const DB_NAME = "kinship";
const DB_VERSION = 1;
const STORE = "outbox";
const MAX_ACTIONS = 20;
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

export const OUTBOX_CHANGED_EVENT = "kinship:outbox-changed";

export interface QueuedAction {
  id: string;
  url: string;
  method: string;
  body: string | null;
  createdAt: number;
}

function supported(): boolean {
  return typeof window !== "undefined" && "indexedDB" in window;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: "id" }).createIndex("createdAt", "createdAt");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Unable to open the offline queue"));
  });
}

function notify() {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(OUTBOX_CHANGED_EVENT));
  }
}

export async function queueAction(url: string, method: string, body: unknown): Promise<boolean> {
  if (!supported()) {
    return false;
  }
  const db = await openDb();
  const id = method + " " + url + " " + (body === undefined ? "" : JSON.stringify(body));
  const entries = await listActions();
  const now = Date.now();
  const fresh = entries.filter((entry) => now - entry.createdAt < MAX_AGE_MS);
  const withoutDuplicate = fresh.filter((entry) => entry.id !== id);
  const trimmed = withoutDuplicate.slice(-(MAX_ACTIONS - 1));
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(STORE, "readwrite");
    const store = transaction.objectStore(STORE);
    store.clear();
    const action: QueuedAction = {
      id,
      url,
      method,
      body: body === undefined ? null : JSON.stringify(body),
      createdAt: now,
    };
    for (const entry of [...trimmed, action]) {
      store.put(entry);
    }
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("Unable to queue the action"));
  });
  db.close();
  notify();
  return true;
}

export async function listActions(): Promise<QueuedAction[]> {
  if (!supported()) {
    return [];
  }
  const db = await openDb();
  const actions = await new Promise<QueuedAction[]>((resolve, reject) => {
    const transaction = db.transaction(STORE, "readonly");
    const request = transaction.objectStore(STORE).getAll();
    request.onsuccess = () => resolve((request.result as QueuedAction[]) ?? []);
    request.onerror = () => reject(request.error ?? new Error("Unable to read the offline queue"));
  });
  db.close();
  return actions.sort((left, right) => left.createdAt - right.createdAt);
}

export async function pendingCount(): Promise<number> {
  const now = Date.now();
  const actions = await listActions();
  return actions.filter((entry) => now - entry.createdAt < MAX_AGE_MS).length;
}

export async function clearOutbox(): Promise<void> {
  if (!supported()) {
    return;
  }
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(STORE, "readwrite");
    transaction.objectStore(STORE).clear();
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("Unable to clear the offline queue"));
  });
  db.close();
  notify();
}

export interface FlushResult {
  sent: number;
  dropped: number;
  remaining: number;
}

let flushing = false;

/**
 * Replays queued actions in order with a fresh access token. Successful entries
 * are deleted immediately; entries rejected by the API (4xx) are dropped because
 * they can never succeed; transport failures stop the flush and keep the rest.
 */
export async function flushOutbox(
  fetchAction: (action: QueuedAction) => Promise<void>,
): Promise<FlushResult> {
  if (flushing || !supported()) {
    return { sent: 0, dropped: 0, remaining: 0 };
  }
  flushing = true;
  let sent = 0;
  let dropped = 0;
  try {
    const actions = await listActions();
    const now = Date.now();
    for (const action of actions) {
      if (now - action.createdAt >= MAX_AGE_MS) {
        await removeAction(action.id);
        dropped += 1;
        continue;
      }
      try {
        await fetchAction(action);
        await removeAction(action.id);
        sent += 1;
      } catch (error) {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          await removeAction(action.id);
          dropped += 1;
          continue;
        }
        break;
      }
    }
    return { sent, dropped, remaining: await pendingCount() };
  } finally {
    flushing = false;
    notify();
  }
}

async function removeAction(id: string): Promise<void> {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(STORE, "readwrite");
    transaction.objectStore(STORE).delete(id);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("Unable to update the offline queue"));
  });
  db.close();
}
