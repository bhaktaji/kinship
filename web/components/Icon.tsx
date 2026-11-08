import type { SVGProps } from "react";

export type IconName =
  | "compass"
  | "users"
  | "sparkles"
  | "user"
  | "bell"
  | "calendar"
  | "map-pin"
  | "arrow-right"
  | "arrow-left"
  | "chevron-right"
  | "chevron-down"
  | "plus"
  | "send"
  | "check"
  | "x"
  | "clock"
  | "lock"
  | "globe"
  | "search"
  | "sliders"
  | "message"
  | "log-out"
  | "heart"
  | "star"
  | "repeat"
  | "alert-circle"
  | "info"
  | "check-circle"
  | "menu"
  | "mail"
  | "eye"
  | "eye-off"
  | "image"
  | "trash"
  | "loader"
  | "calendar-plus"
  | "users-round"
  | "leaf";

const paths: Record<IconName, React.ReactNode> = {
  compass: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m15.4 8.6-2.1 4.7-4.7 2.1 2.1-4.7 4.7-2.1Z" />
    </>
  ),
  users: (
    <>
      <path d="M16 19v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 17.5V19" />
      <circle cx="10" cy="8" r="3.2" />
      <path d="M20 19v-1.4a3.4 3.4 0 0 0-2.6-3.3" />
      <path d="M15.5 4.3a3.2 3.2 0 0 1 0 6.2" />
    </>
  ),
  "users-round": (
    <>
      <path d="M18 21a6 6 0 0 0-12 0" />
      <circle cx="12" cy="8" r="4" />
      <path d="M22 21a4 4 0 0 0-2.5-3.7" />
      <path d="M2 21a4 4 0 0 1 2.5-3.7" />
    </>
  ),
  sparkles: (
    <>
      <path d="m12 3 1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5L12 3Z" />
      <path d="M19 15.5 19.8 18l2.2.8-2.2.8-.8 2.4-.8-2.4-2.2-.8 2.2-.8.8-2.5Z" />
      <path d="M5 16.5 5.6 18l1.4.5-1.4.5L5 20.5l-.6-1.5L3 18.5l1.4-.5L5 16.5Z" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M5 21a7 7 0 0 1 14 0" />
    </>
  ),
  bell: (
    <>
      <path d="M18 8a6 6 0 1 0-12 0c0 6-2.5 7-2.5 7h17S18 14 18 8Z" />
      <path d="M10.3 20a2 2 0 0 0 3.4 0" />
    </>
  ),
  calendar: (
    <>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M8 3v4M16 3v4M3 10h18" />
    </>
  ),
  "calendar-plus": (
    <>
      <rect x="3" y="5" width="18" height="16" rx="2.5" />
      <path d="M8 3v4M16 3v4M3 10h18M12 14v4M10 16h4" />
    </>
  ),
  "map-pin": (
    <>
      <path d="M20 10c0 5.5-8 11-8 11S4 15.5 4 10a8 8 0 1 1 16 0Z" />
      <circle cx="12" cy="10" r="2.6" />
    </>
  ),
  "arrow-right": <path d="M4 12h16m-6-6 6 6-6 6" />,
  "arrow-left": <path d="M20 12H4m6 6-6-6 6-6" />,
  "chevron-right": <path d="m9 5 7 7-7 7" />,
  "chevron-down": <path d="m6 9 6 6 6-6" />,
  plus: <path d="M12 5v14M5 12h14" />,
  send: (
    <>
      <path d="m22 2-7 20-4-9-9-4 20-7Z" />
      <path d="M22 2 11 13" />
    </>
  ),
  check: <path d="m4 12.5 5 5L20 6.5" />,
  x: <path d="M6 6l12 12M18 6 6 18" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3.2 2" />
    </>
  ),
  lock: (
    <>
      <rect x="4" y="10" width="16" height="11" rx="2.5" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </>
  ),
  globe: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a14 14 0 0 1 0 18 14 14 0 0 1 0-18Z" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.6-3.6" />
    </>
  ),
  sliders: (
    <>
      <path d="M4 7h10M18 7h2M4 17h2M10 17h10" />
      <circle cx="16" cy="7" r="2" />
      <circle cx="8" cy="17" r="2" />
    </>
  ),
  message: (
    <>
      <path d="M20 15a2.5 2.5 0 0 1-2.5 2.5H8L3 21V6.5A2.5 2.5 0 0 1 5.5 4h12A2.5 2.5 0 0 1 20 6.5V15Z" />
      <path d="M8 9h8M8 13h5" />
    </>
  ),
  "log-out": (
    <>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <path d="m16 17 5-5-5-5M21 12H9" />
    </>
  ),
  heart: <path d="M20.8 5.6a5.4 5.4 0 0 0-7.7 0L12 6.7l-1.1-1.1a5.4 5.4 0 0 0-7.7 7.7L12 22l8.8-8.7a5.4 5.4 0 0 0 0-7.7Z" />,
  star: <path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1 6.2-5.5-2.9L6.5 20l1-6.2L3 9.6l6.2-.9L12 3Z" />,
  repeat: (
    <>
      <path d="m17 2 4 4-4 4" />
      <path d="M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4" />
      <path d="M21 13v2a3 3 0 0 1-3 3H3" />
    </>
  ),
  "alert-circle": (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5M12 16.5v.5" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 7.5v.5" />
    </>
  ),
  "check-circle": (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8 12.5 2.7 2.7L16 9.5" />
    </>
  ),
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  mail: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <path d="m4 7 8 6 8-6" />
    </>
  ),
  eye: (
    <>
      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
      <circle cx="12" cy="12" r="3" />
    </>
  ),
  "eye-off": (
    <>
      <path d="M3 3l18 18" />
      <path d="M10.6 6.1A9.8 9.8 0 0 1 12 6c6 0 9.5 6 9.5 6a17 17 0 0 1-2.2 3M6.6 6.7A17 17 0 0 0 2.5 12S6 18 12 18c1.4 0 2.7-.3 3.8-.8" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </>
  ),
  image: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2.5" />
      <circle cx="8.5" cy="9.5" r="1.5" />
      <path d="m4 17 5-5 4 4 2.5-2.5L20 17" />
    </>
  ),
  trash: (
    <>
      <path d="M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13" />
      <path d="M10 11v5M14 11v5" />
    </>
  ),
  loader: <path d="M12 3v4M12 17v4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M3 12h4M17 12h4M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8" />,
  leaf: (
    <>
      <path d="M20 4C12 4 5 8 5 15a5 5 0 0 0 5 5c7 0 10-8 10-16Z" />
      <path d="M4 21c3-6 7-9 12-11" />
    </>
  ),
};

interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
  size?: number;
}

export function Icon({ name, size = 20, className, strokeWidth = 1.8, ...rest }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={className}
      {...rest}
    >
      {paths[name]}
    </svg>
  );
}
