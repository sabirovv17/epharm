# POSM ↔ Backend ↔ Admin — архитектура рекомендаций и сверки чеков

> Технический дизайн Module 2 (POSM в Стандарт-Н) и его стыковки с уже готовым backend
> (`kz.epharm.*`) и админ-консолью. Решения приняты как контракт — реализуется ровно так.
> Связанные документы: `claude-admin-notes.md` (state), `PLAN.md` (Этап 5), `ИТОГОВОЕ_ТЗ.pdf` §3.3–3.5, §4.

---

## 0. Недостающие данные (нужны от Inkar / пилотной аптеки)

Реализация ниже идёт на **разумных предположениях**, помеченных `[A]`. Когда придут реальные
данные — меняется только конфиг/парсер, не архитектура.

| #   | Что нужно                                                                                  | Зачем                      | Текущее предположение `[A]`                                                                                                                                                                |
| --- | ------------------------------------------------------------------------------------------ | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | Полный формат строк `zkassa.log` на проде (печать чека, фискальный номер, № смены, кассир) | Источник №1 валидации      | Парсер из текущего `MainWindow` (`iPartID/sname/price/quant`, `Add2Cheque`, `ChequeList.OnChange`, «После печати очереди чеков»). Фискальный № берём из строки печати — формат уточняется. |
| 2   | Точная схема Excel-выгрузки из менеджерской части Стандарт-Н (имена листов/колонок)        | Источник №2                | Колонки: `Дата, Время, Касса, Смена, Чек №, Артикул, Наименование, Кол-во, Цена, Сумма, Фармацевт`. Маппинг в конфиге парсера.                                                             |
| 3   | Как POS-машина идентифицирует **фармацевта** (логин кассира → наш `pharmacist_id`)         | Привязка бонуса к человеку | Таблица-маппинг `pos_operator_map` (логин Стандарт-Н → pharmacist_id), заполняется при онбординге аптеки. На MVP — конфиг `EPHARM_PHARMACIST_ID` на машине.                                |
| 4   | Сетевой доступ кассы наружу (есть ли интернет, прокси, белые списки)                       | Транспорт                  | Есть нестабильный интернет → клиент работает offline-first с outbox-очередью.                                                                                                              |
| 5   | Связь `артикул Стандарт-Н (iPartID, int)` ↔ `наш productId (строка p_*)`                   | Rules Engine матчинг       | Таблица `product_pos_codes (pos_code int → product_id)`; на MVP сидируется вручную для пилота.                                                                                             |
| 6   | Версия Стандарт-Н на пилоте + где лежит лог/Excel                                          | Деплой клиента             | `C:\Standart-N\Kassir\zkassa.log`, Excel кладётся в `C:\Epharm\import\`.                                                                                                                   |
| 7   | Реальный ОФД API (для онлайн-проверки фиск.чека)                                           | Усиление источника №1      | Заглушка; интерфейс `IFiscalVerifier` готов под подмену (как `OcrService`).                                                                                                                |

---

## 1. Итоговая архитектура (компоненты)

```
┌─────────────────────────── АПТЕКА (Windows, POS-моноблок) ───────────────────────────┐
│                                                                                        │
│   Стандарт-Н (касса)                                                                   │
│      │ пишет zkassa.log (cp1251)                                                       │
│      ▼                                                                                  │
│   ┌──────────────────────── Epharm POSM Client (C# / WPF / .NET 10) ───────────────┐  │
│   │  LogTailService      — tail -f лога, парсинг событий чека                        │  │
│   │  CheckoutSession     — текущая корзина (List<ReceiptItem>) + sessionId           │  │
│   │  RulesEngineClient   — HTTP → /api/posm/recommend (cart → рекомендации)          │  │
│   │  RecommendationWindow— popup поверх кассы (F9 = принять, Esc = пропустить)        │  │
│   │  CustomerDisplay     — текущий 2-й монитор: промо слева + живой чек справа        │  │
│   │  SaleReporter        — печать чека → POST /api/posm/sales (источник №1)           │  │
│   │  OfflineOutbox       — SQLite-очередь исходящих, ретрай при появлении сети        │  │
│   │  EpharmApiClient     — HttpClient + X-Posm-Key + Polly-ретраи                     │  │
│   └───────────────────────────────────────────────────────────────────────────────┘  │
│                                   │  HTTPS (outbox flush)                               │
└───────────────────────────────────┼────────────────────────────────────────────────────┘
                                     ▼
┌───────────────────────────── BACKEND (Kotlin / Spring Boot) ─────────────────────────┐
│  posm/         RulesEngineService · RecommendationEventService · PosmController        │
│  receipts/     PendingBonusService · ReconcileService (3 источника) · ExcelSalesImport │
│  rules/ catalog/ pharmacists/  — справочники (готово)                                  │
│  PostgreSQL: rules, products, product_pos_codes, recommendation_events,                │
│              pending_bonuses, pos_sales, excel_imports, excel_sale_rows, receipts      │
└───────────────────────────────────┬────────────────────────────────────────────────────┘
                                     ▼
┌───────────────────────────── ADMIN CONSOLE (React) ──────────────────────────────────┐
│  Rules Engine  — правила замен/cross-sell (готово)                                     │
│  Reconcile     — очередь сверки: Approved/Rejected/Pending/ModerationRequired          │
│  Finance       — выплаты по подтверждённым бонусам (готово)                            │
└────────────────────────────────────────────────────────────────────────────────────┘
```

**Менеджер (раз в день)** загружает Excel-выгрузку через админку → backend импортирует и
автоматически сверяет с источником №1.

---

## 2. Транспорт — решение

- **POSM client → backend: HTTPS REST + offline outbox.** Не SignalR/WebSocket для исходящих:
  касса с нестабильным интернетом, события редкие (на чек), важна гарантия доставки, а не
  низкая задержка. Каждое событие пишется в локальный SQLite-outbox и асинхронно
  отправляется с ретраями; падение сети не теряет данные и не блокирует кассу.
- **Рекомендации (`/recommend`) — синхронный REST с жёстким таймаутом 700 мс.** Если backend
  не ответил — popup просто не показывается (касса не должна тормозить). Требование ТЗ §4
  «< 300 мс» обеспечивается кешем активных правил в backend (см. §5).
- **backend → 2-й монитор (плейлисты, режимы Idle/Active/Promo): Server-Sent Events**
  `GET /api/posm/screen/stream` (Этап 5 хвост). Однонаправленный поток, переживает реконнект,
  проще WebSocket. На текущем инкременте экран рендерит локальный плейлист из конфига.
- **Аутентификация устройства:** заголовок `X-Posm-Key: <device-key>` на всех `/api/posm/**`.
  Ключ выдаётся на аптеку при онбординге, хранится в защищённом конфиге машины. Backend
  валидирует ключ (MVP — общий dev-ключ из конфига; prod — ключ-на-устройство в таблице).

---

## 3. Rules Engine — алгоритм

Вход: `pharmacistId, pharmacyId, sessionId, cart: [{sku, qty}], scannedSku?`.
`sku` здесь — наш `productId` (POSM-client конвертит `iPartID` через `product_pos_codes`; если
кода нет — отправляет сырой код, backend его игнорит при матчинге).

```
1. rules = активные правила (status=active), кешированы.
2. SUBSTITUTION кандидаты (rule.type=substitution):
   trigger матчит товар X в корзине:
     kind=product      → trigger.value == X.sku
     kind=product_any  → X.sku ∈ trigger.value
     kind=mnn          → product(X).mnn == trigger.value
   И recommend(Y) НЕ в корзине, Y существует в catalog.
   → кандидат {ruleId, kind=substitution, triggerSku=X, recommendSku=Y, bonus}
3. CROSSSELL кандидаты (rule.type=crosssell):
   trigger матчит корзину (товар A присутствует, та же матчинг-логика по kind)
   И recommend(B) НЕ в корзине, B существует.
   → кандидат {ruleId, kind=crosssell, triggerSku=A, recommendSku=B, bonus}
4. Фильтр «не показывать отклонённое в этом чеке»:
   убрать кандидаты, чей recommendSku уже rejected в recommendation_events(sessionId).
5. Сортировка и лимит (ТЗ §4):
   - сначала ВСЕ substitution (по bonus DESC), затем crosssell (по bonus DESC)
   - dedup по recommendSku (первый победил)
   - top-2
6. Для каждой возвращаемой рекомендации:
   - идемпотентно: если в (sessionId, ruleId) уже есть событие outcome=shown и не decided →
     переиспользуем его eventId; иначе создаём recommendation_event(outcome=shown).
   - резолвим имена/цены из catalog для popup.
7. Ответ: { sessionId, recommendations:[{eventId, ruleId, kind, triggerSku, triggerName,
            recommendSku, recommendName, recommendPrice, bonus, script, advantages}] }
```

**Фиксация факта рекомендации** (ТЗ — «фиксировать факт») = строка `recommendation_events`
с `outcome=shown`. **Фиксация результата** = `POST /recommendations/{eventId}/outcome`
(`accepted` / `rejected`). Это и есть доказательная цепочка для мотивации.

---

## 4. Модели данных (БД, миграция V013)

```sql
-- Связь артикула кассы с нашим каталогом (источник #5 неизвестности)
CREATE TABLE product_pos_codes (
    pos_code    BIGINT      PRIMARY KEY,           -- iPartID из Стандарт-Н
    product_id  VARCHAR(64) NOT NULL REFERENCES products(id)
);

-- Факт + результат показанной рекомендации (доказательная база бонуса)
CREATE TABLE recommendation_events (
    id               VARCHAR(64) PRIMARY KEY,
    session_id       VARCHAR(64) NOT NULL,         -- один открытый чек
    pharmacist_id    VARCHAR(64) NOT NULL,
    pharmacy_id      VARCHAR(64) NOT NULL,
    rule_id          VARCHAR(64) NOT NULL,
    kind             VARCHAR(32) NOT NULL,         -- substitution | crosssell
    trigger_sku      VARCHAR(64),
    recommend_sku    VARCHAR(64) NOT NULL,
    recommend_name   VARCHAR(255) NOT NULL,
    bonus            BIGINT      NOT NULL DEFAULT 0,
    outcome          VARCHAR(32) NOT NULL DEFAULT 'shown', -- shown|accepted|rejected|expired
    pending_bonus_id VARCHAR(64),
    shown_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ
);

-- Источник №1: чек, подтверждённый логом кассы (POSM client → /sales)
CREATE TABLE pos_sales (
    id            VARCHAR(64) PRIMARY KEY,
    session_id    VARCHAR(64) NOT NULL,
    pharmacist_id VARCHAR(64) NOT NULL,
    pharmacy_id   VARCHAR(64) NOT NULL,
    fiscal_id     VARCHAR(128),                    -- фискальный № (для дубль-детекта и Excel-матча)
    cashier       VARCHAR(128),
    shift         VARCHAR(64),
    total_amount  BIGINT NOT NULL DEFAULT 0,
    items         JSONB  NOT NULL,                 -- [{sku,name,qty,price,total}]
    printed_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Источник №2: Excel-выгрузка (шапка импорта + строки)
CREATE TABLE excel_imports (
    id           VARCHAR(64) PRIMARY KEY,
    file_name    VARCHAR(255) NOT NULL,
    uploaded_by  VARCHAR(64)  NOT NULL,
    rows_total   INT NOT NULL DEFAULT 0,
    rows_matched INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE excel_sale_rows (
    id            VARCHAR(64) PRIMARY KEY,
    import_id     VARCHAR(64) NOT NULL REFERENCES excel_imports(id) ON DELETE CASCADE,
    fiscal_id     VARCHAR(128),
    pharmacy_code VARCHAR(64),
    cashier       VARCHAR(128),
    sku           VARCHAR(64),
    product_name  VARCHAR(255),
    qty           NUMERIC(12,3),
    amount        BIGINT,
    sold_at       TIMESTAMPTZ
);
```

`receipts` и `pending_bonuses` (V012) переиспользуются как есть. `receipts.flag_reason`
получает новое значение `moderation_required` для UI-статуса ModerationRequired.

---

## 5. Процесс валидации чека — 3 источника

Бонус из принятой рекомендации создаёт `pending_bonus (awaiting_receipt)`. Подтверждение:

```
Источник №1 (лог кассы, /api/posm/sales): pos_sale с этим товаром у этого фармацевта,
   в окне ±30 мин от pending_bonus → совпадение по составу/сумме/времени.
Источник №2 (Excel, /import-excel): excel_sale_row с тем же fiscal_id / товаром / суммой.

Решение (ReconcileService.reconcileSources):
  - подтверждён ОБОИМИ (#1 ∧ #2)  → approved (авто), бонус credited.
  - подтверждён ТОЛЬКО ОДНИМ      → moderation_required (ручная проверка).
  - есть в обоих, но расхождение по сумме/составу → flagged.
  - дубль fiscal_id               → flagged duplicate_receipt.
  - нет нигде после N дней        → expired (бонус сгорает).
```

Текущий §3.5-флоу (фото+OCR из мобилки) остаётся как **четвёртый** путь подтверждения для
аптек без POSM-клиента — тот же `ReconcileService`, та же очередь.

Статусы в админке: `Approved · Rejected · Pending · ModerationRequired` (= `receipts.status`

- `flag_reason='moderation_required'`). Карточка чека показывает: позиции, какие источники
  подтвердили, связанную рекомендацию (rule + bonus), кнопки ручного решения.

---

## 6. Процесс начисления бонуса

```
recommendation accepted → PendingBonusService.register(awaiting_receipt)
   → чек подтверждён валидацией → ReconcileService.creditFor:
        pharmacist.balance   += bonus
        pharmacist.earned30d += bonus
        pending_bonus.status  = matched
        receipt.bonus_credited = bonus  (идемпотентно)
   → 1-го числа cron собирает balance в payout_batch (Finance, Этап 4 хвост)
```

Мотивация фармацевта = сумма `matched` бонусов за период. Доказательная цепочка:
`recommendation_event(accepted) → pending_bonus(matched) → pos_sale + excel_row → receipt(approved)`.

---

## 7. API (структура)

| Метод    | Путь                                          | Auth         | Назначение                                   |
| -------- | --------------------------------------------- | ------------ | -------------------------------------------- |
| POST     | `/api/posm/recommend`                         | `X-Posm-Key` | корзина → top-2 рекомендации (≤700 мс)       |
| POST     | `/api/posm/recommendations/{eventId}/outcome` | `X-Posm-Key` | accepted/rejected (accepted → pending_bonus) |
| POST     | `/api/posm/sales`                             | `X-Posm-Key` | подтверждённый лог-чек (источник №1)         |
| GET      | `/api/posm/playlists/active?pharmacyId=`      | `X-Posm-Key` | плейлист для 2-го монитора                   |
| GET      | `/api/posm/screen/stream` (SSE)               | `X-Posm-Key` | Idle/Active/Promo (Этап 5 хвост)             |
| POST     | `/api/admin/reconcile/import-excel`           | JWT          | загрузка Excel (источник №2)                 |
| GET/POST | `/api/admin/reconcile/**`                     | JWT          | очередь + approve/reject (готово)            |

---

## 8. C# — структура решения

```
PharmaPayV2/
├── Epharm.Posm.sln                         (NEW — solution для всего POSM-клиента)
├── App/                  CustomerDisplay.csproj  — WPF UI (расширяем)
│   ├── MainWindow.xaml[.cs]                — киоск + чек (есть) + хук рекомендаций (NEW)
│   ├── RecommendationWindow.xaml[.cs]      — popup поверх кассы (NEW)
│   └── Config/EpharmConfig.cs              — пути/URL/ключи из appsettings (NEW)
├── Models/
│   ├── ReceiptItem.cs                      — строка чека (есть)
│   └── Posm/                               — DTO рекомендаций и продаж (NEW)
│       ├── CartItem.cs · RecommendRequest.cs · RecommendResponse.cs
│       ├── Recommendation.cs · OutcomeRequest.cs · SaleReport.cs
└── Epharm.Posm.Core/     Epharm.Posm.Core.csproj — netstandard2.0 lib, тестируемая логика (NEW)
    ├── Services/
    │   ├── EpharmApiClient.cs              — HttpClient + X-Posm-Key + Polly
    │   ├── IRulesEngineClient.cs / RulesEngineClient.cs
    │   ├── OfflineOutbox.cs                — SQLite-очередь исходящих
    │   ├── SaleReporter.cs                 — печать → /sales (через outbox)
    │   └── CheckoutSession.cs              — корзина + sessionId + lifecycle
    └── Epharm.Posm.Core.Tests/            — xUnit (матчинг/outbox/сессия — без WPF, кросс-платформенно)
```

Логика, которую можно тестировать (сессия, outbox, маппинг DTO), вынесена в
`Epharm.Posm.Core` (netstandard2.0) — собирается и тестируется **на Mac тоже** (`dotnet test`).
WPF-проект (`App/`) собирается только на Windows.

---

## 9. Локальное хранение + offline-sync

- **SQLite** (`Microsoft.Data.Sqlite`) файл `C:\Epharm\outbox.db`. Таблица `outbox(id, kind,
payload_json, created_at, attempts, next_retry_at)`.
- Любое исходящее (sale, outcome) сначала пишется в outbox, затем фоновый `OutboxFlusher`
  (таймер 5 с) отправляет по одному, при успехе удаляет, при ошибке — экспоненциальный backoff.
- `/recommend` НЕ идёт через outbox (синхронный, его смысл — здесь и сейчас); при оффлайне
  просто не показываем popup.
- Идемпотентность: каждое исходящее несёт client-generated `id` (GUID); backend апсертит по нему
  → повторная отправка после падения не двоит данные.

---

## 10. Обработка логов Стандарт-Н

Переиспользуем готовый `TailLogLoop`/`ProcessLogLine` из `MainWindow.xaml.cs`, выносим в
`LogTailService` (Core). События → `CheckoutSession`:
`Add2Cheque` → AddItem; `(delete)` → RemoveItem; `ChequeList.OnChange` → ApplyDiscount;
`«После печати очереди чеков»` → **Finalize** (session закрывается → SaleReporter.Report).
На каждый AddItem (debounce 400 мс) → RulesEngineClient.Recommend(cart).

---

## 11. Импорт Excel

`IExcelSalesParser` (интерфейс) + `PoiExcelSalesParser` (Apache POI, `poi-ooxml`). Маппинг
колонок — в конфиге (`app.excel.columns`). Контроллер `/import-excel` (multipart) →
parser → `excel_sale_rows` → `ReconcileService.reconcileSources` по каждому fiscal_id.
Паттерн «сервис за интерфейсом» — как `OcrService`/`MediaStorage`.

---

## 12. Последовательность реализации (этапы)

- **Stage 1 (этот инкремент): Rules Engine + фиксация рекомендаций.** ✅ backend-verified.
  `/recommend` + `/outcome` + `recommendation_events` + `pending_bonus` при accepted +
  V013 (recommendation_events, product_pos_codes) + интеграционные тесты. C#-ядро:
  `EpharmApiClient`, `RulesEngineClient`, `CartItem`/DTO, `RecommendationWindow`.
- **Stage 2: Валидация по 3 источникам.** ✅ backend-verified (migration V014). `/api/posm/sales`
  (источник №1) + `pos_sales` + Excel-импорт (Apache POI) + `excel_imports/excel_sale_rows` +
  `ReconcileService.ingestLogSale`/`ingestExcelRow`/`decideFromSources` + статус `moderation_required`
  в админке + фронт Reconcile (таб «Ручная проверка» + импорт + колонка источников). C#:
  `SaleReporter` + `OfflineOutbox` (SQLite) + `OutboxFlusher`.
- **Stage 3: 2-й монитор от админки.** `/playlists/active` + SSE Idle/Active/Promo +
  плейлисты из `/api/admin/screens` (готово на backend). CDP-форма телефона (§5.6).
- **Stage 4: prod-харднинг.** ОФД-верификатор, ключ-на-устройство, Polly-политики, телеметрия.

```

```
