import Link from "next/link";
import { formatDateTime } from "@/lib/format";
import type { GroupCard } from "@/lib/types";
import { CategoryArt } from "./CategoryArt";
import { CapacityMeter } from "./CapacityMeter";
import { Icon } from "./Icon";
import { StatusPill } from "./StatusPill";

export function GroupCardView({ group }: { group: GroupCard }) {
  const pending = group.my_status === "PENDING";
  return (
    <Link href={"/groups/" + group.group_id} className="card card-hover group flex overflow-hidden">
      <CategoryArt category={group.category} className="hidden w-24 shrink-0 sm:block" />
      <div className="min-w-0 flex-1 p-4 sm:p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="truncate text-h3 font-semibold text-ink transition group-hover:text-brand">
              {group.name}
            </h3>
            <p className="mt-0.5 text-micro font-semibold uppercase tracking-[0.12em] text-faint">
              {group.category}
            </p>
          </div>
          {/* The list payload has no role, so "Joined" is the truthful label for owners too. */}
          <StatusPill label={pending ? "Pending" : "Joined"} tone={pending ? "warning" : "positive"} />
        </div>

        <div className="mt-3.5">
          <CapacityMeter current={group.member_count} max={group.max_members} compact />
        </div>

        <div className="mt-3 flex items-center gap-1.5 border-t border-line pt-3 text-small text-muted">
          <Icon name="calendar" size={13} />
          {group.next_event_at ? (
            <span>{formatDateTime(group.next_event_at)}</span>
          ) : (
            <span>No upcoming event</span>
          )}
        </div>
      </div>
    </Link>
  );
}
