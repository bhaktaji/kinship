import Link from "next/link";
import { Icon, type IconName } from "./Icon";

export function EmptyState({
  title,
  body,
  actionHref,
  actionLabel,
  icon = "sparkles",
  secondaryHref,
  secondaryLabel,
}: {
  title: string;
  body: string;
  actionHref?: string;
  actionLabel?: string;
  icon?: IconName;
  secondaryHref?: string;
  secondaryLabel?: string;
}) {
  return (
    <div className="card flex flex-col items-center px-6 py-12 text-center sm:px-10 sm:py-14">
      <span className="flex h-14 w-14 items-center justify-center rounded-full bg-brand-soft text-brand">
        <Icon name={icon} size={24} />
      </span>
      <h2 className="mt-4 font-display text-h2 text-ink">{title}</h2>
      <p className="mt-2 max-w-md text-body text-muted">{body}</p>
      {actionHref && actionLabel ? (
        <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
          <Link href={actionHref} className="btn-primary">
            {actionLabel}
            <Icon name="arrow-right" size={16} />
          </Link>
          {secondaryHref && secondaryLabel ? (
            <Link href={secondaryHref} className="btn-secondary">
              {secondaryLabel}
            </Link>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
