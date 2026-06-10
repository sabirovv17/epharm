# POSM-клиент для кассы (C# / WPF)

**Путь:** `App/` (+ общие DTO в `Models/`) · **Стек:** .NET 10 (`net10.0-windows`), WPF,
LibVLCSharp (видео), Microsoft.Data.Sqlite (offline-очередь). Self-contained win-x64.

POSM (Point-Of-Sale Management) — **sidecar-приложение**, которое крутится на кассовом ПК рядом
с аптечной программой «Стандарт-Н» и делает четыре вещи: подсказывает фармацевту замену/допродажу,
крутит рекламу на клиентском мониторе, регистрирует клиента в программе лояльности, и сам себя
обновляет.

## Проект

- **`App/CustomerDisplay.csproj`** — `net10.0-windows`, `UseWPF`, `UseWindowsForms`, RID `win-x64`,
  `Version` (бампается на каждый релиз для сравнения апдейтером).
- Включает `Models/**/*.cs` явно (`<Compile Include="..\Models\**\*.cs" />`) — общие DTO лежат вне `App/`.
- Зависимости: `LibVLCSharp.WPF` 3.9.5 + `VideoLAN.LibVLC.Windows` 3.0.23 (видео), `Microsoft.Data.Sqlite` 9.0 (outbox).

## Исходники

**UI (`App/*.xaml.cs`)**
| Файл | Назначение |
|---|---|
| `App.xaml.cs` | Жизненный цикл; single-instance (Mutex); CrashGuard (перехват всех исключений) |
| `MainWindow.xaml.cs` | Главное окно; чтение лога кассы `zkassa.log` (CP1251) tail-циклом; позиционирование на мониторы; горячие клавиши (`Ctrl+Shift+Q` — выход) |
| `MainWindow.Recommendations.cs` | Логика попапа: `OnCartChanged` (debounce), показ только на экране фармацевта, запись исхода |
| `MainWindow.Screen.cs` | Клиентский экран: VLC-видео по кругу, polling плейлиста, watchdog зависшего видео, `RewriteMediaHost` (localhost→backend для MinIO-URL) |
| `MainWindow.Update.cs` | Авто-апдейт: первый чек через 15с, далее каждые `UpdatePollSec` |
| `RecommendationWindow.xaml.cs` | Карточка-попап: табы **Замена \| Допродажа**, сравнение, бонус, скрипт; `F9`=принять, `Esc`=пропустить; авто-закрытие 30с |
| `CdpForm.xaml.cs` | Лояльность: поиск по телефону (debounce 4+ цифр), регистрация нового клиента |

**Сервисы (`Services/`)**
| Файл | Назначение |
|---|---|
| `EpharmApiClient.cs` | HTTP к бэкенду, все вызовы fail-safe (возвращают null/false, не кидают). Auth — `X-Posm-Key`. Таймаут 700мс (recommend), 10мин (download) |
| `AppUpdater.cs` | Сравнивает версию, качает zip, **обязательно** проверяет SHA256, распаковывает, запускает `apply-update.cmd`, выходит. Только HTTPS (или localhost dev) |
| `CheckoutSession.cs` | Контекст чека (`SessionId`), маппинг `ReceiptItem`→`RecommendRequest` |
| `SaleReporter.cs` | Формирует `SaleReport` (источник №1 сверки), кладёт в outbox |
| `OfflineOutbox.cs` | SQLite-очередь гарантированной доставки (`C:\Epharm\outbox.db`): enqueue/dequeue/reschedule (экспоненциальный backoff) |
| `OutboxFlusher.cs` | Фоновый воркер (каждые 5с): шлёт продажи/исходы, успех→remove, ошибка→reschedule |
| `EpharmConfig.cs` | Конфиг из `C:\Epharm\posm.json` (+ env `EPHARM_*`). Включается только если заданы `PharmacistId` и `PharmacyId` |

**DTO (`Models/Posm/`)** — `PosmDtos.cs` (`RecommendRequest/Response`, `Recommendation` с
`kind=substitution|crosssell`, сравнением, бонусом; `OutcomeRequest`, `CartItem`, `ComparisonRow`),
`Cdp.cs`, `Playlist.cs` (`ActiveSlide`, `ActivePlaylist`), `AppVersion.cs`, `SaleReport.cs`;
`Models/ReceiptItem.cs` (позиция чека).

## Что делает функционально

**A. Рекомендации (ТЗ §4)** — читает `zkassa.log` в реальном времени, собирает корзину, шлёт
debounce-запрос `POST /api/posm/recommend`. Бэкенд возвращает до 2 карточек (замена + допродажа).
Попап показывается **только на экране фармацевта**: сравнение товаров, бонус, скрипт, кнопки
(`F9`=заменить). Исход → `POST /api/posm/recommendations/{eventId}/outcome` (при сбое — в outbox).

**B. Клиентский экран (второй монитор)** — VLC крутит локальное видео или плейлист с бэкенда
(`GET /api/posm/playlists/active` каждые ~120с). Переключение только при смене сигнатуры плейлиста
(без дёргания видео). Watchdog перезапускает зависшее видео.

**C. CDP / лояльность (§5.6)** — форма поиска клиента по телефону (`/api/posm/cdp/lookup`) и
регистрации (`/api/posm/cdp/register`).

**D. Авто-апдейт без простоя** — `GET /api/posm/app/version?platform=win-x64` каждые 30мин;
если версия новее — скачивание + **обязательная** проверка SHA256 + внешний `apply-update.cmd`
(ждёт выхода exe → robocopy → перезапуск). Все кассы обновляются сами.

**E. Offline-устойчивость** — любая исходящая запись (продажа/исход) сперва в SQLite-outbox,
фоновый flusher шлёт с экспоненциальным backoff; идемпотентность по GUID. Переживает обрыв сети.

**F. Живучесть** — CrashGuard (внутри) + внешний watchdog по heartbeat-файлу + автозапуск через
Task Scheduler (ONLOGON). Переживает падения, дедлоки, перезагрузки.

## Интеграция с бэкендом

Все вызовы — base `BackendBaseUrl`, заголовок `X-Posm-Key` (device-key из `posm.json`):

| Эндпоинт                                           | Назначение                    |
| -------------------------------------------------- | ----------------------------- |
| `POST /api/posm/recommend`                         | рекомендации по корзине       |
| `POST /api/posm/recommendations/{eventId}/outcome` | исход (accepted/rejected)     |
| `POST /api/posm/sales`                             | лог продажи (источник сверки) |
| `GET /api/posm/playlists/active?pharmacyId=`       | активный плейлист             |
| `GET /api/posm/app/version?platform=win-x64`       | версия для апдейта            |
| `POST /api/posm/cdp/{lookup,register}`             | лояльность                    |

## Деплой на кассу

Подробно — `App/POSM_DEPLOY.md` и `App/WINDOWS_RUNBOOK.md`. Кратко:

```powershell
# 1. Публикация (именно publish — апдейтеру нужен exe, не dotnet.exe)
dotnet publish App\CustomerDisplay.csproj -c Release -r win-x64 --self-contained -p:Version=1.0.0 -o C:\epharm\app

# 2. Конфиг C:\Epharm\posm.json
#    { "Enabled": true, "BackendBaseUrl": "https://api.epharm.kz",
#      "DeviceKey": "<ключ кассы>", "PharmacyId": "ph_017", ... }

# 3. Автозапуск (ONLOGON + watchdog по heartbeat)
powershell -ExecutionPolicy Bypass -File install-tasks.ps1 -InstallDir C:\epharm\app
```

**Выпуск нового релиза (авто-апдейт всех касс):**

```powershell
# zip публикации → SHA256:
(Get-FileHash .\epharm-1.1.0.zip -Algorithm SHA256).Hash
# регистрируем релиз через admin API:
curl -X POST https://api.epharm.kz/api/admin/app-releases -H "Authorization: Bearer <admin-jwt>" \
  -d '{"version":"1.1.0","url":"https://s3.epharm.kz/app/epharm-1.1.0.zip","sha256":"<hash>","platform":"win-x64","mandatory":false}'
# кассы подхватят в течение UpdatePollSec (30 мин). Откат = заново зарегистрировать прошлую версию.
```

> **Почему HTTP-polling, а не WebSocket:** контент экрана меняется редко и не критичен к задержке
> (1 мин ок); polling устойчив к обрывам сети (каждый запрос независим), не требует серверного push.
> Бесперебойность даёт автозапуск + single-instance + fail-safe, а не постоянный сокет.
