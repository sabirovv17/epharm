import Link from "next/link";
import { cn } from "@/lib/cn";

export function Logo({ light = false, className }: { light?: boolean; className?: string }) {
  const green = light ? "#37c653" : "#1fa23a";
  const shSm = light
    ? "1px 1px 0 #0a4d18"
    : "1px 1px 0 #0b5f1f, 2px 2px 0 #0a531b";
  const shBig = light
    ? "2px 2px 0 #0a4d18, 3px 3px 0 #073d13"
    : "1px 1px 0 #0b5f1f, 2px 2px 0 #0b5f1f, 3px 3px 0 #094f19, 4px 5px 5px rgba(0,0,0,0.18)";

  return (
    <Link
      href="/"
      aria-label="Аптека со склада — на главную"
      className={cn("inline-flex min-w-0 max-w-[232px] items-center leading-none sm:max-w-none", className)}
      style={{ fontFamily: "var(--font-manrope), 'Arial Black', sans-serif" }}
    >
      <span className="flex min-w-0 items-center gap-1.5">
        <span className="flex shrink-0 flex-col items-start gap-px">
          <span className="text-[9px] font-black leading-none tracking-[0.02em] sm:text-[13px]" style={{ color: green, textShadow: shSm }}>DARIHANA</span>
          <span className="text-[9px] font-black leading-none tracking-[0.30em] sm:text-[13px]" style={{ color: green, textShadow: shSm }}>АПТЕКА</span>
        </span>
        <span className="truncate text-[21px] font-black leading-none tracking-[0.01em] sm:text-[30px]" style={{ color: green, textShadow: shBig }}>СО&nbsp;СКЛАДА</span>
      </span>
    </Link>
  );
}
