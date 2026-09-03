"use client";

import { useEffect } from "react";
import { emitProductViewed } from "@/lib/cdp/client";

export function ProductViewTracker({
  productId,
  variantId,
}: {
  productId: string;
  variantId?: string | null;
}) {
  useEffect(() => {
    emitProductViewed({ productId, variantId });
  }, [productId, variantId]);

  return null;
}
