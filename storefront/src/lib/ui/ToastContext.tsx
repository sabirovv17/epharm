"use client";

import { createContext, useContext, useState, useCallback, type ReactNode } from "react";

interface Toast {
  id: number;
  message: string;
  actionLabel?: string;
  onAction?: () => void;
  tone?: "success" | "removed";
}

interface ToastOptions {
  actionLabel?: string;
  onAction?: () => void;
  duration?: number;
  tone?: "success" | "removed";
}

interface ToastContextValue {
  toasts: Toast[];
  push: (message: string, options?: ToastOptions) => void;
  dismiss: (id: number) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);
let counter = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback((message: string, options: ToastOptions = {}) => {
    counter += 1;
    const id = counter;
    setToasts((current) => [...current, {
      id,
      message,
      actionLabel: options.actionLabel,
      onAction: options.onAction,
      tone: options.tone,
    }]);
    window.setTimeout(() => dismiss(id), options.duration ?? 2_600);
  }, [dismiss]);

  return <ToastContext.Provider value={{ toasts, push, dismiss }}>{children}</ToastContext.Provider>;
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within <ToastProvider>");
  return ctx;
}
