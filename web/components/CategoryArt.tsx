type Palette = {
  bg: string;
  ink: string;
  accent: string;
  wash: string;
};

const palettes: Record<string, Palette> = {
  music: { bg: "#F2E1D6", ink: "#8A3D22", accent: "#D9A184", wash: "#F8EEE7" },
  sport: { bg: "#DEE8D9", ink: "#2F4A3B", accent: "#9DBB9B", wash: "#EDF3E9" },
  outdoors: { bg: "#E7EAD5", ink: "#54652F", accent: "#B9C68D", wash: "#F1F3E4" },
  food: { bg: "#F5E7C8", ink: "#8A5F14", accent: "#E3BE6C", wash: "#FBF2DE" },
  books: { bg: "#E6E1EF", ink: "#51416E", accent: "#B9ABD4", wash: "#F1EDF7" },
  art: { bg: "#F1DEE5", ink: "#853A58", accent: "#DCA9BE", wash: "#F9EEF2" },
  default: { bg: "#E9E3D8", ink: "#4A453C", accent: "#C9BFAE", wash: "#F4F0E8" },
};

export function categoryPalette(category?: string): Palette {
  return palettes[(category ?? "").toLowerCase()] ?? palettes.default;
}

/**
 * Category artwork built from simple geometry rather than stock photography.
 * The API's image_url is optional and frequently empty, so this keeps every
 * card visually distinct and on-brand without external requests.
 */
export function CategoryArt({
  category,
  className = "",
  detail = false,
}: {
  category?: string;
  className?: string;
  detail?: boolean;
}) {
  const key = (category ?? "").toLowerCase();
  const palette = categoryPalette(key);

  return (
    <div
      aria-hidden="true"
      className={`relative overflow-hidden ${className}`}
      style={{ backgroundColor: palette.bg }}
    >
      <svg
        viewBox="0 0 400 200"
        preserveAspectRatio="xMidYMid slice"
        className="absolute inset-0 h-full w-full"
      >
        <rect width="400" height="200" fill={palette.bg} />
        <circle cx="330" cy="34" r="96" fill={palette.wash} opacity="0.85" />
        <circle cx="62" cy="178" r="84" fill={palette.accent} opacity="0.32" />

        {key === "music" ? (
          <g>
            <circle cx="286" cy="106" r="58" fill="none" stroke={palette.ink} strokeWidth="1.6" opacity="0.55" />
            <circle cx="286" cy="106" r="44" fill="none" stroke={palette.ink} strokeWidth="1.6" opacity="0.5" />
            <circle cx="286" cy="106" r="30" fill="none" stroke={palette.ink} strokeWidth="1.6" opacity="0.45" />
            <circle cx="286" cy="106" r="8" fill={palette.ink} opacity="0.75" />
            <path d="M92 138c18-34 42-50 72-48" stroke={palette.ink} strokeWidth="2" fill="none" opacity="0.55" />
            <circle cx="92" cy="138" r="4" fill={palette.ink} opacity="0.7" />
            <path d="M164 90v-30l30-7v30" stroke={palette.ink} strokeWidth="2.4" fill="none" opacity="0.7" />
            <circle cx="158" cy="94" r="7" fill={palette.ink} opacity="0.7" />
            <circle cx="188" cy="87" r="7" fill={palette.ink} opacity="0.7" />
          </g>
        ) : null}

        {key === "sport" ? (
          <g>
            <path d="M-20 150 150 20M30 190 210 36M110 205 300 56" stroke={palette.ink} strokeWidth="1.4" opacity="0.22" />
            <circle cx="110" cy="82" r="20" fill="none" stroke={palette.ink} strokeWidth="2" opacity="0.6" />
            <circle cx="168" cy="126" r="11" fill={palette.ink} opacity="0.28" />
            <circle cx="222" cy="72" r="7" fill={palette.ink} opacity="0.42" />
            <path d="M246 150c0-30 22-52 52-52" stroke={palette.ink} strokeWidth="2" fill="none" opacity="0.6" />
            <path d="M258 160c0-22 16-38 38-38" stroke={palette.ink} strokeWidth="2" fill="none" opacity="0.45" />
          </g>
        ) : null}

        {key === "outdoors" ? (
          <g>
            <circle cx="314" cy="52" r="26" fill={palette.accent} opacity="0.75" />
            <path d="M-30 178 96 74l76 68 58-44 100 80v40H-30Z" fill={palette.ink} opacity="0.18" />
            <path d="M-30 196 84 112l62 48 52-34 132 70v24H-30Z" fill={palette.ink} opacity="0.32" />
            <path d="M96 74v-24M96 74l-14-14M96 74l14-14" stroke={palette.ink} strokeWidth="2" opacity="0.55" />
          </g>
        ) : null}

        {key === "food" ? (
          <g>
            <circle cx="286" cy="106" r="56" fill={palette.wash} stroke={palette.ink} strokeWidth="2" opacity="0.75" />
            <circle cx="286" cy="106" r="38" fill="none" stroke={palette.ink} strokeWidth="1.6" opacity="0.45" />
            <path d="M78 64v84M64 64v28a14 14 0 0 0 28 0V64M78 148v-20" stroke={palette.ink} strokeWidth="2.4" opacity="0.65" />
            <path d="M160 64c16 0 26 12 26 28s-10 24-26 24v32" stroke={palette.ink} strokeWidth="2.4" fill="none" opacity="0.55" />
          </g>
        ) : null}

        {key === "books" ? (
          <g>
            <rect x="70" y="118" width="120" height="18" rx="3" fill={palette.ink} opacity="0.35" />
            <rect x="84" y="94" width="120" height="18" rx="3" fill={palette.ink} opacity="0.25" />
            <rect x="64" y="70" width="120" height="18" rx="3" fill={palette.ink} opacity="0.45" />
            <path d="M250 62h70v92h-70z" fill={palette.wash} stroke={palette.ink} strokeWidth="2" opacity="0.7" />
            <path d="M268 82h34M268 98h34M268 114h20" stroke={palette.ink} strokeWidth="2" opacity="0.45" />
          </g>
        ) : null}

        {key === "art" ? (
          <g>
            <rect x="216" y="54" width="84" height="84" rx="6" fill="none" stroke={palette.ink} strokeWidth="2" opacity="0.6" />
            <circle cx="258" cy="96" r="22" fill={palette.accent} opacity="0.65" />
            <path d="M74 146c20-58 44-84 76-78 20 4 22 28 40 32" stroke={palette.ink} strokeWidth="2.2" fill="none" opacity="0.55" />
            <circle cx="74" cy="146" r="7" fill={palette.ink} opacity="0.6" />
            <path d="M148 40l8 18 18 8-18 8-8 18-8-18-18-8 18-8 8-18Z" fill={palette.ink} opacity="0.3" />
          </g>
        ) : null}

        {!["music", "sport", "outdoors", "food", "books", "art"].includes(key) ? (
          <g>
            <circle cx="250" cy="92" r="48" fill="none" stroke={palette.ink} strokeWidth="2" opacity="0.4" />
            <circle cx="286" cy="118" r="48" fill="none" stroke={palette.ink} strokeWidth="2" opacity="0.3" />
            <path d="M84 148c18-34 46-52 84-54" stroke={palette.ink} strokeWidth="2" fill="none" opacity="0.5" />
          </g>
        ) : null}

        {detail ? (
          <g opacity="0.35">
            <circle cx="46" cy="42" r="2" fill={palette.ink} />
            <circle cx="58" cy="42" r="2" fill={palette.ink} />
            <circle cx="70" cy="42" r="2" fill={palette.ink} />
          </g>
        ) : null}
      </svg>
    </div>
  );
}
