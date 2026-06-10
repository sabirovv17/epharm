#!/usr/bin/env bash
# Генерация .env.prod из .env.prod.example с надёжными случайными секретами.
# Заполняет JWT_SECRET / POSM_DEVICE_KEY / пароли БД-MinIO / ADMIN_BOOTSTRAP_PASSWORD.
# Домены, ACME_EMAIL и ADMIN_BOOTSTRAP_EMAIL нужно вписать вручную (печатает напоминание).
#
# Использование:  bash tools/gen-prod-env.sh
set -euo pipefail

cd "$(dirname "$0")/.."

SRC=".env.prod.example"
DST=".env.prod"

[ -f "$SRC" ] || { echo "✗ нет $SRC"; exit 1; }
if [ -f "$DST" ]; then
  echo "✗ $DST уже существует — не перезаписываю. Удали вручную, если нужно пересоздать."
  exit 1
fi

# openssl rand: base64 для JWT (длинный), hex для ключей/паролей (без спецсимволов,
# чтобы не ломать docker .env-парсинг).
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
POSM_DEVICE_KEY="$(openssl rand -hex 24)"
POSTGRES_PASSWORD="$(openssl rand -hex 24)"
MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)"
ADMIN_BOOTSTRAP_PASSWORD="$(openssl rand -base64 18 | tr -d '\n')"

cp "$SRC" "$DST"

# Подставляем значения (BSD/GNU sed-совместимо: через временный файл).
set_kv() {
  local key="$1" val="$2"
  # экранируем & и / для sed-замены
  local esc; esc=$(printf '%s' "$val" | sed -e 's/[&/\]/\\&/g')
  if grep -q "^${key}=" "$DST"; then
    sed "s/^${key}=.*/${key}=${esc}/" "$DST" > "$DST.tmp" && mv "$DST.tmp" "$DST"
  else
    printf '%s=%s\n' "$key" "$val" >> "$DST"
  fi
}

set_kv JWT_SECRET "$JWT_SECRET"
set_kv POSM_DEVICE_KEY "$POSM_DEVICE_KEY"
set_kv POSTGRES_PASSWORD "$POSTGRES_PASSWORD"
set_kv MINIO_ROOT_PASSWORD "$MINIO_ROOT_PASSWORD"
set_kv ADMIN_BOOTSTRAP_PASSWORD "$ADMIN_BOOTSTRAP_PASSWORD"

chmod 600 "$DST"

echo "✓ Создан $DST (chmod 600) с надёжными секретами."
echo
echo "⚠️  Заполни ВРУЧНУЮ перед деплоем:"
echo "    - API_DOMAIN / ADMIN_DOMAIN / S3_DOMAIN  (реальные домены, A-записи на сервер)"
echo "    - ACME_EMAIL                              (для Let's Encrypt)"
echo "    - ADMIN_BOOTSTRAP_EMAIL                   (логин первого админа HQ-консоли)"
echo "    - CORS_ALLOWED_ORIGINS=https://<ADMIN_DOMAIN>"
echo
echo "    Пароль первого админа (ADMIN_BOOTSTRAP_PASSWORD) сохранён в $DST — скопируй его в надёжное место."
echo
echo "Запуск стека:  docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build"
