import Link from "next/link";
import { formatDate, formatDistance, formatTime } from "@/lib/format";
import type { ActivityCard } from "@/lib/types";
import { CategoryArt } from "./CategoryArt";
import { CapacityMeter } from "./CapacityMeter";
import { Icon } from "./Icon";

export function ActivityCardView({ activity }: { activity: ActivityCard }) {
  return (
    <Link
      href={"/activities/" + activity.activity_id}
      className="card card-hover group flex flex-col overflow-hidden"
    >
      <div className="relative">
        <CategoryArt category={activity.category} className="h-28 sm:h-32" />
        <span className="absolute left-3 top-3 pill bg-surface/90 text-ink backdrop-blur">
          {activity.category}
        </span>
        <span className="absolute bottom-3 right-3 inline-flex items-center gap-1 rounded-full bg-ink/80 px-2.5 py-1 text-[0.6875rem] font-semibold text-white backdrop-blur">
          <Icon name="map-pin" size={11} />
          {formatDistance(activity.distance_m)}
        </span>
      </div>

      <div className="flex flex-1 flex-col p-4">
        <h3 className="text-h3 font-semibold leading-snug text-ink transition group-hover:text-brand">
          {activity.title}
        </h3>
        <p className="mt-1 flex items-center gap-1.5 text-small text-muted">
          <Icon name="users" size={13} className="shrink-0" />
          <span className="truncate">{activity.group_name}</span>
        </p>

        <div className="mt-auto pt-4">
          <CapacityMeter current={activity.member_count} max={activity.max_members} />
          <div className="mt-3 flex items-center justify-between gap-2 border-t border-line pt-3 text-small">
            <span className="inline-flex items-center gap-1.5 text-muted">
              <Icon name="calendar" size={13} />
              {formatDate(activity.next_event.start_time)}
            </span>
            <span className="font-medium text-ink">{formatTime(activity.next_event.start_time)}</span>
          </div>
        </div>
      </div>
    </Link>
  );
}
