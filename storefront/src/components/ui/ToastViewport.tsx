"use client";

import { Check, Trash2 } from "lucide-react";
import { useToast } from "@/lib/ui/ToastContext";

export function ToastViewport() {
  const { toasts, dismiss } = useToast();
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-6 z-[90] flex flex-col items-center gap-2 px-4">
      {toasts.map((t) => (
        <div
          key={t.id}
          role="status"
          className="pointer-events-auto flex w-full max-w-md items-center gap-3 rounded-2xl bg-slate-900 py-3 pl-3 pr-3 text-sm font-semibold text-white shadow-pop"
          style={{ animation: "toast-in 0.25s cubic-bezier(.22,1,.36,1)" }}
        >
          <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full ${t.tone === "removed" ? "bg-accent-500" : "bg-brand-500"}`}>
            {t.tone === "removed"
              ? <Trash2 className="h-3.5 w-3.5 text-white" />
              : <Check className="h-3.5 w-3.5 text-white" />}
          </span>
          <span className="min-w-0 flex-1">{t.message}</span>
          {t.actionLabel && t.onAction && (
            <button
              type="button"
              onClick={() => {
                t.onAction?.();
                dismiss(t.id);
              }}
              className="h-9 shrink-0 rounded-lg bg-white/10 px-3 text-sm font-bold text-brand-200 transition hover:bg-white/20 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-400"
            >
              {t.actionLabel}
            </button>
          )}
        </div>
      ))}
    </div>
  );
}
