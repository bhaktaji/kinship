import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        canvas: "rgb(var(--canvas) / <alpha-value>)",
        surface: "rgb(var(--surface) / <alpha-value>)",
        elevated: "rgb(var(--elevated) / <alpha-value>)",
        ink: "rgb(var(--ink) / <alpha-value>)",
        muted: "rgb(var(--muted) / <alpha-value>)",
        faint: "rgb(var(--faint) / <alpha-value>)",
        line: "rgb(var(--line) / <alpha-value>)",
        brand: "rgb(var(--brand) / <alpha-value>)",
        "brand-soft": "rgb(var(--brand-soft) / <alpha-value>)",
        forest: "rgb(var(--forest) / <alpha-value>)",
        sage: "rgb(var(--sage) / <alpha-value>)",
        gold: "rgb(var(--gold) / <alpha-value>)",
        danger: "rgb(var(--danger) / <alpha-value>)",
        success: "rgb(var(--success) / <alpha-value>)",
        warning: "rgb(var(--warning) / <alpha-value>)",
        info: "rgb(var(--info) / <alpha-value>)",
      },
      fontFamily: {
        sans: ["var(--font-sans)", "system-ui", "sans-serif"],
        display: ["var(--font-display)", "Georgia", "serif"],
      },
      fontSize: {
        display: ["clamp(2.25rem, 4.4vw, 3.4rem)", { lineHeight: "1.04", letterSpacing: "-0.025em" }],
        h1: ["2rem", { lineHeight: "1.12", letterSpacing: "-0.02em" }],
        h2: ["1.375rem", { lineHeight: "1.25", letterSpacing: "-0.015em" }],
        h3: ["1.0625rem", { lineHeight: "1.4", letterSpacing: "-0.005em" }],
        body: ["0.9375rem", { lineHeight: "1.6" }],
        small: ["0.8125rem", { lineHeight: "1.5" }],
        micro: ["0.6875rem", { lineHeight: "1.4", letterSpacing: "0.04em" }],
      },
      borderRadius: {
        xs: "0.375rem",
        sm: "0.625rem",
        md: "0.75rem",
        lg: "1.125rem",
        xl: "1.5rem",
        "2xl": "1.75rem",
      },
      boxShadow: {
        card: "0 1px 2px rgb(var(--shadow) / 0.05), 0 12px 32px -22px rgb(var(--shadow) / 0.22)",
        lift: "0 2px 4px rgb(var(--shadow) / 0.05), 0 22px 46px -26px rgb(var(--shadow) / 0.3)",
        pop: "0 24px 70px -28px rgb(var(--shadow) / 0.45)",
      },
      maxWidth: {
        shell: "72rem",
        prose: "42rem",
      },
      transitionTimingFunction: {
        "out-soft": "cubic-bezier(0.22, 1, 0.36, 1)",
      },
      keyframes: {
        "fade-up": {
          from: { opacity: "0", transform: "translateY(6px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        "sheet-in": {
          from: { opacity: "0", transform: "translateY(16px) scale(0.99)" },
          to: { opacity: "1", transform: "translateY(0) scale(1)" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.35s cubic-bezier(0.22, 1, 0.36, 1) both",
        "sheet-in": "sheet-in 0.28s cubic-bezier(0.22, 1, 0.36, 1) both",
      },
    },
  },
  plugins: [],
};

export default config;
