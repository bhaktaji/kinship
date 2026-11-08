"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth-context";
import { apiFetch } from "@/lib/api";
import type { NotificationPage } from "@/lib/types";
import { Avatar } from "./Avatar";
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

export function AppHeader() {
  const { token, user, loading, logout } = useAuth();
  const pathname = usePathname();
  const unread = useQuery({
    queryKey: ["notification-unread", token],
    queryFn: () => apiFetch<NotificationPage>("/v1/notifications?limit=1", {}, token),
    enabled: Boolean(token),
    refetchInterval: 30000,
  });
  const unreadCount = unread.data?.unread_count ?? 0;

  return (
    <header className="sticky top-0 z-50 border-b border-line/80 bg-canvas/85 backdrop-blur-md">
      <div className="shell flex h-16 items-center justify-between gap-3">
        <Link href="/" className="flex shrink-0 items-center gap-2.5" aria-label="Kinship home">
          <span className="flex h-9 w-9 items-center justify-center rounded-sm bg-brand font-display text-lg font-semibold text-white shadow-sm">
            K
          </span>
          <span className="font-display text-[1.35rem] font-semibold tracking-tight text-ink">Kinship</span>
        </Link>

        <nav className="hidden items-center gap-0.5 md:flex" aria-label="Main">
          {links.map((link) => {
            const active = isActive(pathname, link.href);
            return (
              <Link
                key={link.href}
                href={link.href}
                aria-current={active ? "page" : undefined}
                data-active={active}
                className="nav-link"
              >
                <Icon name={link.icon} size={16} />
                {link.label}
              </Link>
            );
          })}
        </nav>

        <div className="flex items-center gap-1 sm:gap-2">
          {loading || user ? (
            <Link
              href="/notifications"
              className="relative inline-flex h-10 w-10 items-center justify-center rounded-full text-muted transition hover:bg-ink/[0.055] hover:text-ink"
              aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : "Notifications"}
              aria-current={isActive(pathname, "/notifications") ? "page" : undefined}
            >
              <Icon name="bell" size={20} />
              {unreadCount > 0 ? (
                <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-brand px-1 text-[0.5625rem] font-bold leading-none text-white">
                  {unreadCount > 9 ? "9+" : unreadCount}
                </span>
              ) : null}
            </Link>
          ) : null}

          {loading ? (
            <span className="skeleton h-9 w-9 rounded-full" />
          ) : user ? (
            <>
              <Link
                href="/profile"
                className="hidden items-center gap-2 rounded-full py-1 pl-1 pr-2.5 transition hover:bg-ink/[0.05] sm:flex"
                aria-label={`${user.display_name}, open profile`}
              >
                <Avatar name={user.display_name} size="sm" />
                <span className="hidden max-w-28 truncate text-small font-medium text-ink lg:inline">
                  {user.display_name}
                </span>
              </Link>
              <button
                type="button"
                onClick={() => void logout()}
                aria-label="Sign out"
                className="inline-flex min-h-9 items-center gap-1.5 rounded-full px-2.5 text-small font-medium text-muted transition hover:bg-ink/[0.055] hover:text-ink"
              >
                <Icon name="log-out" size={16} />
                <span className="hidden sm:inline">Sign out</span>
              </button>
            </>
          ) : (
            <>
              <Link href="/login" className="btn-ghost btn-sm inline-flex">
                Sign in
              </Link>
              <Link href="/register" className="btn-primary btn-sm">
                <span className="sm:hidden">Join</span>
                <span className="hidden sm:inline">Join Kinship</span>
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
