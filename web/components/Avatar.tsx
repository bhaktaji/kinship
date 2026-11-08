const tones = [
  "bg-[#EADFD3] text-[#6B4126]",
  "bg-[#DFE7D8] text-[#33502F]",
  "bg-[#E6E1EF] text-[#54446F]",
  "bg-[#F2E2D9] text-[#7C3F2C]",
  "bg-[#E2E7EC] text-[#344E66]",
  "bg-[#F0E6C9] text-[#6E5415]",
];

const sizes = {
  xs: "h-7 w-7 text-[0.625rem]",
  sm: "h-9 w-9 text-xs",
  md: "h-11 w-11 text-sm",
  lg: "h-14 w-14 text-base",
  xl: "h-24 w-24 text-2xl",
};

function hash(value: string): number {
  let result = 0;
  for (let index = 0; index < value.length; index += 1) {
    result = (result * 31 + value.charCodeAt(index)) % 997;
  }
  return result;
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return "?";
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export function Avatar({
  name,
  size = "md",
  className = "",
  ring = false,
}: {
  name: string;
  size?: keyof typeof sizes;
  className?: string;
  ring?: boolean;
}) {
  const tone = tones[hash(name) % tones.length];
  return (
    <span
      aria-hidden="true"
      className={`inline-flex shrink-0 items-center justify-center rounded-full font-semibold tracking-tight ${tone} ${
        sizes[size]
      } ${ring ? "ring-2 ring-surface" : ""} ${className}`}
    >
      {initials(name)}
    </span>
  );
}
