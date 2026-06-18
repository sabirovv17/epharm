# Мобильное приложение фармацевта (Flutter)

**Путь:** `lib/` · **Стек:** Flutter · Riverpod 2.6 · go_router 14.6 · http 1.2 ·
flutter_secure_storage 10.3 · camera · image_picker · pinput · flutter_svg · url_launcher.

Приложение фармацевта: вход по телефону/OTP, баланс и каталог промо, загрузка чека (фото →
аптека → карта), реальный каталог товаров, профиль с инфо-страницами.

## Фичи (`lib/features/`)

| Фича             | Экраны                                                   | Назначение                                                                                                 |
| ---------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `auth/`          | phone, otp, profile_form                                 | Вход по OTP + само-регистрация (ФИО + ИИН)                                                                 |
| `home/`          | home + виджеты                                           | Дашборд: карточка баланса (`/api/mobile/me`), промо-карусель, каталог продуктов, фильтры/сортировка/бренды |
| `profile/`       | контроллер баланса                                       | Обновление профиля/баланса (`/api/mobile/me`)                                                              |
| `profile_pages/` | faq, instruction, cooperation, terms, privacy            | Статичные инфо-страницы                                                                                    |
| `receipts/`      | list, review, camera, success, address_sheet, card_sheet | Загрузка чека, выбор аптеки, номер карты (Luhn), история                                                   |
| `catalog/`       | catalog, product_sheet                                   | Реальный каталог (прокси Medusa через backend), поиск                                                      |
| `welcome/`       | welcome                                                  | Стартовый экран                                                                                            |

## Core-инфраструктура (`lib/core/`)

**`config/api_config.dart`**

- `ApiConfig.useApi` — флаг `--dart-define=USE_API` (по умолчанию `true`)
- `ApiConfig.baseUrl` — `--dart-define=API_BASE` (дефолт `https://api.epharm.kz` — **домен пока
  мёртв**, не резолвится на сервер; боевой адрес см. ниже в «Сборке прод-APK»)
- Переключает mock ↔ реальный API

**`network/`**

- `api_client.dart` — клиент на `package:http`: `getJson`, `postJson`, `postMultipart`; инъекция
  Bearer-токена; на 401 авто-refresh (`/api/mobile/auth/refresh`) + один ретрай; парсинг `{code,message}`
- `api_exception.dart` — `ApiException` (message, code, statusCode)
- `token_store.dart` — `TokenStore`: хранит токены в `flutter_secure_storage` (Keychain /
  EncryptedSharedPreferences), in-memory кэш + write-through, безопасен в юнит-тестах

**`router/app_router.dart`** — go_router: `/welcome`, `/auth/{phone,otp,profile}`, `/home`,
`/catalog`, `/profile/*`. Redirect: залогиненный → home; гард на auth-маршруты; слушает
`currentUserProvider` для восстановления сессии.

**`theme/`** — дизайн-токены (`app_colors`, `app_typography` (Manrope 500–800), `app_theme`
(Material 3, light), `app_gradients`, `app_spacing`, `app_radii`, `app_shadows`). Fallback-шрифты
для глифа ₸ (Roboto/SF Pro).

**`validation/`** — `card.dart` (Luhn для 16-значной карты), `iin.dart` (ИИН Казахстана).

**`widgets/`** — `error_snackbar` (`messageFromError`, `showErrorSnackBar`), `pharma_logo`,
`primary_button`, `glass_pill`, `filter_chip_row`, `search_input`, `brand_icons`, `receipt_stamp_mark`.

## Data-слой: mock vs API

Каждая фича — Strategy-паттерн с переключением по флагу `USE_API`:

```
feature/data/
  ├── {feature}_repository.dart       # абстрактный интерфейс
  ├── api_{feature}_repository.dart    # реализация через ApiClient (backend)
  ├── mock_{feature}_repository.dart   # хардкод-данные (offline-демо)
  └── models.dart
```

Выбор репозитория — Riverpod-провайдер:

```dart
final authRepositoryProvider = Provider<AuthRepository>((ref) =>
  ApiConfig.useApi
    ? ApiAuthRepository(ref.read(apiClientProvider), ref.read(tokenStoreProvider))
    : MockAuthRepository());
```

| Репозиторий | API-эндпоинт                                 | Mock                                |
| ----------- | -------------------------------------------- | ----------------------------------- |
| `Auth`      | `/api/mobile/auth/*`                         | OTP=`544544`                        |
| `Receipt`   | `/api/mobile/receipts` (multipart + история) | in-memory статусы                   |
| `Pharmacy`  | `/api/mobile/pharmacies`                     | ~20 фейков (реально ~523 из Medusa) |
| `Catalog`   | `/api/mobile/catalog/*`                      | stub                                |
| `Me`        | `/api/mobile/me`                             | —                                   |

## main.dart / bootstrap

- `runZonedGuarded` — глобальный перехват необработанных ошибок (логирует, UI не подменяет)
- `FlutterError.onError` — ошибки фреймворка в лог (не фатально)
- `_restoreSession` — восстановление сессии на старте: токены из `TokenStore` (timeout 3с) →
  `/api/mobile/me` (timeout 6с); ошибки молча → welcome
- `UncontrolledProviderScope` + portrait-only

## Сборка и платформы

**Android** (`android/app/build.gradle`, `AndroidManifest.xml`)

- `minSdk = 23` (требование `flutter_secure_storage`; дефолтный 21 ронял release-сборку)
- Разрешения: `CAMERA` (нет `RECORD_AUDIO`); cleartext только для dev/localhost, прод — HTTPS
- Release-подпись из `android/key.properties`

**iOS** (`ios/Runner/Info.plist`)

- `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`
- **`FLTEnableImpeller = false`** — форсит рендерер Skia (Impeller давал белый экран на iPhone)
- `NSAppTransportSecurity`: HTTPS по умолчанию, исключение для localhost (dev)

**dart-define** (через `builds/build_all.sh`): `USE_API=true`, `API_BASE` (дефолт скрипта —
`https://api.epharm.kz`).

> ⚠️ **Боевой backend сейчас — `https://epharm.78-140-246-238.sslip.io`** (через Caddy/TLS).
> Дефолтный `api.epharm.kz` **не резолвится** на сервер (health `000`) — APK, собранный без
> переопределения, до бэка не достучится. Для боевой сборки обязательно задавай реальный адрес:
> `build_all.sh` читает его из переменной окружения `API_BASE`, а голый `flutter build` — из
> `--dart-define=API_BASE`.

**Сборка прод-APK:**

```bash
cd <repo>
# Боевой адрес ОБЯЗАТЕЛЕН (иначе уйдёт на мёртвый api.epharm.kz):
API_BASE=https://epharm.78-140-246-238.sslip.io bash builds/build_all.sh
# результат: builds/Epharm-v<version>-release.apk (~49 МБ, USE_API=true, боевой base URL)

# Голый flutter build (без скрипта) — тот же адрес через --dart-define:
flutter build apk --release \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
```

APK раздаётся как `https://epharm.78-140-246-238.sslip.io/s3/epharm-receipts/epharm-demo.apk`.

## Запуск в dev

```bash
flutter run \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=http://10.0.2.2:8080   # Android-эмулятор → localhost хоста
# вход: телефон → OTP 544544 (dev) → Home (баланс из бэка)
```

## Ключевые решения

1. Без кодогенерации (нет freezed/build_runner) — простота на этапе bootstrap.
2. `http` вместо Dio — легче + встроенный `MockClient` для тестов + свой auto-refresh.
3. Persist токенов + восстановление сессии — авто-логин после рестарта.
4. Mock-first: флаг `USE_API` даёт offline-демо без бэкенда.
5. Дизайн-токены централизованы — никаких хардкод-цветов в виджетах.
6. Impeller выключен на iOS — обход бага с белым экраном.
