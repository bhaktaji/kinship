import type { Metadata, Viewport } from "next";
import "./globals.css";
import { AppHeader } from "@/components/AppHeader";
import { BottomNav } from "@/components/BottomNav";
import { OfflineBanner } from "@/components/OfflineBanner";
import { ServiceWorkerRegister } from "@/components/ServiceWorkerRegister";
import { display, sans } from "@/lib/fonts";
import { Providers } from "./providers";

export const metadata: Metadata = {
  title: "Kinship — find your people",
  description: "Small recurring activity groups in your city.",
  applicationName: "Kinship",
  manifest: "/manifest.webmanifest",
  icons: {
    icon: [
      { url: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
      { url: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
    ],
    apple: [{ url: "/icons/icon-192.png", sizes: "192x192", type: "image/png" }],
  },
  appleWebApp: { capable: true, statusBarStyle: "default", title: "Kinship" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#f7f4ee",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${display.variable}`}>
      <body>
        <a href="#main-content" className="skip-link">
          Skip to content
        </a>
        <Providers>
          <OfflineBanner />
          <ServiceWorkerRegister />
          <AppHeader />
          <main
            id="main-content"
            tabIndex={-1}
            className="shell pb-28 pt-5 outline-none sm:pt-8 lg:pb-16"
          >
            {children}
          </main>
          <BottomNav />
        </Providers>
      </body>
    </html>
  );
}
