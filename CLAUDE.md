# Epharm — контракт для Claude

Система мотивации фармацевтов (Казахстан): админка HQ + мобилка фармацевта + POSM-клиент
кассы Стандарт-Н + единый backend. Прод живой — аккуратно с деплоем и данными.

## Контракт работы (экономия токенов)

1. **Сначала документация, потом код.** Вся документация — в `docs/`
   (индекс: `docs/README.md`). НЕ сканируй дерево проекта и не читай код «на разведку»,
   пока не посмотрел:
   - `docs/00-project-map.md` — карта проекта и важных файлов (где что лежит);
   - профильный док модуля: `02-backend` / `03-admin-panel` / `04-mobile-app` /
     `05-posm-client` / `06-deployment-and-ops` / `07-database`.
2. **Рабочая память** (живые заметки, читать перед задачей по модулю и обновлять после
   нетривиальных решений): `admin-panel/claude-admin-notes.md` (бэк/админка/POSM),
   `docs/claude-notes.md` (мобилка).
3. **Точečные правки.** Найдя файл через карту — читай только нужный диапазон, не весь файл.
4. После структурных изменений обновляй `docs/00-project-map.md` и этот файл.

## Карта (сжатая — полная в docs/00-project-map.md)

- `admin-panel/backend/` — Kotlin/Spring Boot, ЕДИНЫЙ бэкенд; конфиг: `src/main/resources/application.yml`; миграции V001–V032
- `admin-panel/frontend/` — React 19 админка; разделы: `src/features/*/Page.tsx`
- `lib/` + `test/` — Flutter-мобилка; вход: `lib/features/auth/`, конфиг: `lib/core/config/api_config.dart`
- `App/` + `Models/` — C#/WPF POSM-клиент кассы; конфиг: `App/Config/EpharmConfig.cs`
- `docs/` — вся документация; `builds/` — архив релизов; `_reference/` — дизайн-токены
- Прод: `root@78.140.246.238:/root/epharm` (ключ epharm_deploy), хост `epharm.78-140-246-238.sslip.io`

## Команды (проверка после изменений)

- Backend: `cd admin-panel/backend && ./gradlew test --tests "<пакет>.*"` (полный suite тяжёлый — таргетно; Testcontainers требует Docker)
- Админка: `cd admin-panel/frontend && npm test`
- Мобилка: `export PATH="$HOME/development/flutter/bin:$PATH" && flutter analyze && flutter test`
- Деплой: см. `docs/06-deployment-and-ops.md` (git archive → scp → compose build; НЕ git на сервере)

## Правила

- Коммиты: scope из [admin, backend, mobile, posm, infra, repo, deps], header ≤72 символа
  (commitlint), трейлер Co-Authored-By. Ветка: `feat/mobile-backend`.
- Секреты НЕ коммитить и НЕ светить в командах/логах: `.env.prod` (только на сервере),
  `docs/STOREFRONT-CREDENTIALS.md` (untracked), device key POSM, P1SMS_API_KEY.
- `.ps1`-файлы: UTF-8 с РОВНО одним BOM + CRLF (двойной BOM ломает парсер PowerShell).
- APK отдавать ссылкой MinIO (`epharm-demo.apk`), не локальным путём.
- Время в админке — локальный пояс зрителя (toLocaleString БЕЗ timeZone).
- Правила рекомендаций создаются ТОЛЬКО из Промо-кампаний (rules-engine read-only).
