export function CapacityMeter({
  current,
  max,
  compact = false,
  label = "members",
  format = "ratio",
  className = "",
}: {
  current: number;
  max: number;
  compact?: boolean;
  label?: string;
  format?: "ratio" | "words";
  className?: string;
}) {
  const safeMax = Math.max(max, 1);
  const percent = Math.min(100, Math.round((current / safeMax) * 100));
  const full = current >= max;

  return (
    <div className={className}>
      <div className="flex items-center justify-between gap-2 text-small text-muted">
        <span className="inline-flex items-center gap-1.5">
          <span className={`inline-block h-1.5 w-1.5 rounded-full ${full ? "bg-brand" : "bg-sage"}`} aria-hidden="true" />
          <span>
            {format === "words" ? (
              <>
                <span className="font-semibold text-ink">{current}</span> of {max}
              </>
            ) : (
              <>
                <span className="font-semibold text-ink">{current}</span>/{max}
              </>
            )}
            {compact ? "" : " " + label}
          </span>
        </span>
        {!compact ? <span className="text-[0.6875rem] uppercase tracking-[0.08em] text-faint">{full ? "Full" : `${max - current} open`}</span> : null}
      </div>
      <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-ink/[0.08]" aria-hidden="true">
        <div
          className={`h-full rounded-full transition-all duration-500 ${full ? "bg-brand" : "bg-sage"}`}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}
