import Link from "next/link";
import { formatDayNumber, formatMonthShort, formatTime, statusLabel } from "@/lib/format";
import type { EventSummary } from "@/lib/types";
import { CapacityMeter } from "./CapacityMeter";
import { Icon } from "./Icon";
import { StatusPill } from "./StatusPill";

export function EventRow({ event }: { event: EventSummary }) {
  const cancelled = event.status === "CANCELLED";
  const going = event.my_status === "GOING";

  return (
    <Link
      href={"/events/" + event.event_id}
      className={`group flex items-center gap-3 rounded-lg border border-line bg-surface p-3 transition duration-300 ease-out-soft hover:-translate-y-0.5 hover:border-ink/20 hover:shadow-card sm:gap-4 sm:p-4 ${
        cancelled ? "opacity-60" : ""
      }`}
    >
      <div
        className={`flex h-14 w-14 shrink-0 flex-col items-center justify-center rounded-md ${
          going ? "bg-forest text-white" : "bg-canvas text-ink"
        }`}
        aria-hidden="true"
      >
        <span className="text-[0.625rem] font-semibold uppercase tracking-[0.08em] opacity-75">
          {formatMonthShort(event.start_time)}
        </span>
        <span className="font-display text-xl leading-none">{formatDayNumber(event.start_time)}</span>
      </div>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="truncate text-small font-semibold text-ink">{event.title}</p>
          {cancelled ? <StatusPill label="Cancelled" tone="danger" dot={false} /> : null}
        </div>
        <p className="mt-0.5 flex items-center gap-1.5 text-small text-muted">
          <Icon name="clock" size={12} />
          {formatTime(event.start_time)}
          <span className="text-faint">·</span>
          {event.capacity > 0 ? `${event.confirmed_count}/${event.capacity} going` : "Open"}
        </p>
        <div className="mt-2 max-w-44">
          <CapacityMeter current={event.confirmed_count} max={event.capacity || 1} compact />
        </div>
      </div>

      <div className="flex shrink-0 flex-col items-end gap-1.5">
        {event.my_status ? (
          <StatusPill
            label={statusLabel(event.my_status)}
            tone={event.my_status === "GOING" ? "positive" : event.my_status === "MAYBE" ? "warning" : "neutral"}
          />
        ) : (
          <span className="text-small font-medium text-brand">RSVP</span>
        )}
        <Icon
          name="chevron-right"
          size={16}
          className="text-faint transition group-hover:translate-x-0.5 group-hover:text-ink"
        />
      </div>
    </Link>
  );
}
