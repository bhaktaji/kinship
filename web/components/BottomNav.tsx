"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, type IconName } from "./Icon";

const links: { href: string; label: string; icon: IconName }[] = [
  { href: "/", label: "Discover", icon: "compass" },
  { href: "/groups", label: "Groups", icon: "users" },
  { href: "/invitations", label: "Matches", icon: "sparkles" },
  { href: "/profile", label: "Profile", icon: "user" },
];

function isActive(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(href + "/");
}

export function BottomNav() {
  const pathname = usePathname();

  return (
    <nav
      aria-label="Primary"
      className="fixed inset-x-0 bottom-0 z-50 border-t border-line bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur-md md:hidden"
    >
      <ul className="mx-auto flex h-16 max-w-md items-stretch justify-around px-2">
        {links.map((link) => {
          const active = isActive(pathname, link.href);
          return (
            <li key={link.href} className="flex-1">
              <Link
                href={link.href}
                aria-current={active ? "page" : undefined}
                className={`flex h-full flex-col items-center justify-center gap-1 text-[0.625rem] font-semibold tracking-wide transition ${
                  active ? "text-brand" : "text-muted hover:text-ink"
                }`}
              >
                <span
                  className={`relative flex h-7 w-10 items-center justify-center rounded-full transition ${
                    active ? "bg-brand-soft" : ""
                  }`}
                >
                  <Icon name={link.icon} size={20} strokeWidth={active ? 2.1 : 1.8} />
                  {active ? (
                    <span className="absolute -bottom-0.5 h-1 w-1 rounded-full bg-brand" aria-hidden="true" />
                  ) : null}
                </span>
                {link.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
