/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Реальная авторизация покупателя через Medusa customer (не localStorage-мок).
 * Браузер к Медузе напрямую не ходит (CORS) → всё через этот серверный роут с publishable-ключом.
 *
 * Флоу «вход по номеру» (passwordless): клиент шлёт {phone, code, name} — код из SMS
 * проверяется ЗДЕСЬ (checkCode), и только при валидном коде делаем get-or-create customer.
 * Пароль Медузы детерминированно выводится из номера (HMAC с серверным секретом) —
 * пользователь его не видит и не вводит; аккаунт защищён владением номером (OTP).
 *
 * Токен Медузы кладём в httpOnly-cookie (браузеру недоступен из JS). Бонус/уровень —
 * в customer.metadata (персистит на бэке).
 */
import crypto from "node:crypto";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import {
  createDemoSession,
  createDemoSessionForInstallId,
  customerAuthMode,
  demoIdentity,
  isSyntheticCustomerEmail,
  isValidDemoInstallId,
  readDemoSession,
} from "@/lib/customerAuthMode";
import { secureMedusaBaseUrl } from "@/lib/medusaUrl";
import { consumeCode, normalizePhone, validateCode } from "@/lib/otp";
import { clientIp, rateLimit } from "@/lib/rateLimit";

export const dynamic = "force-dynamic";

const BASE = secureMedusaBaseUrl(process.env.MEDUSA_URL);
const PK = process.env.MEDUSA_PUBLISHABLE_KEY || "";
// ВАЖНО: секрет должен быть СТАБИЛЬНЫМ между рестартами, иначе выведенный пароль изменится
// и существующие пользователи не смогут войти. Фиксированный дефолт — чтобы работало без конфигурации.
const SECRET = process.env.CUSTOMER_AUTH_SECRET || "";
const EMAIL_DOMAIN = "phone.darihana.kz";
const COOKIE = "ms_cust";
const DEMO_COOKIE = "inkar_demo_session";
const MEDUSA_ON = Boolean(BASE && PK) && process.env.MEDUSA_ENABLED !== "false";
const AUTH_MODE = customerAuthMode();

function derivePassword(digits: string): string {
  if (!SECRET) throw new Error("CUSTOMER_AUTH_SECRET is not configured");
  return "P!" + crypto.createHmac("sha256", SECRET).update(digits).digest("base64url").slice(0, 26);
}
const phoneEmail = (digits: string) => `u${digits}@${EMAIL_DOMAIN}`;

// Токен сессии: web — из httpOnly-cookie, мобилка (без cookie) — из заголовка Authorization: Bearer.
function bearer(req: Request): string | null {
  const h = req.headers.get("authorization") || "";
  const mt = h.match(/^Bearer\s+(.+)$/i);
  return mt ? mt[1].trim() : null;
}
async function sessionToken(req: Request): Promise<string | null> {
  return bearer(req) || (await cookies()).get(COOKIE)?.value || null;
}

async function m(path: string, opts: { method?: string; token?: string | null; body?: any } = {}) {
  if (!BASE) return { status: 0, data: null };
  const headers: Record<string, string> = { "x-publishable-api-key": PK };
  if (opts.token) headers.authorization = "Bearer " + opts.token;
  if (opts.body) headers["content-type"] = "application/json";
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 10000);
  try {
    const res = await fetch(BASE + path, {
      method: opts.method || "GET",
      headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      signal: ctl.signal,
      cache: "no-store",
    });
    let data: any = null;
    try { data = await res.json(); } catch { /* пустой ответ */ }
    return { status: res.status, data };
  } catch {
    return { status: 0, data: null };
  } finally {
    clearTimeout(timer);
  }
}

function mapCustomer(c: any) {
  const meta = c?.metadata || {};
  const email = isSyntheticCustomerEmail(c?.email) ? null : (c?.email || null);
  return {
    name: c?.first_name || "Гость",
    phone: c?.phone ? normalizePhone(c.phone) : "",
    email,
    bonus: Number(meta.bonus ?? 0) || 0,
    level: String(meta.level || "Bronze"),
    demo: meta.demo_customer === true,
  };
}

function mapDemoCustomer(sessionId: string, name = "Демо-покупатель") {
  const identity = demoIdentity(sessionId, SECRET);
  return {
    name: name.trim() || "Демо-покупатель",
    phone: identity.phone,
    email: null,
    bonus: 1500,
    level: "Silver",
    demo: true,
  };
}

type CustomerCredentials = {
  email: string;
  password: string;
  phone: string;
  name: string;
  metadata?: Record<string, unknown>;
};

/** Логин по серверным credentials; если нет — регистрируем и создаём store-customer. */
async function getOrCreateCustomer({ email, password, phone, name, metadata }: CustomerCredentials) {
  let token: string | null = null;

  let r = await m("/auth/customer/emailpass", { method: "POST", body: { email, password } });
  if (r.data?.token) token = r.data.token;
  else {
    r = await m("/auth/customer/emailpass/register", { method: "POST", body: { email, password } });
    if (r.data?.token) token = r.data.token;
    else {
      // identity уже есть (или гонка) — пробуем войти ещё раз
      r = await m("/auth/customer/emailpass", { method: "POST", body: { email, password } });
      token = r.data?.token || null;
    }
  }
  if (!token) return { error: "auth_unavailable" as const };

  let me = await m("/store/customers/me", { token });
  let cust = me.data?.customer;
  if (!cust) {
    // новый (или битая прошлая регистрация без customer) — создаём + приветственные баллы
    await m("/store/customers", {
      method: "POST",
      token,
      body: {
        email,
        first_name: name.trim() || "Гость",
        phone,
        metadata: { bonus: 1500, level: "Silver", ...metadata },
      },
    });
    // В Medusa v2 register-токен не видит связь с только что созданным customer —
    // берём СВЕЖИЙ токен логином, иначе /me вернёт пусто (customer при этом уже есть).
    const relog = await m("/auth/customer/emailpass", { method: "POST", body: { email, password } });
    if (relog.data?.token) token = relog.data.token;
    me = await m("/store/customers/me", { token });
    cust = me.data?.customer;
  }
  if (!cust) return { error: "no_customer" as const };
  return { token: token!, customer: cust }; // token гарантированно не-null (см. guard выше)
}

function getOrCreatePhone(digits: string, name: string) {
  return getOrCreateCustomer({
    email: phoneEmail(digits),
    password: derivePassword(digits),
    phone: digits,
    name,
  });
}

const cookieOpts = {
  httpOnly: true as const,
  sameSite: "lax" as const,
  path: "/",
  maxAge: 60 * 60 * 24 * 90, // 90 дней
  secure: process.env.NODE_ENV === "production",
};

// GET → текущий покупатель по cookie (или null).
export async function GET(req: Request) {
  if (AUTH_MODE === "demo" && SECRET) {
    const cookieStore = await cookies();
    const signedSession = req.headers.get("x-demo-session") || cookieStore.get(DEMO_COOKIE)?.value || null;
    const sessionId = readDemoSession(signedSession, SECRET);
    return NextResponse.json(
      { user: sessionId ? mapDemoCustomer(sessionId) : null, authMode: AUTH_MODE },
      { headers: { "cache-control": "no-store" } },
    );
  }
  if (!MEDUSA_ON) return NextResponse.json({ user: null, authMode: AUTH_MODE }, { headers: { "cache-control": "no-store" } });
  const token = await sessionToken(req);
  if (!token) return NextResponse.json({ user: null, authMode: AUTH_MODE }, { headers: { "cache-control": "no-store" } });
  const me = await m("/store/customers/me", { token });
  if (me.data?.customer) {
    return NextResponse.json({ user: mapCustomer(me.data.customer), authMode: AUTH_MODE }, { headers: { "cache-control": "no-store" } });
  }
  // Невалидный токен (401) — чистим cookie. Сетевой сбой/5xx — транзиент: НЕ разлогиниваем.
  if (me.status === 401 || me.status === 404) {
    const res = NextResponse.json({ user: null, authMode: AUTH_MODE }, { headers: { "cache-control": "no-store" } });
    res.cookies.set(COOKIE, "", { ...cookieOpts, maxAge: 0 });
    return res;
  }
  return NextResponse.json({ user: null, authMode: AUTH_MODE, transient: true }, { headers: { "cache-control": "no-store" } });
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => ({} as any));
  const action = String(body?.action || "");

  if (action === "logout") {
    const res = NextResponse.json({ ok: true }, { headers: { "cache-control": "no-store" } });
    res.cookies.set(COOKIE, "", { ...cookieOpts, maxAge: 0 });
    res.cookies.set(DEMO_COOKIE, "", { ...cookieOpts, maxAge: 0 });
    return res;
  }

  if (action === "demo") {
    if (AUTH_MODE !== "demo") {
      return NextResponse.json({ error: "demo_disabled" }, { status: 403, headers: { "cache-control": "no-store" } });
    }
    if (!SECRET) {
      return NextResponse.json({ error: "auth_unavailable" }, { status: 503, headers: { "cache-control": "no-store" } });
    }
    if (!rateLimit(`customer-demo:${clientIp(req)}`, 12, 60 * 60_000, Date.now())) {
      return NextResponse.json({ error: "too_many_requests" }, { status: 429, headers: { "cache-control": "no-store" } });
    }

    const hasInstallId = body?.installId !== undefined && body?.installId !== null;
    if (hasInstallId && !isValidDemoInstallId(body.installId)) {
      return NextResponse.json({ error: "bad_install_id" }, { status: 400, headers: { "cache-control": "no-store" } });
    }

    const cookieStore = await cookies();
    let signedSession = hasInstallId
      ? createDemoSessionForInstallId(body.installId, SECRET)
      : (cookieStore.get(DEMO_COOKIE)?.value || "");
    let sessionId = readDemoSession(signedSession, SECRET);
    if (!sessionId && !hasInstallId) {
      signedSession = createDemoSession(SECRET);
      sessionId = readDemoSession(signedSession, SECRET);
    }
    if (!sessionId) {
      return NextResponse.json({ error: "auth_unavailable" }, { status: 503, headers: { "cache-control": "no-store" } });
    }

    const identity = demoIdentity(sessionId, SECRET);
    if (hasInstallId && !rateLimit(`customer-demo-install:${identity.sessionHash}`, 20, 10 * 60_000, Date.now())) {
      return NextResponse.json({ error: "too_many_requests" }, { status: 429, headers: { "cache-control": "no-store" } });
    }
    const res = NextResponse.json(
      {
        user: mapDemoCustomer(sessionId, String(body?.name || "Демо-покупатель")),
        authMode: AUTH_MODE,
        ...(body?.withToken ? { token: signedSession, demoSession: signedSession } : {}),
      },
      { headers: { "cache-control": "no-store" } },
    );
    res.cookies.set(DEMO_COOKIE, signedSession, cookieOpts);
    return res;
  }

  if (AUTH_MODE === "demo" && action === "delete") {
    const cookieStore = await cookies();
    const signedSession = req.headers.get("x-demo-session") || cookieStore.get(DEMO_COOKIE)?.value || null;
    if (!readDemoSession(signedSession, SECRET)) {
      return NextResponse.json({ ok: false, error: "no_session" }, { status: 401, headers: { "cache-control": "no-store" } });
    }
    const res = NextResponse.json({ ok: true, status: "deleted" }, { status: 202, headers: { "cache-control": "no-store" } });
    res.cookies.set(COOKIE, "", { ...cookieOpts, maxAge: 0 });
    res.cookies.set(DEMO_COOKIE, "", { ...cookieOpts, maxAge: 0 });
    return res;
  }

  if (AUTH_MODE === "demo" && action === "update") {
    const cookieStore = await cookies();
    const signedSession = req.headers.get("x-demo-session") || cookieStore.get(DEMO_COOKIE)?.value || null;
    const sessionId = readDemoSession(signedSession, SECRET);
    if (!sessionId) {
      return NextResponse.json({ user: null, error: "no_session" }, { status: 401, headers: { "cache-control": "no-store" } });
    }
    return NextResponse.json(
      { user: mapDemoCustomer(sessionId, String(body?.name || "Демо-покупатель")) },
      { headers: { "cache-control": "no-store" } },
    );
  }

  if (!MEDUSA_ON) return NextResponse.json({ error: "medusa_off" }, { status: 503, headers: { "cache-control": "no-store" } });

  if (action === "continue") {
    if (AUTH_MODE !== "sms") {
      return NextResponse.json({ error: "sms_disabled" }, { status: 403, headers: { "cache-control": "no-store" } });
    }
    const digits = normalizePhone(body?.phone || "");
    const code = String(body?.code ?? "").trim();
    if (digits.length !== 11) return NextResponse.json({ error: "bad_phone" }, { status: 400, headers: { "cache-control": "no-store" } });
    // Ключевая проверка: аккаунт выдаём ТОЛЬКО при валидном одноразовом коде из SMS.
    const chk = validateCode(digits, code);
    if (!chk.ok) return NextResponse.json({ error: "bad_code", reason: chk.reason }, { status: 401, headers: { "cache-control": "no-store" } });

    const r = await getOrCreatePhone(digits, String(body?.name || ""));
    if ("error" in r) return NextResponse.json({ error: r.error }, { status: 502, headers: { "cache-control": "no-store" } });
    consumeCode(digits, code);

    const res = NextResponse.json(
      { user: mapCustomer(r.customer), authMode: AUTH_MODE, ...(body?.withToken ? { token: r.token } : {}) },
      { headers: { "cache-control": "no-store" } },
    );
    res.cookies.set(COOKIE, r.token, cookieOpts);
    return res;
  }

  if (action === "delete") {
    const token = await sessionToken(req);
    if (!token) return NextResponse.json({ ok: false, error: "no_session" }, { status: 401, headers: { "cache-control": "no-store" } });
    const current = await m("/store/customers/me", { token });
    if (!current.data?.customer) {
      const status = current.status === 401 || current.status === 404 ? 401 : 503;
      return NextResponse.json({ ok: false, error: "customer_unavailable" }, { status, headers: { "cache-control": "no-store" } });
    }
    const metadata = {
      ...(current.data.customer.metadata || {}),
      account_deletion_status: "requested",
      account_deletion_requested_at: new Date().toISOString(),
    };
    const updated = await m("/store/customers/me", { method: "POST", token, body: { metadata } });
    if (updated.status < 200 || updated.status >= 300) {
      return NextResponse.json({ ok: false, error: "deletion_request_failed" }, { status: 502, headers: { "cache-control": "no-store" } });
    }
    const res = NextResponse.json({ ok: true, status: "requested" }, { status: 202, headers: { "cache-control": "no-store" } });
    res.cookies.set(COOKIE, "", { ...cookieOpts, maxAge: 0 });
    res.cookies.set(DEMO_COOKIE, "", { ...cookieOpts, maxAge: 0 });
    return res;
  }

  if (action === "update") {
    const token = await sessionToken(req);
    if (!token) return NextResponse.json({ user: null, error: "no_session" }, { status: 401, headers: { "cache-control": "no-store" } });
    const patch: any = {};
    if (typeof body?.name === "string" && body.name.trim()) patch.first_name = body.name.trim();
    if (typeof body?.email === "string" && body.email.trim()) patch.email = body.email.trim();
    // Loyalty values are server-owned. Never accept bonus/level/spent from a
    // public customer token: a modified mobile client could mint points.
    if (Object.keys(patch).length) await m("/store/customers/me", { method: "POST", token, body: patch });
    const me = await m("/store/customers/me", { token });
    return NextResponse.json({ user: me.data?.customer ? mapCustomer(me.data.customer) : null }, { headers: { "cache-control": "no-store" } });
  }

  return NextResponse.json({ error: "bad_action" }, { status: 400, headers: { "cache-control": "no-store" } });
}
