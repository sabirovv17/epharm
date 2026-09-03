import { NextResponse } from "next/server";
import { adminAuthorized, publicPushConfig } from "@/lib/push/send";
import { adminPushSnapshot } from "@/lib/push/store";
import { nativePushStats } from "@/lib/push/native-store";
import { apnsConfigured } from "@/lib/push/apns";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const NO_STORE = { "cache-control": "no-store" };

export async function GET(req: Request) {
  if (!adminAuthorized(req)) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401, headers: NO_STORE });
  }
  const requested = Number(new URL(req.url).searchParams.get("limit") || 100);
  const limit = Number.isFinite(requested) ? Math.max(1, Math.min(Math.trunc(requested), 200)) : 100;
  try {
    const [snapshot, native] = await Promise.all([
      adminPushSnapshot(limit),
      nativePushStats(),
    ]);
    const { subscriptions, deliveries } = snapshot.stats;
    const history = snapshot.history.map((entry) => ({
      ...entry,
      title: entry.payload.title,
      body: entry.payload.body,
      url: entry.payload.url,
      audience: entry.audience ?? (entry.kind === "order-status" || entry.event === "order.created" ? "orders" : "all"),
      status: entry.delivery.failed > 0
        ? (entry.delivery.sent > 0 ? "partial" : "failed")
        : "sent",
      sent: entry.delivery.sent,
      failed: entry.delivery.failed,
      targets: entry.delivery.targets,
      revoked: entry.delivery.revoked,
    }));
    return NextResponse.json(
      {
        ok: true,
        configured: publicPushConfig().enabled || apnsConfigured(),
        updatedAt: snapshot.updatedAt,
        stats: {
          active: subscriptions.active + native.active,
          promos: subscriptions.promos + native.promos,
          orders: subscriptions.orders + native.orders,
          sent: deliveries.sent,
          failed: deliveries.failed,
          revoked: subscriptions.revoked,
          total: subscriptions.total + native.total,
          orderBindings: snapshot.stats.orderBindings,
          history: snapshot.stats.history,
          subscriptions,
          channels: {
            web: subscriptions,
            ios: native,
          },
          deliveries,
        },
        history,
      },
      { headers: NO_STORE },
    );
  } catch (error) {
    console.error("[admin/notifications] failed to read push store", error);
    return NextResponse.json({ error: "storage_unavailable" }, { status: 503, headers: NO_STORE });
  }
}
