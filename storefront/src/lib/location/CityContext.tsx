"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

/** Города присутствия сети. */
export const CITIES = [
  "Алматы",
  "Астана",
  "Шымкент",
  "Караганда",
  "Актобе",
  "Тараз",
  "Павлодар",
  "Усть-Каменогорск",
  "Семей",
  "Атырау",
  "Костанай",
  "Кызылорда",
  "Уральск",
  "Актау",
];

const KEY = "ass_city";
type Ctx = { city: string; setCity: (c: string) => void };
const CityCtx = createContext<Ctx | null>(null);

export function CityProvider({ children }: { children: ReactNode }) {
  const [city, setCityState] = useState("Алматы");

  useEffect(() => {
    try {
      const s = localStorage.getItem(KEY);
      // eslint-disable-next-line react-hooks/set-state-in-effect -- гидратация из localStorage после маунта (SSR == первый клиентский рендер)
      if (s) setCityState(s);
    } catch {
      /* ignore */
    }
  }, []);

  const setCity = (c: string) => {
    setCityState(c);
    try {
      localStorage.setItem(KEY, c);
    } catch {
      /* ignore */
    }
  };

  return <CityCtx.Provider value={{ city, setCity }}>{children}</CityCtx.Provider>;
}

export function useCity() {
  const c = useContext(CityCtx);
  if (!c) throw new Error("useCity must be used within <CityProvider>");
  return c;
}
