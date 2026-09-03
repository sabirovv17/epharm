// OTP по SMS. Учетные данные провайдера — серверные секреты, в клиент не уходят.
// Коды держим в памяти процесса (для одного инстанса/дева достаточно). Для мульти-инстанса
// вынести в Postgres/Redis. globalThis — чтобы пережить hot-reload Next dev.

import { randomInt } from "node:crypto";

type Entry = {
  code: string;
  expires: number;
  attempts: number;
  state: "pending" | "active";
};

type Cooldown = {
  code: string;
  until: number;
};

const g = globalThis as unknown as {
  __otp?: Map<string, Entry>;
  __otpCooldowns?: Map<string, Cooldown>;
};
const store: Map<string, Entry> = (g.__otp ??= new Map());
const cooldowns: Map<string, Cooldown> = (g.__otpCooldowns ??= new Map());

const TTL_MS = 5 * 60 * 1000; // код живёт 5 минут
const RESEND_MS = 30 * 1000; // не чаще раза в 30с
const MAX_ATTEMPTS = 5;

/** Любой ввод → 11 цифр в формате 7XXXXXXXXXX (КЗ/РФ). */
export function normalizePhone(raw: string): string {
  let d = (raw || "").replace(/\D/g, "");
  if (d.startsWith("8")) d = "7" + d.slice(1);
  if (!d.startsWith("7")) d = "7" + d;
  return d.slice(0, 11);
}

export function genCode(): string {
  return String(randomInt(0, 1_000_000)).padStart(6, "0");
}

/** Можно ли слать сейчас (анти-флуд). */
export function canSend(phone: string, now = Date.now()): boolean {
  const cooldown = cooldowns.get(phone);
  if (!cooldown) return true;
  if (now >= cooldown.until) {
    cooldowns.delete(phone);
    return true;
  }
  return false;
}

/** Атомарно резервирует отправку, чтобы параллельные запросы не списали деньги дважды. */
export function reserveCode(phone: string, code: string, now = Date.now()): boolean {
  if (!canSend(phone, now)) return false;
  store.set(phone, {
    code,
    expires: now + TTL_MS,
    attempts: 0,
    state: "pending",
  });
  cooldowns.set(phone, { code, until: now + RESEND_MS });
  return true;
}

/** Код становится пригодным для входа только после успешного ответа SMS-шлюза. */
export function activateCode(phone: string, code: string): boolean {
  const entry = store.get(phone);
  if (!entry || entry.code !== code || entry.state !== "pending") return false;
  entry.state = "active";
  return true;
}

/** Освобождает резерв при ошибке шлюза, чтобы пользователь мог повторить отправку. */
export function discardCode(phone: string, code: string) {
  const entry = store.get(phone);
  if (entry?.code !== code) return;
  store.delete(phone);
  if (cooldowns.get(phone)?.code === code) cooldowns.delete(phone);
}

/** Validate an OTP without consuming it. Customer login consumes only after
 * Medusa has completed, so an upstream timeout does not burn a valid SMS. */
export function validateCode(
  phone: string,
  code: string,
  now = Date.now(),
): { ok: boolean; reason?: string } {
  const e = store.get(phone);
  if (!e) return { ok: false, reason: "no_code" };
  if (e.state !== "active") return { ok: false, reason: "not_sent" };
  if (now > e.expires) { store.delete(phone); return { ok: false, reason: "expired" }; }
  if (++e.attempts > MAX_ATTEMPTS) {
    // Delete only the verification secret. The independently stored resend
    // cooldown remains authoritative until its original deadline.
    store.delete(phone);
    return { ok: false, reason: "too_many" };
  }
  if (e.code !== code.trim()) return { ok: false, reason: "mismatch" };
  return { ok: true };
}

export function consumeCode(phone: string, code: string, now = Date.now()): boolean {
  const e = store.get(phone);
  if (!e || e.state !== "active" || e.code !== code.trim() || now > e.expires) return false;
  store.delete(phone);
  return true;
}

export function checkCode(
  phone: string,
  code: string,
  now = Date.now(),
): { ok: boolean; reason?: string } {
  const checked = validateCode(phone, code, now);
  if (checked.ok) consumeCode(phone, code, now);
  return checked;
}

type SmsResult = { ok: boolean; error?: string };

function providerError(value: unknown, fallback: string): string {
  if (typeof value === "string" && value.trim()) return value.slice(0, 240);
  if (typeof value === "number") return String(value);
  return fallback;
}

export function smscResponseResult(
  httpOk: boolean,
  status: number,
  data: Record<string, unknown>,
): SmsResult {
  if (!httpOk || data.error || data.error_code) {
    return {
      ok: false,
      error: providerError(data.error, data.error_code ? `smsc_${data.error_code}` : `http_${status}`),
    };
  }
  if (data.id === undefined && data.cnt === undefined) {
    return { ok: false, error: "smsc_invalid_response" };
  }
  return { ok: true };
}

async function sendViaSmsc(phone: string, text: string): Promise<SmsResult> {
  const login = process.env.SMSC_LOGIN?.trim();
  const password = process.env.SMSC_PASSWORD;
  const apiKey = process.env.SMSC_API_KEY;
  if ((!login || !password) && !apiKey) return { ok: false, error: "smsc_credentials_missing" };

  const form = new URLSearchParams({
    phones: phone,
    mes: text,
    fmt: "3",
    charset: "utf-8",
  });
  if (apiKey) form.set("apikey", apiKey);
  else {
    form.set("login", login!);
    form.set("psw", password!);
  }
  const sender = process.env.SMSC_SENDER?.trim();
  if (sender) form.set("sender", sender);

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8_000);
  try {
    const res = await fetch("https://smsc.kz/sys/send.php", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded;charset=UTF-8" },
      body: form.toString(),
      signal: controller.signal,
    });
    const data = await res.json().catch(() => ({} as Record<string, unknown>));
    return smscResponseResult(res.ok, res.status, data as Record<string, unknown>);
  } catch (error) {
    return { ok: false, error: providerError(error instanceof Error ? error.message : error, "smsc_unavailable") };
  } finally {
    clearTimeout(timeout);
  }
}

/** Legacy P1SMS adapter retained for rollback while SMSC.kz is the active provider. */
async function sendViaP1Sms(phone: string, text: string): Promise<SmsResult> {
  const apiKey = process.env.P1SMS_API_KEY;
  if (!apiKey) return { ok: false, error: "no_api_key" };
  const channel = process.env.P1SMS_CHANNEL || "digit";
  const sender = process.env.P1SMS_SENDER;
  const sms: Record<string, unknown> = { channel, text, phone };
  if (sender && channel !== "digit") sms.sender = sender;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8_000);
  try {
    const res = await fetch("https://admin.p1sms.kz/apiSms/create", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ apiKey, sms: [sms] }),
      signal: controller.signal,
    });
    const data = await res.json().catch(() => ({} as Record<string, unknown>));
    // P1SMS может вернуть общий status:"success", но ошибку по конкретному SMS внутри data[].
    // Считаем успехом только если по сообщению нет ошибки (sent/queued/moderation — ок).
    const first = Array.isArray((data as { data?: unknown[] })?.data) ? (data as { data: Array<Record<string, unknown>> }).data[0] : undefined;
    const msgStatus = first?.status as string | undefined;
    const msgErrors = first?.errors;
    const failed =
      !res.ok ||
      (data as { status?: string })?.status === "error" ||
      msgStatus === "error" ||
      msgStatus === "not_sent" ||
      (Array.isArray(msgErrors) && msgErrors.length > 0);
    if (failed) {
      return { ok: false, error: providerError(msgErrors ?? (data as { message?: string })?.message, `http_${res.status}`) };
    }
    return { ok: true };
  } catch (e) {
    return { ok: false, error: String(e) };
  } finally {
    clearTimeout(timeout);
  }
}

export async function sendSms(phone: string, text: string): Promise<SmsResult> {
  const provider = String(process.env.SMS_PROVIDER || (process.env.SMSC_PASSWORD || process.env.SMSC_API_KEY ? "smsc" : "p1sms"))
    .trim()
    .toLowerCase();
  return provider === "smsc" ? sendViaSmsc(phone, text) : sendViaP1Sms(phone, text);
}
