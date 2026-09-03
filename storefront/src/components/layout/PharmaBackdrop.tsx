import type { CSSProperties } from "react";
import { cn } from "@/lib/cn";

type Variant = "fill" | "outline";

interface CrossDef {
  top?: string;
  left?: string;
  right?: string;
  size: number;
  color: string;
  op: number;
  v: Variant;
  dur: number;
  delay: number;
  anim: "a" | "b";
}

// Плотнее и крупнее слева, реже и светлее справа — как в референсе.
const crosses: CrossDef[] = [
  { top: "3%",  left: "1%",  size: 132, color: "text-brand-600", op: 0.13, v: "fill",    dur: 8,  delay: 0,   anim: "a" },
  { top: "13%", left: "9%",  size: 64,  color: "text-brand-500", op: 0.24, v: "outline", dur: 6.5, delay: 0.7, anim: "b" },
  { top: "33%", left: "3%",  size: 150, color: "text-brand-500", op: 0.12, v: "fill",    dur: 10, delay: 0.4, anim: "b" },
  { top: "57%", left: "1%",  size: 78,  color: "text-brand-400", op: 0.22, v: "outline", dur: 7.5, delay: 1.2, anim: "a" },
  { top: "76%", left: "11%", size: 172, color: "text-brand-600", op: 0.10, v: "fill",    dur: 11, delay: 0.5, anim: "b" },
  { top: "23%", left: "21%", size: 56,  color: "text-brand-500", op: 0.18, v: "fill",    dur: 6,  delay: 0.2, anim: "a" },
  { top: "47%", left: "17%", size: 52,  color: "text-brand-400", op: 0.20, v: "outline", dur: 9,  delay: 1.1, anim: "b" },
  { top: "88%", left: "26%", size: 60,  color: "text-brand-400", op: 0.16, v: "outline", dur: 8,  delay: 1.6, anim: "a" },
  { top: "7%",  right: "9%", size: 92,  color: "text-brand-400", op: 0.16, v: "fill",    dur: 9,  delay: 0.6, anim: "a" },
  { top: "30%", right: "4%", size: 120, color: "text-brand-300", op: 0.13, v: "fill",    dur: 10.5, delay: 1.3, anim: "b" },
  { top: "60%", right: "9%", size: 68,  color: "text-brand-400", op: 0.16, v: "outline", dur: 7,  delay: 0.3, anim: "a" },
  { top: "85%", right: "4%", size: 104, color: "text-brand-300", op: 0.11, v: "fill",    dur: 12, delay: 0.9, anim: "b" },
  { top: "50%", left: "47%", size: 44,  color: "text-brand-300", op: 0.10, v: "outline", dur: 11, delay: 1.5, anim: "a" },
];

export function PharmaBackdrop() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(115% 85% at 0% 100%, #ecfdf3 0%, rgba(236,253,243,0) 55%)," +
            "radial-gradient(85% 70% at 100% 0%, #f0fbf4 0%, rgba(255,255,255,0) 48%)",
        }}
      />
      {crosses.map((c, i) => {
        const style: CSSProperties = {
          top: c.top,
          left: c.left,
          right: c.right,
          width: c.size,
          height: c.size,
          opacity: c.op,
          animationName: c.anim === "a" ? "pharma-float-a" : "pharma-float-b",
          animationDuration: `${c.dur}s`,
          animationTimingFunction: "ease-in-out",
          animationIterationCount: "infinite",
          animationDelay: `${c.delay}s`,
        };
        return <Cross key={i} variant={c.v} className={cn("pharma-cross absolute", c.color)} style={style} />;
      })}
    </div>
  );
}

function Cross({ variant, className, style }: { variant: Variant; className?: string; style?: CSSProperties }) {
  const d = "M35 0 H65 V35 H100 V65 H65 V100 H35 V65 H0 V35 H35 Z";
  return (
    <svg viewBox="0 0 100 100" className={className} style={style}>
      {variant === "outline" ? (
        <path d={d} fill="none" stroke="currentColor" strokeWidth={7} strokeLinejoin="round" />
      ) : (
        <path d={d} fill="currentColor" />
      )}
    </svg>
  );
}
