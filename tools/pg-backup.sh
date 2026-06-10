#!/usr/bin/env bash
# Ежедневный бэкап Postgres Epharm: pg_dump | gzip → backups/, retention 30 дней.
# Ставится в root-cron на прод-сервере (см. RUNBOOK). Запуск вручную: sudo bash tools/pg-backup.sh
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"   # корень деплоя (~/epharm)
cd "$DIR"

# POSTGRES_USER / POSTGRES_DB берём из .env.prod (значения без спецсимволов).
eval "$(grep -E '^POSTGRES_(USER|DB)=' .env.prod)"

mkdir -p backups
TS="$(date +%Y%m%d-%H%M%S)"
OUT="backups/epharm-${TS}.sql.gz"

# pg_dump внутри контейнера → gzip на хост. -i: без TTY (для cron).
docker exec -i epharm-postgres pg_dump -U "${POSTGRES_USER}" "${POSTGRES_DB}" | gzip > "$OUT"

SIZE="$(du -h "$OUT" | cut -f1)"
echo "$(date '+%F %T') backup OK: $OUT ($SIZE)"

# Retention: удалить дампы старше 30 дней.
find backups -name 'epharm-*.sql.gz' -mtime +30 -delete

# Защита прав (дампы содержат все данные).
chmod 600 "$OUT"
