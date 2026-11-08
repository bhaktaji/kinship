const TIME_ZONE = "Europe/Berlin";

const dateTimeFormatter = new Intl.DateTimeFormat("en-GB", {
  weekday: "short",
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: TIME_ZONE,
});

const dateFormatter = new Intl.DateTimeFormat("en-GB", {
  weekday: "short",
  day: "numeric",
  month: "short",
  timeZone: TIME_ZONE,
});

const longDateFormatter = new Intl.DateTimeFormat("en-GB", {
  weekday: "long",
  day: "numeric",
  month: "long",
  year: "numeric",
  timeZone: TIME_ZONE,
});

const timeFormatter = new Intl.DateTimeFormat("en-GB", {
  hour: "2-digit",
  minute: "2-digit",
  timeZone: TIME_ZONE,
});

const dayFormatter = new Intl.DateTimeFormat("en-GB", { day: "numeric", timeZone: TIME_ZONE });
const monthFormatter = new Intl.DateTimeFormat("en-GB", { month: "short", timeZone: TIME_ZONE });
const weekdayFormatter = new Intl.DateTimeFormat("en-GB", { weekday: "short", timeZone: TIME_ZONE });
const monthYearFormatter = new Intl.DateTimeFormat("en-GB", {
  month: "long",
  year: "numeric",
  timeZone: TIME_ZONE,
});

export function formatDateTime(value: string): string {
  return dateTimeFormatter.format(new Date(value));
}

export function formatDate(value: string): string {
  return dateFormatter.format(new Date(value));
}

export function formatLongDate(value: string | Date): string {
  return longDateFormatter.format(typeof value === "string" ? new Date(value) : value);
}

export function formatTime(value: string): string {
  return timeFormatter.format(new Date(value));
}

export function formatDayNumber(value: string): string {
  return dayFormatter.format(new Date(value));
}

export function formatMonthShort(value: string): string {
  return monthFormatter.format(new Date(value));
}

export function formatWeekday(value: string): string {
  return weekdayFormatter.format(new Date(value));
}

export function formatMonthYear(value: string): string {
  return monthYearFormatter.format(new Date(value));
}

/** "18:30 – 20:00" on one day, otherwise a compact range across days. */
export function formatTimeRange(start: string, end: string): string {
  const startDate = new Date(start);
  const endDate = new Date(end);
  const sameDay = formatDate(start) === formatDate(end);
  if (sameDay) {
    return `${formatTime(start)} – ${formatTime(end)}`;
  }
  return `${formatDate(start)} ${formatTime(start)} – ${formatDate(end)} ${formatTime(end)}`;
}

export function formatDistance(metres: number): string {
  return metres < 1000 ? metres + " m" : (metres / 1000).toFixed(1) + " km";
}

export function formatCoordinates(lat: number, lng: number): string {
  const latLabel = `${Math.abs(lat).toFixed(2)}°${lat >= 0 ? "N" : "S"}`;
  const lngLabel = `${Math.abs(lng).toFixed(2)}°${lng >= 0 ? "E" : "W"}`;
  return `${latLabel}, ${lngLabel}`;
}

export function statusLabel(status?: string | null): string {
  switch (status) {
    case "GOING":
      return "Going";
    case "NOT_GOING":
      return "Not going";
    case "MAYBE":
      return "Maybe";
    case "DECLINED":
      return "Declined";
    case "PENDING":
      return "Pending approval";
    case "ACTIVE":
      return "Member";
    default:
      return status ?? "Not responded";
  }
}

export function dayName(dayOfWeek: number): string {
  const names = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  // The API stores 1 = Monday … 7 = Sunday.
  return names[dayOfWeek % 7] ?? "Day";
}
