import { NextResponse } from "next/server";
import {
  activateCode,
  discardCode,
  genCode,
  normalizePhone,
  reserveCode,
  sendSms,
} from "@/lib/otp";
import { customerAuthMode } from "@/lib/customerAuthMode";
import { clientIp, rateLimit } from "@/lib/rateLimit";

// POST { phone } → шлёт SMS-код через настроенного провайдера. Обходов без реальной отправки нет.
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  if (customerAuthMode() === "demo") {
    return NextResponse.json(
      { ok: false, sent: false, error: "sms_disabled" },
      { status: 403, headers: { "cache-control": "no-store" } },
    );
  }
  const now = Date.now();
  if (!rateLimit(`otp:${clientIp(req)}`, 10, 10 * 60_000, now) ||
      !rateLimit("otp:global", 120, 60_000, now)) {
    return NextResponse.json({ ok: false, error: "too_many_requests" }, { status: 429, headers: { "cache-control": "no-store" } });
  }
  const body = await req.json().catch(() => ({}));
  const phone = normalizePhone(body?.phone || "");
  if (phone.length !== 11) {
    return NextResponse.json({ ok: false, error: "bad_phone" }, { status: 400, headers: { "cache-control": "no-store" } });
  }
  const code = genCode();
  if (!reserveCode(phone, code)) {
    return NextResponse.json({ ok: false, error: "too_soon" }, { status: 429, headers: { "cache-control": "no-store" } });
  }
  // Короткий латинский шаблон устойчив к ограничениям неподтвержденных SMS-шаблонов.
  const text = `AptekaSoSklada: ${code}`;
  const r = await sendSms(phone, text);
  if (!r.ok) {
    discardCode(phone, code);
    console.error("OTP provider send failed", { reason: r.error || "unknown" });
    return NextResponse.json(
      { ok: false, sent: false, error: "provider_unavailable" },
      { status: 502, headers: { "cache-control": "no-store" } },
    );
  }
  if (!activateCode(phone, code)) {
    return NextResponse.json(
      { ok: false, sent: true, error: "otp_state_error" },
      { status: 500, headers: { "cache-control": "no-store" } },
    );
  }
  return NextResponse.json({ ok: true, sent: true }, { headers: { "cache-control": "no-store" } });
}
