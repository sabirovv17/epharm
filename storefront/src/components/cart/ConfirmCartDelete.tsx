"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AlertTriangle, Trash2, X } from "lucide-react";

type ConfirmCartDeleteProps = {
  onConfirm: () => void;
  label: string;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel: string;
  itemName?: string;
  focusTargetId?: string;
  trigger?: "icon" | "text";
  iconSize?: "sm" | "md";
};

export function ConfirmCartDelete({
  onConfirm,
  label,
  title,
  description,
  confirmLabel,
  cancelLabel,
  itemName,
  focusTargetId,
  trigger = "icon",
  iconSize = "md",
}: ConfirmCartDeleteProps) {
  const [confirming, setConfirming] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const itemNameId = useId();
  const descriptionId = useId();

  const close = useCallback(() => {
    setConfirming(false);
    window.requestAnimationFrame(() => triggerRef.current?.focus());
  }, []);

  const confirm = () => {
    setConfirming(false);
    onConfirm();
    if (focusTargetId) {
      window.requestAnimationFrame(() => document.getElementById(focusTargetId)?.focus());
    }
  };

  useEffect(() => {
    if (!confirming) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    window.requestAnimationFrame(() => cancelRef.current?.focus());

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close();
        return;
      }
      if (event.key !== "Tab" || !dialogRef.current) return;
      const focusable = Array.from(
        dialogRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [close, confirming]);

  const triggerButton = trigger === "text" ? (
    <button
      ref={triggerRef}
      type="button"
      onClick={() => setConfirming(true)}
      aria-haspopup="dialog"
      aria-expanded={confirming}
      className="text-sm text-slate-500 transition hover:text-red-600"
    >
      {label}
    </button>
  ) : (
    <button
      ref={triggerRef}
      type="button"
      onClick={() => setConfirming(true)}
      aria-label={label}
      aria-haspopup="dialog"
      aria-expanded={confirming}
      className="grid h-11 w-11 place-items-center rounded-xl text-slate-400 transition hover:bg-accent-50 hover:text-accent-600"
    >
      <Trash2 className={iconSize === "sm" ? "h-4 w-4" : "h-5 w-5"} />
    </button>
  );

  return (
    <>
      {triggerButton}
      {confirming && typeof document !== "undefined" && createPortal(
        <div className="fixed inset-0 z-[120] flex items-end justify-center sm:items-center sm:p-4">
          <div
            aria-hidden="true"
            onClick={close}
            className="absolute inset-0 bg-slate-950/45 backdrop-blur-[2px]"
          />
          <div
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby={titleId}
            aria-describedby={itemName ? `${itemNameId} ${descriptionId}` : descriptionId}
            className="relative z-10 w-full max-w-md rounded-t-3xl bg-white px-5 pb-[max(1.25rem,env(safe-area-inset-bottom))] pt-5 shadow-pop sm:rounded-3xl sm:p-6"
          >
            <button
              type="button"
              onClick={close}
              aria-label={cancelLabel}
              className="absolute right-4 top-4 grid h-9 w-9 place-items-center rounded-xl text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
            >
              <X className="h-5 w-5" />
            </button>
            <span className="grid h-12 w-12 place-items-center rounded-2xl bg-accent-50 text-accent-600">
              <AlertTriangle className="h-6 w-6" />
            </span>
            <h2 id={titleId} className="mt-4 pr-10 font-display text-xl font-extrabold text-slate-900">
              {title}
            </h2>
            {itemName && (
              <p id={itemNameId} className="mt-2 line-clamp-2 text-sm font-semibold text-slate-700">
                {itemName}
              </p>
            )}
            <p id={descriptionId} className="mt-2 text-sm leading-6 text-slate-500">
              {description}
            </p>
            <div className="mt-6 grid gap-2 sm:grid-cols-2">
              <button
                ref={cancelRef}
                type="button"
                onClick={close}
                className="h-12 rounded-xl bg-brand-600 px-4 text-sm font-semibold text-white transition hover:bg-brand-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
              >
                {cancelLabel}
              </button>
              <button
                type="button"
                onClick={confirm}
                className="h-12 rounded-xl border border-accent-200 bg-accent-50 px-4 text-sm font-semibold text-accent-600 transition hover:border-accent-300 hover:bg-accent-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-400 focus-visible:ring-offset-2"
              >
                {confirmLabel}
              </button>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
