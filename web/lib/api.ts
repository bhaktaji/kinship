export const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

interface ErrorBody {
  error?: { code?: string; message?: string };
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: ErrorBody = {};
  try {
    body = (await response.json()) as ErrorBody;
  } catch {
    // Non-JSON error response.
  }
  return new ApiError(
    response.status,
    body.error?.code ?? "ERROR",
    body.error?.message ?? "Request failed",
  );
}

function buildHeaders(options: RequestInit, token?: string | null): Headers {
  const headers = new Headers(options.headers);
  headers.set("Content-Type", "application/json");
  if (token) {
    headers.set("Authorization", "Bearer " + token);
  }
  return headers;
}

async function request<T>(path: string, options: RequestInit = {}, token?: string | null): Promise<T> {
  const response = await fetch(API_BASE + path, {
    ...options,
    headers: buildHeaders(options, token),
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  // Some successful endpoints (membership updates, logout, RSVP no-ops) return an
  // empty 200 body; parsing those as JSON used to surface a false error.
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

export function apiFetch<T>(path: string, options: RequestInit = {}, token?: string | null): Promise<T> {
  return request<T>(path, options, token);
}

export interface ConditionalResult<T> {
  data: T | null;
  etag: string | null;
  notModified: boolean;
}

/**
 * Conditional GET used by discovery. The API returns a strong ETag for
 * `/v1/activities` (docs/lld.md §3.2); replaying it with `If-None-Match`
 * yields 304 and no body.
 */
export async function apiFetchConditional<T>(
  path: string,
  options: RequestInit = {},
  token?: string | null,
  etag?: string | null,
): Promise<ConditionalResult<T>> {
  const headers = buildHeaders(options, token);
  if (etag) {
    headers.set("If-None-Match", etag);
  }
  const response = await fetch(API_BASE + path, {
    ...options,
    headers,
    credentials: "include",
    cache: "no-store",
  });
  if (response.status === 304) {
    return { data: null, etag: etag ?? null, notModified: true };
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  return {
    data: (await response.json()) as T,
    etag: response.headers.get("ETag"),
    notModified: false,
  };
}

export async function refreshAccessToken(): Promise<string | null> {
  try {
    const body = await request<{ access_token: string }>("/v1/auth/refresh", { method: "POST" });
    return body.access_token;
  } catch (error) {
    if (error instanceof ApiError && error.status !== 401) {
      console.warn("refresh failed", error.code);
    }
    return null;
  }
}
