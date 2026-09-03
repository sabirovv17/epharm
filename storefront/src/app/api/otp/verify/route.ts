import { NextResponse } from "next/server";
import { customerAuthMode } from "@/lib/customerAuthMode";
import { normalizePhone, checkCode } from "@/lib/otp";

// POST { phone, code } → проверяет код. ok:true одноразово (код удаляется).
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  if (customerAuthMode() === "demo") {
    return NextResponse.json(
      { ok: false, reason: "sms_disabled" },
      { status: 403, headers: { "cache-control": "no-store" } },
    );
  }
  const body = await req.json().catch(() => ({}));
  const phone = normalizePhone(body?.phone || "");
  const code = String(body?.code ?? "").trim();
  if (phone.length !== 11 || code.length < 3) {
    return NextResponse.json({ ok: false, reason: "bad_input" }, { status: 400, headers: { "cache-control": "no-store" } });
  }
  const r = checkCode(phone, code);
  return NextResponse.json({ ok: r.ok, reason: r.reason }, { status: r.ok ? 200 : 400, headers: { "cache-control": "no-store" } });
}
