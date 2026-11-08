export type PillTone = "neutral" | "positive" | "warning" | "danger" | "info" | "brand";

const classes: Record<PillTone, string> = {
  neutral: "bg-ink/[0.06] text-muted",
  positive: "bg-success/10 text-success",
  warning: "bg-warning/10 text-warning",
  danger: "bg-danger/10 text-danger",
  info: "bg-info/10 text-info",
  brand: "bg-brand/10 text-brand",
};

const dotClasses: Record<PillTone, string> = {
  neutral: "bg-muted",
  positive: "bg-success",
  warning: "bg-warning",
  danger: "bg-danger",
  info: "bg-info",
  brand: "bg-brand",
};

export function StatusPill({
  label,
  tone = "neutral",
  dot = true,
}: {
  label: string;
  tone?: PillTone;
  dot?: boolean;
}) {
  return (
    <span className={`pill ${classes[tone]}`}>
      {dot ? <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${dotClasses[tone]}`} /> : null}
      {label}
    </span>
  );
}
