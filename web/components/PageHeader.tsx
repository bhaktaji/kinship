import type { ReactNode } from "react";
import Link from "next/link";
import { Icon } from "./Icon";

export function PageHeader({
  eyebrow,
  title,
  description,
  action,
  className = "",
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={`flex flex-wrap items-end justify-between gap-4 ${className}`}>
      <div className="min-w-0">
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1 className="mt-1.5 font-display text-h1 text-ink">{title}</h1>
        {description ? <p className="mt-2 max-w-2xl text-body text-muted">{description}</p> : null}
      </div>
      {action ? <div className="flex shrink-0 flex-wrap items-center gap-2">{action}</div> : null}
    </div>
  );
}

export function SectionHeading({
  title,
  description,
  action,
  className = "",
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={`flex flex-wrap items-end justify-between gap-3 ${className}`}>
      <div>
        <h2 className="font-display text-h2 text-ink">{title}</h2>
        {description ? <p className="mt-1 text-small text-muted">{description}</p> : null}
      </div>
      {action ? <div className="flex shrink-0 items-center gap-2">{action}</div> : null}
    </div>
  );
}

export function BackLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="inline-flex items-center gap-1.5 text-small font-medium text-muted transition hover:text-ink"
    >
      <Icon name="arrow-left" size={15} />
      {children}
    </Link>
  );
}
