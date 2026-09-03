"use client";

import { useEffect, useState } from "react";

const cache = new Map<string, number | null>();
const inflight = new Map<string, Promise<number | null | undefined>>();
const pending = new Map<string, (value: number | null | undefined) => void>();
let batchTimer: ReturnType<typeof setTimeout> | null = null;
const BATCH_SIZE = 6;

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function requestChunk(chunk: [string, (value: number | null | undefined) => void][], attempt = 0): Promise<void> {
  try {
    const ids = chunk.map(([id]) => id);
    const response = await fetch(`/api/prices?ids=${encodeURIComponent(ids.join(","))}`, { cache: "no-store" });
    if (!response.ok) throw new Error(`prices_${response.status}`);
    const payload = await response.json();
    const failed = new Set<string>(Array.isArray(payload?.failedIds) ? payload.failedIds : []);
    if (failed.size > 0 && attempt === 0) {
      await wait(6_000);
      return requestChunk(chunk, 1);
    }
    for (const [id, resolve] of chunk) {
      if (failed.has(id)) {
        resolve(undefined);
        continue;
      }
      const min = payload?.prices?.[id]?.min;
      resolve(typeof min === "number" ? min : null);
    }
  } catch {
    if (attempt === 0) {
      await wait(6_000);
      return requestChunk(chunk, 1);
    }
    chunk.forEach(([, resolve]) => resolve(undefined));
  }
}

/** Объединяет запросы цен карточек, смонтированных в одном кадре, в один HTTP. */
async function flushBatch() {
  const entries = [...pending.entries()];
  pending.clear();
  batchTimer = null;

  for (let offset = 0; offset < entries.length; offset += BATCH_SIZE) {
    const chunk = entries.slice(offset, offset + BATCH_SIZE);
    await requestChunk(chunk);
  }
}

function fetchMin(id: string): Promise<number | null | undefined> {
  return new Promise((resolve) => {
    pending.set(id, resolve);
    if (!batchTimer) batchTimer = setTimeout(() => void flushBatch(), 35);
  });
}

/** undefined — загружается, null — цены нет, number — минимальная цена. */
export function usePrice(id: string, enabled: boolean): number | null | undefined {
  const [min, setMin] = useState<number | null | undefined>(() => (cache.has(id) ? cache.get(id)! : undefined));

  useEffect(() => {
    if (!enabled || cache.has(id)) return;
    let alive = true;
    const request = inflight.get(id) ?? (() => {
      const created = fetchMin(id).then((value) => {
        if (value !== undefined) cache.set(id, value);
        inflight.delete(id);
        return value;
      });
      inflight.set(id, created);
      return created;
    })();
    request.then((value) => { if (alive) setMin(value); });
    return () => { alive = false; };
  }, [id, enabled]);

  return min;
}
