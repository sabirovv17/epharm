import { NextResponse } from "next/server";
import { customerSession } from "@/lib/customerSession";
import { clientIp, rateLimit } from "@/lib/rateLimit";
import { revokeNativeDevice, upsertNativeDevice } from "@/lib/push/native-store";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";
const NO_STORE = { "cache-control": "no-store" };
const IOS_TOKEN = /^[a-f0-9]{64}$/i;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

async function session(req: Request) {
  const value = await customerSession(req);
  if (value.status === "anonymous") {
    return { response: NextResponse.json({ error: "login_required" }, { status: 401, headers: NO_STORE }) };
  }
  if (value.status === "unavailable") {
    return { response: NextResponse.json({ error: "auth_unavailable" }, { status: 503, headers: NO_STORE }) };
  }
  return { customerId: value.customerId };
}

export async function POST(req: Request) {
  if (!rateLimit(`native-push-register:${clientIp(req)}`, 20, 60_000, Date.now())) {
    return NextResponse.json({ error: "rate_limited" }, { status: 429, headers: NO_STORE });
  }
  const auth = await session(req);
  if ("response" in auth) return auth.response;
  const body: unknown = await req.json().catch(() => null);
  if (!record(body)) return NextResponse.json({ error: "bad_json" }, { status: 400, headers: NO_STORE });
  const token = typeof body.token === "string" ? body.token.trim().toLowerCase() : "";
  const installationId = typeof body.installationId === "string" ? body.installationId.trim().toLowerCase() : "";
  if (body.platform !== "ios" || !IOS_TOKEN.test(token) || !UUID.test(installationId)) {
    return NextResponse.json({ error: "bad_device" }, { status: 400, headers: NO_STORE });
  }
  try {
    const device = await upsertNativeDevice({
      customerId: auth.customerId,
      installationId,
      platform: "ios",
      token,
      preferences: {
        orders: body.orders !== false,
        promos: body.promos === true,
      },
      locale: typeof body.locale === "string" ? body.locale.slice(0, 20) : undefined,
      city: typeof body.city === "string" ? body.city.slice(0, 80) : undefined,
      appVersion: typeof body.appVersion === "string" ? body.appVersion.slice(0, 40) : undefined,
    });
    return NextResponse.json({
      ok: true,
      deviceId: device.id,
      preferences: device.preferences,
    }, { status: 201, headers: NO_STORE });
  } catch (error) {
    console.error("[push/native] registration failed", error);
    return NextResponse.json({ error: "storage_unavailable" }, { status: 503, headers: NO_STORE });
  }
}

export async function DELETE(req: Request) {
  if (!rateLimit(`native-push-revoke:${clientIp(req)}`, 20, 60_000, Date.now())) {
    return NextResponse.json({ error: "rate_limited" }, { status: 429, headers: NO_STORE });
  }
  const auth = await session(req);
  if ("response" in auth) return auth.response;
  const body: unknown = await req.json().catch(() => null);
  const token = record(body) && typeof body.token === "string" ? body.token.trim().toLowerCase() : "";
  if (!IOS_TOKEN.test(token)) return NextResponse.json({ error: "bad_device" }, { status: 400, headers: NO_STORE });
  try {
    await revokeNativeDevice(auth.customerId, token);
    return NextResponse.json({ ok: true }, { headers: NO_STORE });
  } catch (error) {
    console.error("[push/native] revoke failed", error);
    return NextResponse.json({ error: "storage_unavailable" }, { status: 503, headers: NO_STORE });
  }
}
