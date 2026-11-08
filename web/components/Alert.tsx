import type { ReactNode } from "react";
import { Icon, type IconName } from "./Icon";

type Tone = "info" | "success" | "warning" | "danger" | "neutral";

const toneStyles: Record<Tone, { box: string; icon: IconName }> = {
  info: { box: "border-info/20 bg-info/[0.06] text-info", icon: "info" },
  success: { box: "border-success/20 bg-success/[0.07] text-success", icon: "check-circle" },
  warning: { box: "border-warning/25 bg-warning/[0.08] text-warning", icon: "alert-circle" },
  danger: { box: "border-danger/20 bg-danger/[0.06] text-danger", icon: "alert-circle" },
  neutral: { box: "border-line bg-surface text-muted", icon: "info" },
};

export function Alert({
  tone = "neutral",
  children,
  className = "",
  live = true,
}: {
  tone?: Tone;
  children: ReactNode;
  className?: string;
  live?: boolean;
}) {
  const style = toneStyles[tone];
  const assertive = tone === "danger";
  return (
    <div
      role={assertive ? "alert" : "status"}
      aria-live={live ? (assertive ? "assertive" : "polite") : undefined}
      className={`flex items-start gap-2.5 rounded-md border px-3.5 py-3 text-small font-medium ${style.box} ${className}`}
    >
      <Icon name={style.icon} size={17} className="mt-0.5 shrink-0" />
      <div className="min-w-0 leading-relaxed">{children}</div>
    </div>
  );
}
