// Маска и нормализация номера РК/РФ: +7 (7XX) XXX-XX-XX.
// phoneDigits — 11 цифр 7XXXXXXXXXX (ключ аккаунта, тело запроса в P1SMS).
// formatPhone — то, что видит пользователь. Зеркалит Flutter _PhoneFormatter.

export function phoneDigits(raw: string): string {
  let d = (raw || "").replace(/\D/g, "");
  if (d.startsWith("8")) d = "7" + d.slice(1);
  if (d && !d.startsWith("7")) d = "7" + d;
  return d.slice(0, 11);
}

export function formatPhone(raw: string): string {
  const d = phoneDigits(raw);
  if (!d) return "";
  let s = "+7";
  if (d.length > 1) s += " (" + d.slice(1, Math.min(4, d.length));
  if (d.length >= 4) s += ")";
  if (d.length > 4) s += " " + d.slice(4, Math.min(7, d.length));
  if (d.length > 7) s += "-" + d.slice(7, Math.min(9, d.length));
  if (d.length > 9) s += "-" + d.slice(9, 11);
  return s;
}

export const isFullPhone = (raw: string): boolean => phoneDigits(raw).length === 11;
