import {
  Sparkles, Droplets, Baby, Pill, HeartPulse, ShowerHead, Sun, Brush, Stethoscope,
  type LucideIcon,
} from "lucide-react";

const registry: Record<string, LucideIcon> = {
  Sparkles, Droplets, Baby, Pill, HeartPulse, ShowerHead, Sun, Brush, Stethoscope,
};

export function CategoryIcon({ name, className }: { name: string; className?: string }) {
  const Icon = registry[name] ?? Sparkles;
  return <Icon className={className} />;
}
