"use client";

import Link from "next/link";
import { Truck, MapPin } from "lucide-react";
import { useLang } from "@/lib/i18n/LanguageContext";

export function TopBar() {
  const { t } = useLang();
  return (
    <div className="bg-brand-700 text-white">
      <div className="mx-auto flex h-9 max-w-7xl items-center justify-between px-4 text-xs sm:px-6">
        <p className="flex items-center gap-1.5 font-medium">
          <Truck className="h-3.5 w-3.5" />
          {t("top.delivery")}
        </p>
        <nav className="hidden items-center gap-5 text-brand-50/90 sm:flex">
          <Link href="/pharmacies" className="flex items-center gap-1 transition hover:text-white">
            <MapPin className="h-3.5 w-3.5" /> {t("top.map")}
          </Link>
          <Link href="/help" className="transition hover:text-white">{t("top.help")}</Link>
        </nav>
      </div>
    </div>
  );
}
