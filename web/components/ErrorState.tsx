"use client";

import { Icon } from "./Icon";

export function ErrorState({
  message,
  onRetry,
  title = "Something went wrong",
}: {
  message: string;
  onRetry?: () => void;
  title?: string;
}) {
  return (
    <div className="card flex flex-col items-center px-6 py-12 text-center" role="alert">
      <span className="flex h-12 w-12 items-center justify-center rounded-full bg-danger/10 text-danger">
        <Icon name="alert-circle" size={22} />
      </span>
      <h2 className="mt-4 font-display text-h2 text-ink">{title}</h2>
      <p className="mt-2 max-w-md text-body text-muted">{message}</p>
      {onRetry ? (
        <button type="button" className="btn-secondary mt-5" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}
