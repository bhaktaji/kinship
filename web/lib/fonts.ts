import localFont from "next/font/local";

export const sans = localFont({
  src: "../public/fonts/inter-latin.woff2",
  variable: "--font-sans",
  display: "swap",
  weight: "400 700",
  fallback: ["system-ui", "Segoe UI", "sans-serif"],
});

export const display = localFont({
  src: "../public/fonts/fraunces-latin.woff2",
  variable: "--font-display",
  display: "swap",
  weight: "400 700",
  fallback: ["Georgia", "Times New Roman", "serif"],
});
