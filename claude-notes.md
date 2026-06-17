# Epharm — заметки для контекста

> **Читать этот файл первым в каждой новой сессии.** Содержит сжатое описание проекта, статус и план — чтобы не тратить токены на повторное изучение HTML-прототипа и ТЗ.

## 🎓 РОЛЬ: ОПЫТНЫЙ SENIOR-РАЗРАБОТЧИК (закреплено 2026-05-29)

**Подход к КАЖДОЙ задаче в этом проекте — без исключений, без сокращений.**

Применяется и к Flutter mobile, и к admin-panel React, и к Kotlin backend, и к POSM Electron — везде где пишу код.

### 1. Reproduction-first

- На bug или новую фичу — сначала **failing test**, потом код.
- Тест должен падать по той причине, которую ты диагностировал. Если падает по другой — гипотеза неверна.
- Sanity-control: рядом тест что корректное поведение продолжает работать (защита от over-shoot фикса).

### 2. Root cause через факты

- `grep`, `find`, чтение файлов, stack-trace, логи — **факты**, не интуиция.
- Не «попробую так, может сработает». Сначала гипотеза → reproduction-тест → подтверждение.
- Никаких «слепых правок». Каждое изменение отвечает на вопрос «какой симптом оно лечит и почему».

### 3. Минимальные изменения

- Один баг = один сфокусированный фикс. Без попутного рефакторинга «раз уж тут».
- Composition: один валидатор переиспользуется во всех точках входа, не дрейфит.

### 4. Тестирование — ВСЕГО что пишу

- **3 типа тестов** обязательно: unit (pure logic), integration (с реальным репозиторием/Riverpod-контейнером/Spring-контекстом), smoke (рендер без падений).
- Каждый новый виджет / провайдер / репозиторий / контроллер / endpoint = тест в том же commit'е.
- Каждый bug-fix = regression test, чтобы баг не вернулся.
- `flutter test` / `npm test` / `./gradlew test` после **каждого** touch'а — не «в конце», не «когда вспомнил».
- Если красное — не двигаюсь дальше. Чиним. Не `skip:`, не `it.skip`.

### 5. Машинно-читаемые ошибки

- Backend бросает `AppException(ErrorCode.XXX, ...)`. Frontend switch'ит по коду.
- Сообщения с конкретикой (`"recommend=$id"`, `"phone format invalid"`) — чтобы дебаг был очевидным.

### 6. Документировать на ходу

- После каждого нетривиального решения — запись в `claude-notes.md` (mobile) или `admin-panel/claude-admin-notes.md` (admin): gotcha, fix, regression test.
- Pattern «домен → бэкенд → фронт» (Этап 3.2 admin) занял 1.5 дня в первый раз; Promo (3.3) по тому же checklist'у — 3 часа. **Notes — экономия времени, не накладные расходы.**

### 7. Контракт-консистентность

- Frontend type строго зеркалит backend DTO. Любое расхождение — баг.
- API endpoints per ТЗ: REST, kebab-case, plural resources.
- Status enum — strict через CHECK constraint в БД + Kotlin/Dart enum + frontend type union.

### 8. Защита API через service layer

- DTO-валидация ловит синтаксис. Бизнес-правила — в service: shape validation, self-reference, status transitions, FK existence.
- PATCH-эндпоинты не должны позволять то, для чего есть dedicated endpoint (silent state changes — потеря audit-event'ов).

### 9. Архитектурные anti-patterns которых НЕ делаю

- Async hydration sync state: токены из localStorage в useEffect → редирект на /login срабатывает раньше. **Правильно:** synchronous initial-state factory.
- `.filter(Boolean)` на лету в controlled textarea: юзер вводит, видит другое. **Правильно:** raw input, sanitize только в save.
- `role="switch"` на `<span>` — не focusable. **Правильно:** button.
- В Flutter: `setState` в `build()`, mutable global state без Riverpod scope, await'ы без `mounted` guard.

### 10. Что считается «готово»

- ✅ Failing test был → теперь passes.
- ✅ Sanity tests не сломались.
- ✅ Full suite зелёный (`flutter test`, `npm test`, `./gradlew test`).
- ✅ Build clean (`flutter build`, `npm run build`, `./gradlew build`).
- ✅ Notes обновлены.

Без всех 5 пунктов — не «готово».

**Этот подход — закреплённое правило, не предложение. Применяется ко всему коду в проекте: новым фичам, багфиксам, рефакторингу, миграциям БД, инфраструктуре, UI-полировке.**

---

## Что строим

Кроссплатформенное (iOS-first) приложение для фармацевтов на Flutter.

- **Цель**: фармацевты получают бонусы за переключение клиентов на промо-SKU, проходят обучение и AI-экзамены, отслеживают выплаты.
- **Бренд vs код**: брендовое имя в UI/сторах — **Epharm** (ребрендинг 2026-05-26 с «PharmaPay»). Код-имя (`pubspec.yaml`, bundle ID, applicationId) — **pharmacy** / `kz.pharmacy.app`. Был ребрендинг с `kz.pharmapay.pharmapay` чтобы свежие сборки не конфликтовали с старыми на одном устройстве (iOS уникально идентифицирует по bundle ID). **Wordmark**: «E» в `brandBlue600` (короткая монограмма-акцент) + «pharm» в основном цвете (white на header) — тот же визуальный паттерн что был у «PharmaPay» (короткая часть = blue accent). Класс `PharmaWordmark` оставлен как code identifier — не переименовывали (внутреннее имя ≠ brand). Папки `_reference/design_handoff_pharmapay/` и файлы `_reference/PharmaPay.html` / `PharmaPay Logo.html` сохранили исторические имена — это эталонные дизайн-документы.
- **UI-эталон**: `_reference/design_handoff_pharmapay/prototypes/PharmaPay.html` (живой прототип) + `prototypes/src/*.jsx` (исходники)
- **Дизайн-токены (каноничные)**: `_reference/design-tokens.md` (765 строк, §1–§11 включая admin console и receipt-flow)
- **Дизайн-токены (вторичные, поддерживаются параллельно)**: `_reference/design_handoff_pharmapay/design-tokens.md`
- **Receipt-flow JSX-эталон**: `_reference/recipe/{upload,review}.jsx` + `README.md`
- **Profile-pages JSX-эталон**: `_reference/profile-pages/`
- **Шрифты подробно**: `_reference/Fonts.md`
- **Логотип**: `_reference/PharmaPay Logo.html` — Receipt Stamp с медицинским крестом. Применён как app icon (green variant, см. ниже)

## Стек

- Flutter 3.27 (Dart 3.6), iOS+Android, MVP запускается на iOS-симуляторе Xcode (iPhone 17, iOS 26.5)
- **State**: Riverpod (NotifierProvider, AsyncNotifier)
- **Навигация**: go_router с auth-aware redirect
- **Шрифт**: Manrope (variable) бандлится локально в `assets/fonts/Manrope/Manrope-Variable.ttf`. `google_fonts` НЕ используем — runtime-download падал на Android-эмуляторе (DNS изолирован).
- **Иконки**: CustomPainter для бренд-иконок (Coin, GiftEmoji, TrophyEmoji, PharmGlyph, ReceiptStampMark) + flutter_svg на будущее
- **Спецвиджеты**: `mask_text_input_formatter` (телефон), `pinput` (OTP-боксы), `url_launcher` (внешние ссылки YouTube / WhatsApp)
- **Backend**: пока mock через repository pattern. Замена data/-слоя на реальный API позже.

## Архитектура

Feature-based clean architecture. Слои: `presentation` → `application` (Riverpod) → `domain` → `data`.

```
lib/
├── main.dart
├── app.dart                          # MaterialApp.router + ProviderScope + theme + system overlay
├── core/
│   ├── theme/                        # app_colors, app_typography, app_radii, app_shadows, app_spacing, app_gradients, app_theme
│   ├── router/app_router.dart        # go_router: /welcome, /auth/{phone,otp,profile}, /home (опционально authed)
│   └── widgets/                      # PharmaWordmark, GlassPill, BrandIcons (Coin/Gift/Trophy/Pharm), ReceiptStampMark, SearchInput, FilterChipRow
├── features/
│   ├── welcome/presentation/         # WelcomeScreen с peeking phone-previews (3 слайда)
│   ├── auth/
│   │   ├── data/auth_repository.dart # mock SMS + IIN валидация
│   │   ├── domain/user.dart
│   │   ├── application/auth_controller.dart  # currentUserProvider, authDraftProvider, authActionsProvider
│   │   └── presentation/{phone,otp,profile_form}_screen.dart
│   ├── home/
│   │   ├── data/home_repository.dart # PROMOS, PRODUCTS, BRANDS (моки из data.jsx)
│   │   ├── application/home_controller.dart
│   │   └── presentation/
│   │       ├── home_screen.dart      # 2-tab switch (Home/Профиль) + центральный FAB-камера; header — часть скролла
│   │       └── widgets/{balance_card, promo_carousel, product_card, package_mock, bottom_navigation, profile_row, learning_stub_screen, contests_stub_screen, ...}.dart
│   ├── receipts/                     # ВЕСЬ flow «загрузил → отправил»
│   │   ├── data/{receipt_repository, nearby_pharmacies}.dart   # Receipt + 4 статуса + 8 моков аптек
│   │   ├── application/receipts_controller.dart                # ReceiptDraft (promos/pharmacy/card) + Notifier + StreamProvider
│   │   └── presentation/
│   │       ├── upload_prompt_sheet.dart       # bottom-sheet: Сделать фото / Из галереи / Сканировать QR
│   │       ├── camera_screen.dart             # реальная камера + flash + front/back
│   │       ├── receipt_review_screen.dart     # 3-row checklist (акции/аптека/карта)
│   │       ├── promo_picker_screen.dart       # каталог с toggle Добавить/Добавлено
│   │       ├── address_sheet.dart             # pharmacy picker с geolocation-banner
│   │       ├── card_sheet.dart                # bonus-card preview + 3×4 numeric keypad
│   │       ├── success_screen.dart            # синий disc + 2 CTA
│   │       └── receipts_list_screen.dart      # история со статусами (push-route)
│   └── profile_pages/                # FAQ + Instruction + Cooperation + Terms + Privacy
└── shared/                           # пусто пока
```

## Реализовано (golden path работает)

- [x] Дизайн-система (`core/theme/*`) с токенами из design-tokens.md
- [x] go_router + auth-aware redirect (Home доступен без логина)
- [x] **Welcome** — 3 слайда с peeking phone-previews (PreviewHome/Camera/Success)
- [x] **Auth**: phone (маска `(XXX) XXX-XX-XX`) → OTP (6 боксов 48×60, auto-fill `544544`, countdown 1:59→0:00) → profile (ФИО+ИИН с правильной кириллицей на Android, цифры-only в ИИН)
- [x] **Home tab**:
  - Зелёный header с PharmaWordmark + balance card / welcome-gate (по `authed`)
  - PromoCarousel: Info + Huggies + Kotex (полные дизайн-варианты)
  - SearchInput + Sort-circle + Brand-pill + chips Все / Новинки / Конкурсные
  - BigProductCard (featured) с TierLadder + GiftEmoji маркеры
  - 2-col grid SmallProductCard (Period pill, NEW/Trophy badges, restriction)
  - PackageMock (medicine box с side-ribbon, watermark, license номер)
- [x] **BottomNav: 2 таба + центральный FAB-камера, icon-only, compact** (2026-05-26):
  - Левый таб (домик) / правый таб (человечек) — **без текстовых лейблов** (icon-only nav). Семантика осталась через `Semantics.label` для screen-reader'ов.
  - Размер иконок 34 px (раньше 28 px + label 12 px — после удаления лейбла размер бампнули для визуального веса)
  - Active иконка = `*_rounded` filled (`Icons.home_rounded` / `Icons.person_rounded`); inactive = `*_outlined` (тонкий контур). Active цвет = `brandGreen600`, inactive = `ink400`.
  - Центр — зелёный круглый FAB 64×64 с иконкой камеры, приподнят на –24 px над панелью, белый ring 4 px + `shadow/fab` glow
  - FAB → `showUploadPromptSheet(context)` (то же что и pill «Загрузить чек» в BalanceCard — primary action фармацевта)
  - **Compact padding**: container top 6 / bottom 8 (раньше 10/28); inner SizedBox.height 48 (раньше 56). Раньше шторка занимала ~84 px без SafeArea + ~34 px home-indicator на iOS = очень толстая полоса. Сейчас ~62 px + SafeArea — стройная.
  - История: 4 таба (Чек удалён 2026-05) → 3 таба (Обучение перенесён в Profile 2026-05-26) → 2 таба + FAB → icon-only + compact (2026-05-26). Каждая итерация — ответ на feedback «слишком много / неравное распределение / слишком толсто».
- [x] **Profile tab**:
  - Header с welcome-gate / аватар+ФИО+телефон+glass-pills (История/Конкурсы)
  - Секция «Помощь»: **Обучение** (school-иконка, push на `LearningStubScreen` — добавлено 2026-05-26), WhatsApp поддержка, Вопросы и ответы, Подробная инструкция, Сотрудничество
  - Секция «О приложении»: Пользовательское соглашение, Политика конфиденциальности
  - Красная кнопка «Выйти» (для authed) + footer «Epharm · v 1.0.4 (2026)»
- [x] **Home — scroll-as-one-flow (2026-05-26)**: зелёная шапка теперь часть `CustomScrollView` (первый `SliverToBoxAdapter`), а не sticky-`Positioned`. Раньше при скролле header висел поверх, контент уезжал под него — UX путаный на short-screen Android'ах. Сейчас весь экран скроллится единым потоком: header → промо → search → фильтры → товары. Промо-карусель полностью видна (без `Transform.translate(-24)`).
  - **Sort sheet** (круглая кнопка ↕): 4 опции — «Сначала новые акции» (дефолт), «По названию (А-Я)», «По типу акции (по возрастанию/убыванию)». Radio-кружок зелёный ring 2px + 10×10 fill в активной строке, divider `paperInput` между опциями. Подсвечивает chip-кнопку зелёным если выбран не дефолт.
  - **Brand sheet** (chip «Бренд»): bottom-sheet 85% высоты с поиском по бренду + чекбоксами. Курированный список 22 KZ pharma-брендов в `kKzPharmaBrands`. Кнопка «Применить (N)» / «Сбросить».
  - **Search-input** связан с `searchQueryProvider` — фильтрует по name + brand product'ов.
  - Полный фильтр-pipeline в `applyHomeFilters` (chip → brands → query → sort).
- [x] **Profile sub-pages** — все экраны кнопок в Профиле:
  - `FaqScreen` — аккордеон с `+` ↔ `×` (поворот -45°, 280 мс), inline `[link]/**bold**` маркеры
    - **VideoInstructionCard** в футере: 16:9 синий gradient с YouTube-style плеером, открывает `https://youtu.be/2ax0jHFhM-A` через `url_launcher` (external app или браузер)
  - `InstructionScreen` — 5-шаговая карусель с peeking phone-mockups и gradient pager card
  - `CooperationScreen` — 3 gradient-карточки (аптеки/производители/дистрибьюторы) + 3 contact row + CTA
  - `TermsScreen` / `PrivacyScreen` — длинные юр-документы через `LongDocScreen` (h2 / p / li / def блоки)
- [x] **Кнопки на главной/профиле — рабочие**:
  - `BalanceCard` «История» → push на `ReceiptsListScreen` (с auth-guard).
  - `BalanceCard` «Загрузить чек» → `showUploadPromptSheet(context)`.
  - **Центральный FAB-камера** в bottom-nav → тот же `showUploadPromptSheet(context)` (дублирующий entry-point для primary action).
  - `_AuthedHeader` (Profile) «История» → push на `ReceiptsListScreen` (тот же callback).
  - `_AuthedHeader` «Конкурсы» → push `ContestsStubScreen`.
  - Profile menu «Обучение» (school-иконка) → push `LearningStubScreen`.
  - Архитектура: `_HomeScreenState._openReceiptsHistory()` пробрасывается в `_HomeTab` → `_Header` → `BalanceCard.onHistory` и параллельно в `_ProfileTab` → `_AuthedHeader.onHistoryTap`. FAB в bottom-nav получает `onScanTap` отдельно (не зависит от `_tab`).
- [x] **Product Detail bottom sheet**:
  - `lib/features/home/presentation/widgets/product_detail_sheet.dart` — `showProductDetailSheet(context, product)`.
  - DraggableScrollableSheet 50-95%, paperCanvas фон, drag-handle, sticky bottom CTA «Загрузить чек» (snackbar пока).
  - Hero блок: gradient `pkg.background` + PackageMock + бейджи NEW/КОНКУРС.
  - Body: brand-pill + название (22/800) + sub + TierLadder + Bonuses bullet-list + Contest-баннер (gold gradient) + Restrictions info-box (blue-100) + Period line.
  - Привязано к `onTap` BigProductCard и SmallProductCard в home_screen.
- [x] **Receipts — полный flow (Этапы 1+3+Recipe-integration 2026-05)**:
  - `lib/features/receipts/` — feature: `data/{receipt_repository, nearby_pharmacies}.dart`, `application/receipts_controller.dart` (StreamProvider + `ReceiptDraftNotifier`).
  - **4 статуса чека**: `awaitingReceipt` / `inReview` / `confirmed` / `rejected`. Бейджи: зелёный / янтарный / серый / красный dashed.
  - **Точки входа**: Чек-таб **удалён**. Единственный entry → pill «Загрузить чек» в `BalanceCard` (authed Home). История чеков (`ReceiptsListScreen`) — pill «История» в BalanceCard / Profile, push-route с back-кнопкой.
  - **UploadPromptSheet** — bottom-sheet с зелёным `grad-welcome`. 3 опции: Сделать фото / Из галереи / Сканировать QR (QR snackbar-stub).
  - **CameraScreen** — реальная камера (`camera: ^0.11.0`), FittedBox+CameraPreview, dashed viewfinder + затемнение, top-bar (close + flash cycle), bottom-bar (shutter 76 + switch front/back). Возвращает path через `Navigator.pop`.
  - **ReceiptReviewScreen — 3-row checklist** (переписан 2026-05 с OCR-формы): чек уже принят (banner «Чек загружен» + thumbnail), фармацевт явно заполняет:
    1. «Добавить акции» → `PromoPickerScreen`
    2. «Добавить адрес аптеки» → `AddressSheet`
    3. «Добавить номер карты» → `CardSheet`
       Row filled = `brand-green-100 + green-200 border + green-600 icon-tile с shadow/fab + white check`. Row empty = `white + green-100 icon-tile + green-700 glyph`. Sticky CTA «Продолжить» активна когда все 3 filled.
  - **PromoPickerScreen** — каталог с inline toggle «Добавить / Добавлено» (green-100/600), 2x grid, sticky CTA «Добавить · N» / «Выберите акции».
  - **AddressSheet** — bottom-sheet с geolocation-banner («Рядом с вами · 8 аптек поблизости»), search, список аптек с chain-coloured tile + distance-pill (green-100/green-700). Mock: `nearby_pharmacies.dart` (8 Алматы аптек).
  - **CardSheet** — bottom-sheet с card-preview (gradient `#16A65C → #21D17A → #3DCDA2` + золотой чип + masked digits), number field (n/16 counter), 3×4 numeric keypad (digits = `paper-input` `ink-900` 24/800; backspace = `brand-green-600` + `shadow/fab` — единственная action-кнопка против 10 grey digit keys).
  - **SuccessScreen** — `grad-welcome` фон + синий disc 120px + белая карточка «Чек успешно отправлен» + 2 CTA: «История и статусы» (popUntil to root + push `ReceiptsListScreen`) / «Отправить ещё раз» (popUntil + `showUploadPromptSheet`). Полный wiring добавлен в Этапе 4.6 (2026-05-26).
  - **ReceiptDraft schema (Riverpod NotifierProvider)**: `{photoPath, promos: List<Product>, pharmacy: NearbyPharmacy?, card: String?}`. `isComplete = hasPromos && hasPharmacy && hasCard`. `reset(keepCard: true)` — обнуляет всё кроме card (она persists между submission'ами в сессии — как в JS-эталоне).
  - Submit flow: `repo.addReceipt(...)` (статус `inReview`, дата=now, title=first promo name, pharmacy=draft.pharmacy.name) → `repo.changes` стрим → StreamProvider перезаливает список → `reset(keepCard:true)` → `pushReplacement(SuccessScreen)`.
  - Permissions: `NSCameraUsageDescription` / `NSPhotoLibraryUsageDescription` в `Info.plist`; `android.permission.CAMERA` в `AndroidManifest.xml`.
  - Эталон → Dart маппинг описан в `_reference/design-tokens.md §11`.
- [x] **Кросс-платформа**: тенге `₸` рендерится через `fontFamilyFallback: [Roboto, sans-serif, SF Pro Text]` (Manrope не имеет глифа U+20B8)
- [x] **iOS-симулятор + Android-эмулятор запускаются** (iPhone 17, iOS 26.5; SDK gphone64 arm64)
- [x] `flutter analyze` — No issues

## iOS codesign fix (ВАЖНО — без этого билд падает)

**Проблема**: проект лежит на Desktop, который синхронизируется iCloud Drive. macOS 15+ добавляет xattrs `com.apple.FinderInfo` + `com.apple.fileprovider.fpfs#P` к свежесоздаваемым папкам внутри iCloud-зоны. Flutter's `_signFramework` вызывает `codesign --force --sign -` который треактует Flutter.framework как bundle и при sealed-resources проверяет xattrs → "resource fork, Finder information, or similar detritus not allowed".

**Решение** (применено, не трогать):

1. `/tmp/codesign_shim/codesign` — shim, который вместо `codesign` запускает `codesign --no-strict $@`. ВАЖНО: shim в `/tmp/` обнуляется при перезагрузке Mac.
2. `ios/fix_build.sh` — wrapper, который ставит `PATH=/tmp/codesign_shim:$PATH` перед вызовом `xcode_backend.sh build`. Это нужно чтобы Dart's ProcessManager нашёл наш shim вместо системного codesign.
3. `ios/Runner.xcodeproj/project.pbxproj` — изменён shellScript "Run Script" фаза: `/bin/sh "$SRCROOT/fix_build.sh"` (вместо прямого вызова xcode_backend.sh).
4. В Debug-конфиге Runner target: `OTHER_CODE_SIGN_FLAGS = "--no-strict"` — для самого Runner.app (Xcode-side codesign).
5. `ios/Podfile` `post_install`: `config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'` для Pod-зависимостей.

**После перезагрузки Mac воссоздать shim**:

```bash
mkdir -p /tmp/codesign_shim
cat > /tmp/codesign_shim/codesign << 'EOF'
#!/bin/sh
exec /usr/bin/codesign --no-strict "$@"
EOF
chmod +x /tmp/codesign_shim/codesign
```

## Команды

```bash
export PATH="$HOME/development/flutter/bin:$PATH"
cd /Users/amir/Desktop/work/pharma/PharmaPayV2
flutter run                     # iOS симулятор (по умолчанию)
flutter run -d "iPhone 17"      # явный выбор симулятора
flutter analyze                 # должен быть чистым
flutter clean && flutter run    # при странных багах
```

## 🌐 Запуск против реального backend (API-режим, 2026-06-09)

Приложение умеет ходить в backend по флагу `USE_API` (по умолчанию false → моки сохранены).
Полный стек поднимается так:

```bash
# 1) инфра
cd /Users/amir/Desktop/work/pharma/PharmaPayV2 && docker compose up -d     # postgres:5433 + minio + redis
# 2) backend (отдельное окно; сначала docker healthy!)
cd admin-panel/backend && export JAVA_HOME=/Users/amir/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home && ./gradlew bootRun
# 3) приложение в API-режиме:
export PATH="/tmp/codesign_shim:$HOME/development/flutter/bin:$PATH"
flutter run -d <iphone-id> --dart-define=USE_API=true --dart-define=API_BASE=http://<MAC-LAN-IP>:8080   # физ. iPhone
#   iOS-сим:    API_BASE=http://localhost:8080
#   Android-эм: API_BASE=http://10.0.2.2:8080
# seeded-фармацевт с балансом: curl -X POST localhost:8080/api/admin/dev/reset → логин +7 700 000 0001 (OTP 544544)
```

**🚨 Gotcha — НЕ собирать Flutter кнопкой Run в Xcode напрямую.** Xcode не запускает Flutter-пайплайн
(генерацию артефактов + сборку CocoaPods) → сыплет десятками ошибок «module map ... not found»,
«Unable to resolve module dependency: Flutter/UIKit/Foundation». Правильно: **`flutter run`** (он сам
зовёт xcodebuild с `Runner.xcworkspace`). Если уж через Xcode — открывать **`ios/Runner.xcworkspace`**
(НЕ `.xcodeproj`) и хотя бы раз сделать `flutter run`/`flutter build` до этого. При мусоре в DerivedData:
`rm -rf ~/Library/Developer/Xcode/DerivedData/Runner-* && flutter clean && flutter pub get`.

**Физический iPhone:** `localhost` = сам телефон → `API_BASE` обязан быть LAN-IP Mac'а
(`ipconfig getifaddr en0`), телефон + Mac на одном Wi-Fi, backend слушает `*:8080` (ок).

**🔓 DEV-флаги plain-HTTP (добавлены для теста против http-бэка, в ПРОД убрать — будет HTTPS):**
iOS `Info.plist` → `NSAppTransportSecurity/NSAllowsArbitraryLoads=true`; Android manifest →
`android:usesCleartextTraffic="true"`. Без них iOS/Android молча блокируют http-запросы. Записано в
prod-readiness todo.

**iCloud codesign-shim** (`/tmp/codesign_shim`) слетает после ребута Mac — пересоздать (см. раздел
«iOS codesign fix») перед сборкой на устройство.

## Build артефакты для review

Папка `builds/` хранит готовые APK / IPA / .app.zip для отправки на ревью.

- `builds/README.md` — подробная инструкция (что внутри, как обновить, как установить)
- `builds/build_all.sh` — автоматизация: воссоздаёт codesign shim → `flutter clean` → APK release → iOS .app bundle. Запуск: `bash builds/build_all.sh`.

**Текущая сборка** (v0.1.0+1, Bundle ID **`kz.pharmacy.app`**, app label **«Epharm»**):

- `pharmacy-v0.1.0-release.apk` — Android-APK release, подписан release-keystore (`android/keystore.jks`, alias=pharmacy, **не коммитится**, см. `.gitignore`).
- `pharmacy-v0.1.0-Runner.app.zip` — iOS .app bundle без distribution-подписи, ставится через Xcode → Devices (Personal Team, 7-дневный лимит).
- Был ребрендинг bundle ID `kz.pharmapay.pharmapay → kz.pharmacy.app` чтобы свежие сборки не конфликтовали со старыми установками на симуляторе/устройстве.

**iOS подпись (важно)**: бесплатный Personal Team не подходит для distribution-IPA. Для App Store / TestFlight нужен paid Apple Developer ($99/год). С ним — `flutter build ipa --release` → `build/ios/ipa/*.ipa`.

**`.gitignore`**: добавлено `builds/*.apk`, `builds/*.ipa`, `builds/*.app.zip` — артефакты в git не коммитим, только README + build_all.sh.

## Ключевые соглашения

- **Цвета только через `AppColors.brandGreen600`** (и подобные) — не хардкодить хексы.
- **Async через Riverpod (Async)Notifier**. Контроллеры в `features/<f>/application/`.
- **Mock-репозитории имитируют задержку 300-800мс**.
- **Валидация**: ИИН ровно 12 цифр (digitsOnly formatter); телефон 10 national digits; ФИО ≥ 2 whitespace-токенов; OTP 6 цифр (`544544` дефолт).
- **Шрифт** (см. `_reference/Fonts.md` + `design-tokens.md §2`):
  - Display 32-34 / 800 · H1 26 / 800 · H2 22-24 / 800
  - **List-row title** (Profile rows «Помощь» / «О приложении») = **17 / 800** — особый case плотнее body-strong
  - Body-strong (filter chips, glass-pills, buttons) = 16 / 700
  - Body = 14-16 / **600** (верхняя граница body 500-600)
  - Caption = 12-13 / 600 · Micro (tab labels) = 11-12 / 700
  - Section titles в Profile («Помощь» / «О приложении») = **H1 26/800**, не H2
  - `app_typography.dart` константа `w500` оставлена нетронутой (это имя, не usage)
- **Тенге `₸`**: всегда декларируй `fontFamilyFallback: ['Roboto', 'sans-serif']` (Manrope-Variable.ttf — subsetted, без U+20B8). Глобальный fallback стоит в `app_theme.dart`, но inline TextStyle часто не наследуют его → добавляй явно.
- **Кириллица в TextField на Android**: на каждом `TextField` ставь:
  ```
  autocorrect: false,
  enableSuggestions: false,
  smartDashesType: SmartDashesType.disabled,
  smartQuotesType: SmartQuotesType.disabled,
  style: ...copyWith(fontFamilyFallback: const ['Roboto', 'sans-serif']),
  ```
  Без этого Android IME форсит Latin-словарь и не пропускает / заменяет кириллицу. Применено в `profile_form_screen._WhiteField`, `search_input.dart`, `brand_sheet.dart`.
- **Status bar**: `AnnotatedRegion<SystemUiOverlayStyle>` для белого текста на зелёном header.
- **Без лишних абстракций**: интерфейс repository добавим только когда появится реальный API.
- **Иконки**: использовать декоративные `CoinGlyph`, `GiftEmojiGlyph`, `TrophyEmojiGlyph`, `PharmGlyph` из `core/widgets/brand_icons.dart`. ReceiptStampMark — только для будущего app-icon, в UI шапке только text wordmark.
- **Profile sub-pages** имеют единый канвас `#EFF3FB` (pale lavender) и общий `ProfilePageScaffold` (back + center-title + body). См. design-tokens.md §6.11.
- **Тень `shadow/card`** = `0 1px 3px rgba(0,0,0,0.08) + 0 6px 18px rgba(0,0,0,0.12)` — достаточно плотная, белые карточки чётко стоят на paperCanvas `#F4F6FA`. Изначальная (0.04/0.06) была слишком слабой — карточки сливались с фоном.
- **Тень `shadow/fab`** = `0 10px 24px rgba(22,201,122,0.55)` — зелёное свечение под primary CTA и активным filter chip. Раньше было 0.35 — слишком тускло; усилили до 0.55 для контраста по всему приложению.
- **TierLadder (product cards)**: pills = 72×40 rounded-square (radius 16) **pastel-light** — заливка `brand-green-100` (#E5F8EE) + бордюр 1 px `brand-green-500 @ 35%` + мягкая тень `rgba(brand-green-600, 0.08)`. Текст `brand-green-700` 15/800. Раньше был тёмный gradient `green-500→600` с белым 16/800 — «слишком давило» на белой карточке; перешли на pastel-fill для светлого вида. Pills + labels выровнены **по левому краю** колонки. Gift-маркеры = 36×36 белый кружок с border **`brand-green-500`** 2 px (раньше был `brand-blue-300` из первичного эталона — сменили на зелёный, чтобы обводка совпадала с primary brand-accent; gift-glyph внутри **остаётся синим** — он маркирует «бонус» как secondary accent). Лежат на 8 px track в начале колонок 2 и 3 (НЕ в gap-центрах). См. design-tokens.md §6.8.
- **GlassPill (на зелёном header в Profile/BalanceCard)**: заливка **`brand-green-500 @ 55%` (#21D17A)** + бордюр 1 px `white-40` + soft white halo. Текст white 15/800. Эволюция: 16% white → 40% white → green-500@55% (текущая). Каждый шаг — ответ на feedback: первая была невидна, вторая выглядела беловато, третья даёт явно saturated green pill. Compact padding: pad-x 10, icon-gap 4, fontSize 15 — чтобы длинный label «Загрузить чек» (8 символов после иконки) гарантированно помещался без ellipsis в pill ширины ~170 px (половина экрана при 2-pill row). См. design-tokens.md §6.1 строка «Glass pill».
- **Recipe flow (2026-05) — полностью интегрирован**: `_reference/recipe/{upload,review}.jsx` + README → Flutter. State-machine: `Home (authed) → UploadPrompt sheet → CameraScreen → ReceiptReviewScreen → {PromoPickerScreen, AddressSheet, CardSheet} → SuccessScreen`. ReceiptDraft переработан с OCR-полей (title/pharmacy/cashier/sku/amount) на checklist-схему: `{photoPath, promos: List<Product>, pharmacy: NearbyPharmacy?, card: String?}`. `isComplete = hasPromos && hasPharmacy && hasCard`. `reset(keepCard: true)` оставляет card между submission'ами (как в JS-эталоне). Новые файлы: `data/nearby_pharmacies.dart` (8 мок-аптек Алматы с distance/color), `presentation/{address_sheet, card_sheet, promo_picker_screen, receipt_review_screen}.dart`. CardSheet содержит 3×4 numeric keypad с backspace-кнопкой в правом нижнем (brand-green-600 + shadow/fab — как primary CTA весомость). См. design-tokens §11.
- **Flutter gotcha — yellow-underline bug при tab → push-route**: если экран жил как `body` Scaffold'а в tab-host'е, у него inherited Scaffold/Material → DefaultTextStyle есть. При превращении в push-route своего `Scaffold` нет — все Text-ы получают жёлтый подчёркнутый debug-стиль (никакой не TextStyle, это default `_DefaultStyle` от RootWidget'а). **Правило**: любой push-route обязан возвращать `Scaffold` (или хотя бы `Material`) на самом верху. Ловили на `ReceiptsListScreen` после удаления Чек-таба. См. design-tokens §6.6 «Receipts navigation».
- **Bottom-nav: 4 tabs → 3 tabs → 2 tabs + центральный FAB-камера (2026-05-26)**:
  - **Этап А (2026-05)**: Чек-tab удалён. Загрузка чеков идёт через pill «Загрузить чек» в BalanceCard. История = push-route через pill «История». В `_HomeScreenState` индексы: 0=Home, 1=Обучение, 2=Профиль. `_openReceiptsHistory()` делает `Navigator.push(ReceiptsListScreen)` + guard на login. У `ReceiptsListScreen` добавлен `_HeaderBackButton` (36×36 круглая, white-18 fill + white-40 border + arrow_back glyph) — обязательна после превращения tab → push-route.
  - **Этап Б (2026-05-26)**: Обучение-tab удалён, перенесён первым пунктом в Profile menu «Помощь» (та же `Icons.school_outlined`, push на `LearningStubScreen`). На его место в центре — круглый зелёный FAB-камера 64×64, приподнят на –24 px, белый ring 4 px + `shadow/fab`. FAB → `showUploadPromptSheet(context)` (тот же sheet что у pill «Загрузить чек»). Active-tab цвет сменён с `brandBlue600` на `brandGreen600` для консистентности с brand-accent. PharmaBottomNav теперь принимает 3 параметра: `currentIndex` (0=Home, 1=Profile), `onTap`, `onScanTap`.
  - **Этап В (2026-05-26, же)**: убраны text-лейблы «Главная» / «Профиль» → icon-only nav. Размер иконок 28 → **34 px** для веса. Active = `*_rounded` filled, inactive = `*_outlined`. Semantic label передаётся через `Semantics.label` (для accessibility). Высота шторки тоньше: container padding top 10→6 / bottom 28→8, inner SizedBox.height 56→48 (примерно –40 px суммарно). Раньше bottom 28 + SafeArea-home-indicator давали слишком пустую белую полосу под иконками.
  - См. design-tokens.md §6.6.
- **PharmaWordmark sizes bumped**: md 26 → **32** (default — Home/Receipts header), lg 34 → **38** (Welcome splash / auth screens). Раньше md=26 совпадал по размеру с welcome-heading «Добро пожаловать!» (26/800) — лого и подзаголовок «конкурировали как заголовки». Теперь wordmark визуально доминирует. Параллельно welcome-heading в `home_welcome_gate.dart` снижен с 26/800 → 22/700 для явной иерархии «бренд > подзаголовок». sm=20 не менял.
- **Шрифты «+10% жирнее» (2026-05)**: массовый bump всех font weights на одну ступень — `w500→w600` (2 точки), `w600→w700` (29 точек), `w700→w800` (62 точки). Применено через `sed -i ''` по `lib/` (descending order чтобы избежать double-promotion), плюс правки в `app_typography.dart` helper-функциях (body14/body16/caption: 600→700; bodyStrong/title20/micro/button: 700→800). H1-H3/Display/OTP остались 800 — упёрлись в верх Manrope-variable. Текущее распределение: w800=140, w700=29, w600=2, w500=1. См. design-tokens §2.
- **Search input — белый, не paper-input**: раньше surface был `paper/input` (#F2F4F8), который почти не отличался от `paper/DEFAULT` (#F4F6FA) — поле сливалось с фоном. Сейчас: белая surface + 1px бордюр `ink/300 @ 50%` + `shadow/card` + иконка поиска `ink/500` (раньше ink/400). Читается как полноценный input. См. design-tokens §6.2 «Search input».
- **App icon — Receipt Stamp (GREEN variant)**: master 1024×1024 + iOS набор 15 размеров + Android mipmap (5 плотностей) + adaptive icon (drawable-_ foreground + vector gradient background). Источник: design-tokens §7 + §7.5 GREEN tile. Генератор — `tools/gen_app_icon.py` (master + iOS + Android plain) и `tools/gen_adaptive_icon.py` (Android adaptive foreground). Чтобы пересобрать: `python3 tools/gen_app_icon.py && python3 tools/gen_adaptive_icon.py`. Pillow ≥ 9 нужен (`pip3 install --break-system-packages Pillow`). После изменения дизайна — перегенерь оба скрипта, скопируй из `tools/build_icons/{ios,android,android_adaptive}/` в `ios/Runner/Assets.xcassets/AppIcon.appiconset/` + `android/app/src/main/res/{mipmap-_,drawable-\*}/`.
- **ProfileRow + LogoutButton**: критичный layering-фикс — раньше Material был белым, поверх него Container с тенью, и shadow визуально «затемнял» белую surface через прозрачный Container (карточки выглядели серыми, не белыми). Сейчас наоборот: Container задаёт `color: Colors.white + shadow/card`, Material внутри прозрачный (только для ripple-эффекта InkWell). Это правильный Flutter-pattern для тенистых white-карточек. ProfileRow иконки = flat **brand-green-600** 28 px (НЕ синие — primary brand остался зелёным), label 18/800 ink/900. См. design-tokens.md §6.4 + §6.4c.

---

## План дальше (по приоритету)

Пошагово, мелкими видимыми итерациями. **НЕ беремся за следующий этап пока не закрыт текущий.**

### ✅ Этап 1 (готово) — Receipts: фундамент + история со статусами

Tab «Чек» теперь показывает реальный список чеков с 4 статусами.

### ✅ Этап 2 (готово) — Product Detail sheet

Тап по любой карточке товара в Home открывает bottom-sheet с подробностями.

### ✅ Этап 3 (готово) — Receipts: реальная камера + полный upload flow

End-to-end: CTA → UploadPromptSheet → CameraScreen / Gallery → ReceiptReviewScreen (mock OCR) → Submit → SuccessScreen → история обновляется.

### ✅ Этап 4 (готово) — Recipe integration (2026-05)

Интегрировали `_reference/recipe/` JSX-эталон в Flutter:

- Чек-tab **удалён**, переход на 3-tab nav (см. design-tokens §6.6)
- Перевели `ReceiptDraft` с OCR-формы (title/pharmacy/cashier/sku/amount) на 3-row checklist схему (promos/pharmacy/card)
- Создали `PromoPickerScreen`, `AddressSheet`, `CardSheet` (с 3×4 numeric keypad), переписали `ReceiptReviewScreen`
- `nearby_pharmacies.dart` — 8 мок-аптек Алматы с distance/color
- `reset(keepCard: true)` сохраняет карту между submission'ами

### ✅ Этап 4.5 (готово) — Bottom-nav 3 → 2 + FAB, scrollable header (2026-05-26)

- 3-tab BottomNav → **2-tab + центральный зелёный FAB-камера** (`PharmaBottomNav.onScanTap`). FAB открывает `UploadPromptSheet` (тот же что pill в BalanceCard).
- Tab «Обучение» перенесён в Profile menu (первый пункт «Помощь», school-иконка) → push `LearningStubScreen` (новый файл `widgets/learning_stub_screen.dart`).
- Active-tab color: `brandBlue600` → **`brandGreen600`**.
- Home: зелёный header теперь часть скролла (первый `SliverToBoxAdapter`), а не `Stack` + `Positioned`. Вся страница скроллится единым flow.
- Файлы: `bottom_navigation.dart` (rewrite), `home_screen.dart` (refactor `_HomeTab` + `_ProfileTab._helpItems` + `_resolveOnTap` helper, удалён `_StubTab`), `learning_stub_screen.dart` (new).
- Сборка: gallery-pick + camera bugs пофикшены ранее (Navigator + Notifier захватываются до попа sheet'а; `Row(stretch)` в ListView обёрнут в `SizedBox(height:88)`; `_ReceiptThumb` теперь рендерит реальное `Image.file(photoPath)`).

### ✅ Этап 4.7 (готово) — ReceiptsListScreen в стиле Profile (2026-05-26)

- **ReceiptRow** переписан под layering-pattern `ProfileRow`: `Container(white + shadow/card)` снаружи задаёт surface, `Material(transparent)` внутри только для ink ripple. Раньше Material был белым с тенью-в-Container — shadow «затемнял» прозрачный Container, карточки рендерились серыми/полупрозрачными. Теперь они crisp white, как ProfileRow.
- **Leading status-icon** (28 px, цвет статуса): `check_circle_rounded` для confirmed, `hourglass_top_rounded` для inReview, `receipt_long_outlined` для awaitingReceipt, `error_outline_rounded` для rejected. Аналог зелёной icon-плитки в ProfileRow (только без tile-фона — receipt-карточки и так дenser, не хочу перегружать).
- **«Мои чеки» перенесён в section title на canvas** (H1 26/800 ink/900, как «Помощь» / «О приложении» в Profile). В зелёной шапке остались только back-кнопка + PharmaWordmark + 3 stat-pills. Это унифицирует структуру с Profile: green-header с user-context → canvas с H1-секциями → списки карточек.
- Padding между карточками 12 px (раньше 10), screen-edge 20 px — те же значения что в Profile menu.

### ✅ Этап 4.6 (готово) — SuccessScreen wiring + ReceiptsList polish (2026-05-26)

- **SuccessScreen** действия:
  - «История и статусы» → `popUntil((r)=>r.isFirst)` + push `ReceiptsListScreen` (раньше просто закрывал стек до root, без открытия истории — UX тупиковый).
  - «Отправить ещё раз» → `popUntil` + `showUploadPromptSheet(context)` (мгновенный новый цикл загрузки; `ReceiptDraftNotifier.reset(keepCard: true)` сохраняет карту между submission'ами).
- **ReceiptsListScreen — stat-pills в стиле GlassPill**: 3 пилюли (Подтверждено / На проверке / Ожидает) приведены к рецепту GlassPill (`brandGreen500 @ 55%` + 1 px white-40 border, radius 16, height 64). Счётчик 22/800 (раньше 18/800), label 12/800. Это унифицирует визуальный язык — те же green-pills что в `_AuthedHeader` (Profile) и BalanceCard.
- **Pull-to-refresh** на ReceiptsListScreen: `RefreshIndicator(color: brandGreen600)` оборачивает список, при ослиг-pull-down — `HapticFeedback.mediumImpact()` + `ref.invalidate(receiptListProvider)` + `await ref.read(receiptListProvider.future)` чтобы spinner отыграл visual feedback полностью. Empty/loading/error состояния обёрнуты в ListView с `AlwaysScrollableScrollPhysics()` — без этого pull-to-refresh не реагирует когда контента нет (нечего тянуть).

### QR-сканер / ОФД — ОТМЕНЕНО (2026-06-09)

ОФД-верификации по QR не будет ни сейчас, ни в будущем (решение заказчика). Сверка чека идёт
ТОЛЬКО по логу кассы Стандарт-Н (программа на C#) + Excel-выгрузке + ручной модерации. Из приложения
убраны: опция «Сканировать QR», `qrRaw`, упоминания ОФД в FAQ. Загрузка чека = фото + выбранная аптека.

> **Баг-фикс (2026-06-09):** выбранная фармацевтом аптека теперь реально доходит до бэка —
> `submitReceipt(pharmacyId, pharmacyName)` шлёт её в multipart-полях (раньше терялась → в детали
> чека аптека была пустой). Добавлен экран детали чека (`receipt_detail_sheet.dart`) по тапу на строку
> истории: фото, акция, **аптека**, сумма, бонус, статус, причина отклонения.

### Этап 6 — Рефакторинг (cleanup без новых фич)

- `home_screen.dart` сейчас ~700 строк. Вынести:
  - `_ProfileTab` → `lib/features/profile/presentation/profile_screen.dart`
  - `_StubTab` (если останется) → `lib/core/widgets/`
  - `_Header`, `_AuthedHeader`, `_LogoutButton` — каждый в свой файл
- Структура `lib/features/home/` останется только для Home tab.

### Этап 7 — Доделки в Профиль-разделе

- **Помощь → WhatsApp**: открыть через `url_launcher` ссылку `https://wa.me/+77001234567?text=…`
- **Profile → Конкурсы**: stub «Скоро» или начало лидерборда.
- (История уже работает — push на `ReceiptsListScreen` после Этапа 4)

### Этап 8 — Material 3 Android вариант

Условный стиль через `Theme.of(context).platform`. См. `_reference/design_handoff_pharmapay/prototypes/PharmaPay (Android).html`.

### Этап 9 — Реальный API + JWT refresh

- `AuthApiRepository` рядом с mock'ом + переключатель.
- Dio + interceptors, refresh flow.
- Замена `ReceiptRepository` (in-memory) на HTTP-вариант.

---

## Открытые вопросы

- Реальный backend (REST/GraphQL, OpenAPI?) — не известно. Mock-репо async-ready.
- Локализация: пока только ru-KZ; en/kk добавим через `flutter_localizations` + ARB при необходимости.
- iCloud Drive: при переезде проекта в не-iCloud папку (`~/Developer/` например) shim больше не нужен. Это вариант на будущее.

---

## 2026-06-10 — Реальный каталог + адреса аптек + Luhn карты

- **Каталог** (`lib/features/catalog/`): экран поиска (дебаунс 300 мс) + бесконечный скролл + detail-лист.
  `CatalogRepository` mock/api по флагу `USE_API` → `/api/mobile/catalog/*` (бэкенд проксирует Medusa-витрину).
  Цена/фото опциональны: плитка-заглушка (первая буква названия), «Цена в аптеке». Вход — карточка
  «Каталог товаров» на главной + маршрут `/catalog`. Bonus-каталог (промо-акции) на главной оставлен
  как есть — это РАЗНЫЕ сущности (бонусы — наша логика, каталог — реальный товарный справочник).
- **Аптеки**: `PharmacyRepository` mock/api; `AddressSheet` грузит реестр через `/api/mobile/pharmacies`
  (loading / error+«Повторить» / empty). `NearbyPharmacy.fromApi` — цвет сети из hex `chainColor`, в слот
  `distance` кладём район (GPS-координат в реестре нет).
- **Карта**: `lib/core/validation/card.dart` — Luhn + бренд по BIN + формат. `CardSheet` активирует
  «Привязать карту» только при валидном номере, инлайн-ошибка контрольной суммы, лейбл бренда на превью.
  `ReceiptDraft.hasCard` тоже через `isValidCardNumber`.
- Тесты: `card_test`, `catalog_test`, `pharmacy_repository_test`. `flutter analyze` чист, 46 тестов зелёные.

## 2026-06-10 — Release-hardening: реальные данные по умолчанию

- **`ApiConfig.useApi` теперь `true` по умолчанию** — приложение работает на реальном бэкенде.
  Офлайн-моки только под `--dart-define=USE_API=false`. Каталог/аптеки/чеки/профиль — реальные.
- **Persist токенов**: `flutter_secure_storage` (Keychain/EncryptedSharedPreferences). `TokenStore` —
  in-memory кэш (синхронный для ApiClient) + write-through на диск; `load()` на старте; ошибки
  секьюр-хранилища (unit-тесты без плагина) безопасно глотаются. `main()` восстанавливает сессию:
  load токенов → `refreshMe()` (/me) → пользователь сразу на Home. `logout()` чистит токены.
- **Cleartext/ATS — прод-безопасно**: Android `res/xml/network_security_config.xml` (http только для
  10.0.2.2/localhost/127.0.0.1, иначе HTTPS); iOS Info.plist — без `NSAllowsArbitraryLoads`, http-
  исключение только для localhost. Релизная сборка идёт на `https://api.epharm.kz`.
- **Реальные аптеки**: список приходит из бэкенда (`/api/mobile/pharmacies`) — 522 реальных аптеки
  витрины inkar.kz. Хардкод 8 аптек остался только как mock-фолбэк (USE_API=false).
- Тесты: 46 зелёные, analyze чист (после flutter pub add flutter_secure_storage).

## 2026-06-11 — Лента Home = реальный каталог (фильтры бренд/категория/сорт)

**Что сделано:** главный экран переделан — отдельная плашка «Каталог товаров» (+ маршрут `/catalog`
и экран `CatalogScreen`) **удалены**, лента под фильтрами теперь рендерит **реальный каталог витрины**
(через `/api/mobile/catalog`), а не 9 mock-промо-товаров.

**🔑 Факты по реальным данным канала «Сайт» (проверял live Medusa перед дизайном — senior-правило):**

- Всего **77 товаров** (НЕ 28k — это весь Medusa; на канал «Сайт» опубликовано 77).
- **Цены нет ни у одного** (`calculated_price = null` у всех 77) → карточки «Цена в аптеке».
- Фото только у **7/77** → у остальных градиент-плитка с первой буквой.
- `created_at` **одинаковый у всех** (bulk-импорт) → «Новинки»/бейдж NEW вычислить не из чего.
- Брендов **~9** (через fallback brand_name→brand_raw→corporation→manufacturer), часть мусорная (`-`, дубли).
- Категорий: привязано **11/77** товаров (БАДы(7), Косметика(4)…); 66 без категории; «Сайт» — структурная.
- Medusa Store API: `order=-created_at`/`order=title` работают, но **фильтр по `metadata.brand_name` — 400**.

**Архитектурное решение (под реальные данные, без фейков):**

- 77 товаров **грузим целиком на клиент** (`homeCatalogProvider` — постранично, safety-cap 1000) и
  фильтруем локально через чистую `applyCatalogFilters` (тот же паттерн, что был с mock — `applyHomeFilters`).
  Если каталог вырастет в тысячи — фильтрацию переносим на сервер.
- Фильтры: **Бренд** (реальные бренды из `homeBrandsProvider`, мусор `-`/null отброшен) + **Категории**
  (новый sheet, `homeCategoriesProvider`, без «Сайт») + поиск (имя/бренд/МНН) + сортировка (А-Я/Я-А).
- Чипы **«Новинки»/«Конкурсные» убраны** (под них в каталоге нет данных). По выбору заказчика их
  заменил фильтр «Категории».
- Карточка — переиспользуемая `CatalogCard` (вынес из удалённого `catalog_screen`), тап → существующий
  `showCatalogProductSheet`. Промо-карусель (Huggies/Kotex/Info) на месте — это НАШИ бонус-акции.
- **Backend:** в `MobileCatalogProductDto` добавлено `categories: List<String>` (все категории товара,
  для клиентского фильтра); `MAX_LIMIT` 50→100 (все 77 за 1 запрос).
- **promo_picker (выбор акций для чека)** по-прежнему использует `productListProvider` (mock-промо-товары) —
  это отдельная сущность (бонус-акции), не каталог; модель `Product`/`home_repository` оставлены для него.

**Удалено как мёртвое:** `catalog_screen.dart`, маршрут `/catalog`, `product_card.dart` (Big/SmallProductCard),
`product_detail_sheet.dart`, `catalogProvider`/`CatalogState`/`CatalogNotifier` (пагинация старого экрана),
chip-провайдер `homeChipProvider`, старый `SortOption`, хардкод `kKzPharmaBrands`.

**Тесты:** `test/features/home/home_catalog_filter_test.dart` (9 тестов: `applyCatalogFilters` —
бренд/категория/поиск/сорт/комбинации + деривация брендов/категорий через `ProviderContainer` с фейк-репо).
`flutter analyze` чист, **55 тестов зелёные**. Backend: `MobileCatalogServiceTest` обновлён (categories + лимит).

## 2026-06-11 — Фикс «Каталог недоступен» (был backend-401, не баг мобилки)

Главная с лентой каталога показывала «Каталог недоступен» + иконку «нет сети». Это оказался
НЕ баг приложения: иконка `Icons.wifi_off_rounded` — мой generic error-виджет, который рисуется
на ЛЮБУЮ ошибку `homeCatalogProvider`, включая HTTP 401. Корень — на бэке каталог требовал JWT
фармацевта, а главная смотрится без логина. Фикс чисто серверный (см. admin-notes), код мобилки
НЕ менялся.

- `homeCatalogProvider` — `FutureProvider`: кеширует ошибку, сам не ретраит. После фикса сервера
  нужен `ref.invalidate` (кнопка «Повторить») ИЛИ перезапуск приложения, чтобы перезапросить.
  **APK в `builds/` пересобирать не нужно** — он уже бил в этот сервер; чинился только серверный 401.
- Проверено в iOS-симуляторе (iPhone 17, против https://epharm.78-140-246-238.sslip.io): лента
  карточек грузится (Aquafil Hydra, Eve Multi 90/Bayer, Hairmate…), деталь товара открывается
  (штрихкод, «Без рецепта»), фильтр «Бренды» (поиск + чекбоксы всех брендов) работает.
- 💡 На будущее (polish): различать в error-виджете 401/auth и реальную сеть — сейчас обе дают
  «нет сети». Плюс известный остаток: цена «-» (нужен `region_id` для Medusa calculated_price).

## 2026-06-12 — Лента главной = промо-акции (Фаза 4 фичи Medusa→админка→мобилка)

Главная фармацевта переключена с «весь каталог Medusa» на ПУЛ промо-акций из админки.
Источник — `GET /api/mobile/promotions` (новый публичный backend-эндпоинт, Фаза 2): активные
промо-кампании, смерженные с живыми данными товара Medusa.

- Новая feature `lib/features/promotions/`:
  - `data/promotion_models.dart` — `Promotion` (id промо + productId Medusa + товар + tiers + даты)
    - `PromoTier{minQty,price,bonus}` + `dateLabel` («с 1 — 30 июня»).
  - `data/promotions_repository.dart` — `GET /api/mobile/promotions` (getJsonList).
  - `application/promotions_controller.dart` — `promotionsProvider` (FutureProvider),
    `promoBrandsProvider`/`promoCategoriesProvider` (из пула промо), `applyPromotionFilters`.
  - `presentation/promo_product_card.dart` — богатая карточка В НАШ СТИЛЬ (по синему макету,
    но зелёная): фото, название/бренд, ряд ценовых порогов «от N шт → цена», строки бонусов,
    бейдж дат. Тап → `showCatalogProductSheet(productId)` (переиспользуем детальную карточку).
- `home_screen.dart`: `_CatalogSliver` (2 колонки) → `_PromoSliver` (ОДНА колонка, `SliverList.separated`),
  watch `promotionsProvider` + `applyPromotionFilters`. brand_sheet/category_sheet → `promoBrands/Categories`.
- Каталог Medusa (`homeCatalogProvider`/`applyCatalogFilters`/`CatalogCard`) и его тесты НЕ тронуты —
  остаются для будущего обзора витрины.
- Баннеры-заглушки −10% (260×200 → 234×180).
- Тест `test/features/promotions/promotion_filter_test.dart` (фильтр/сорт/поиск + парсинг).

**Демо:** на проде засижено 5 акций (Панкраген/Кардиоген/Eve Multi-Bayer/Ivatherm/NOW Бор) —
все с фото, реальными брендами Medusa и порогами/бонусами. Лента непустая.

**Осталось (следующие фазы):** Фаза 3 — админ-форма промо (пикер товара Medusa + редактор
порогов/дат), чтобы админ заводил акции сам (сейчас засижено скриптом).

## 2026-06-12 — Пикер чека = пул промо-акций (Фаза 5)

Пикер товаров при загрузке чека (`promo_picker_screen.dart`) переключён с мок-каталога
(`productListProvider` → `Product`) на тот же пул промо-акций (`promotionsProvider` → `Promotion`),
что и лента Home. Фармацевт выбирает из ПРЕДСТАВЛЕННЫХ акций те, что в его чеке.

- `ReceiptDraft.promos: List<Promotion>` (было `List<Product>`); `setPromos`/`copyWith` обновлены.
- Карточка пикера — фото товара (`Image.network` + плейсхолдер-буква, как в `PromoProductCard`),
  бейдж дат (`dateLabel`), inline-toggle. **Ленивый `SliverGrid`** (CustomScrollView) — карточки и
  их network-фото строятся только во вьюпорте (не «все разом»).
- Выбор хранится как `Map<String,Promotion>` (id→объект) — сохраняется РОВНО выбор, счётчик CTA
  не расходится с сохранённым, даже если пул обновился. Error-стейт с кнопкой «Повторить»
  (`ref.invalidate`, паритет с Home). `Semantics(button/selected/label)` на карточках.
- Выбранные `promoIds` уходят с чеком (`api_receipt_repository` → multipart-поле CSV) и **персистятся
  на чеке** (`receipts.claimed_promo_ids`, V024) как заявление фармацевта — контекст модератору,
  на матчинг бонуса НЕ влияет. Виден в админском drawer проверки чека («Заявленные акции»).
- Мёртвый `productListProvider` удалён (мок-`Product`/`loadProducts` в `home_repository` пока живут —
  вынесено в отдельную задачу-чип для чистки).
- Тесты: `test/features/receipts/promo_picker_screen_test.dart` (рендер из пула, выбор→setPromos,
  пустой пул) + `api_receipt_repository_test` (CSV `promoIds`). Flutter 56/56, analyze чистый.

**Качество:** фича прогнана через ultracode adversarial-review (4 измерения), 8 подтверждённых
находок исправлены (error-retry, ленивый грид, Map-выбор, токен-безопасная нормализация CSV на бэке,
показ заявленных акций модератору, Semantics, фикстура теста).

---

## 2026-06-14 — Live-sync: pull-to-refresh + авто-рефреш на главной

**Зачем:** «изменил акцию в админке → должно появиться на телефоне без перезапуска». Данные и так
живые (телефон читает `/api/mobile/promotions` напрямую из БД), но `promotionsProvider` —
обычный `FutureProvider` (не autoDispose), грузит ленту ОДИН раз за сессию. Не хватало триггера refresh.

**Что сделано** (`lib/features/home/presentation/home_screen.dart`):

- `refreshHomeData(WidgetRef ref, {bool awaitData=true})` — инвалидирует/рефрешит `promotionsProvider`
  (реальная лента из админки) + `promoListProvider` (карусель) + `refreshMe()` (баланс).
  `awaitData=true` (pull) ждёт futures → спиннер держится; `awaitData=false` (resume) — fire-and-forget.
- Лента (`_HomeTab`) обёрнута в `RefreshIndicator` + `AlwaysScrollableScrollPhysics` (pull работает и при
  коротком контенте). Потянул вниз → свежие акции.
- `_HomeScreenState with WidgetsBindingObserver`: на `AppLifecycleState.resumed` авто-рефреш с троттлингом
  20с + `mounted`-guard. Возврат в приложение → лента актуализируется.

**Качество:** `flutter analyze` чистый, тесты `test/features/home/home_refresh_test.dart` (refresh
перезапрашивает ленту = видит новую акцию; на главной есть RefreshIndicator), полный прогон 58/58.
Прогнано через ultracode adversarial-review (3 линзы × verify): все blocker/major **отбракованы**
verify-пасом как ложные (синхронный invalidate без async-gap, resume не пересекается с pull) — взяли
только идиоматичный `mounted`-guard. APK пересобран с `API_BASE=https://epharm.78-140-246-238.sslip.io`
(дефолт `api.epharm.kz` сейчас не резолвится — собирать ТОЛЬКО с явным base).

---

## 2026-06-14 — Лента в 2 колонки + фикс сброса фильтра + единый зелёный

По фидбеку со скрина (`home_screen`):

- **Сетка 2 колонки:** `_PromoSliver` → `SliverGrid` (crossAxisCount 2, `mainAxisExtent 238`) из
  новой компактной `PromoGridCard` (фото 116 + название + бренд + бонус). Старая широкая
  `PromoProductCard` осталась в файле, но в ленте больше не используется.
- **Единый зелёный:** убрано «буйство оттенков» — тёмный `brandGreen700` (ценовые пилюли) в ленте
  заменён на ОСНОВНОЙ `brandGreen600` (как шапка/навбар). Бонус-пилюля в grid-карточке — brandGreen600.
- **Фикс сброса фильтра:** в `brand_sheet`/`category_sheet` кнопка «Сбросить» чистила только локальный
  выбор — если закрыть лист свайпом (без «Применить»), провайдер оставался → чип не сбрасывался.
  Теперь «Сбросить» сразу зовёт `ref.read(selected*Provider.notifier).clear()`. Регрессионный тест.
- Тесты: `home_grid_filter_test` (рендер grid-карточки + сброс фильтра). Flutter 60/60, analyze чистый.

## ⚠️ GOTCHA: iCloud Desktop ломает codesign iOS (`com.apple.FinderInfo`)

Проект на `~/Desktop` под iCloud «Desktop & Documents». iCloud вешает на свежие build-артефакты
xattr `com.apple.FinderInfo`/`fileprovider`, а `codesign` их отвергает:
`App.framework/App: resource fork, Finder information ... not allowed`. Это НЕ про подпись/keychain
(она валидна) — чисто iCloud-тег. `xattr -cr build` не помогает (сборка пересоздаёт файл, iCloud
снова тегает).

**Фикс (применён):** увести `build/` из iCloud симлинком на не-синхронизируемую папку:
`rm -rf build && mkdir -p ~/.epharm-build-out && ln -s ~/.epharm-build-out build`. После этого
`flutter build ios/apk` подписывает чисто. Симлинк оставлен — будущие сборки тоже не споткнутся.
Установка на устройство: `xcrun devicectl device install app --device <udid> build/ios/iphoneos/Runner.app`.

## Батч D/E (2026-06-16) — ДОП.3b + ДОП.8

- **ДОП.3b Альтернативы/Дополнения** (`catalog_product_sheet`): новый `catalogRecommendationsProvider`
  (`/api/mobile/catalog/products/{id}/recommendations`) — отдельный fetch, не тормозит карточку. Две
  горизонтальные секции `_RecoSection` (3:4 карточки `_RecoCard` + бонус-бейдж `+520 ₸`), тап → карточка
  товара (стек bottom-sheet). Пустые секции скрыты. mock возвращает `CatalogRecommendations.empty`.
  Модели `CatalogRecommendation`/`CatalogRecommendations` в `catalog_models.dart`.
- **ДОП.8 авто-чек** — убран ручной выбор акции/аптеки. Удалены `promo_picker_screen.dart`,
  `address_sheet.dart` + вся инфра аптек-пикера (`pharmacy_repository`/`api`/`mock`/`nearby_pharmacies` +
  `pharmacyRepositoryProvider`/`pharmacyListProvider`). `ReceiptDraft` упрощён до `photoPath`+`card`
  (убраны promos/pharmacy/setPromos/setPharmacy/hasPromos/hasPharmacy). Экран обзора: 1 пункт (карта) +
  инфо-баннер `_AutoDetectNote` «Акции и аптека — автоматически». `submitReceipt(title, photoPath)` —
  multipart только с фото. Backend сам берёт аптеку из профиля и матчит акции (POSM-бронь).
- Тесты: catalog 7/7, receipts (api/mock/detail) зелёные; `flutter analyze` 0 issues.

## Смягчение палитры главной (2 прохода)

- 1-й проход (1d8cb01): хедер 2-stop неон → мягкий 3-stop; баннеры `ink300` → светлый градиент.
- 2-й проход (cb374b4) по жалобе «переход всё равно жёсткий»: жёсткость давала **drop-тень
  баннера** (резкая тёмная кромка снизу). Фиксы: у баннера тень УБРАНА + низ градиента сведён
  к цвету канваса (`#F2F4F9 ≈ #F4F6FA`) → нижний край растворяется, шва нет; `AppShadows.card`
  смягчена (свечение 0.12→0.08, контакт 0.08→0.05) — все карточки лежат на фоне мягче.
  **Урок:** «жёсткий переход» у светлого блока — почти всегда про ТЕНЬ/кромку, не про сам цвет.

## 2026-06-17 — Батч мобильных правок (Q&A, категории, пилюли пулов, скролл, онбординг)

- **Q&A-аккордеон** (`catalog_product_sheet.dart`): секция «Вопросы и ответы» свёрнута в
  кнопку-заголовок (счётчик + шеврон `AnimatedRotation`), по тапу `AnimatedCrossFade` раскрывает
  все Q&A. Длинный FAQ не растягивает карточку. Виджет `_QaSection` (StatefulWidget).
- **Категории без «Сайт»** (`category_sheet.dart` → `buildPrunedTree`): «Сайт» — структурный
  корень Medusa; его детей промотим на верхний уровень (категории «как раньше», разбито по
  разделам). Флэттеним любой root с именем `сайт` (lowercase). Плоский список (поиск) и так
  фильтровал «сайт» в `promoCategoriesProvider`.
- **Пилюли «Альтернативы»/«Дополнения»**: рядом с Бренд/Категории — два тумблера
  (`homeRecoPoolProvider`, enum `RecoPool` none/alternatives/crosssells, взаимоисключающие).
  Активная пилюля заменяет ленту акций на сетку пула продвигаемых товаров (`_CatalogPoolSliver`
  → `CatalogCard`). Источник: новый backend `GET /api/mobile/catalog/recommendation-pools` →
  `CatalogRecommendationPools{alternatives,crosssells}` (distinct recommend активных
  substitution/crosssell-правил, резолв в Medusa-карточки). Провайдер
  `catalogRecommendationPoolsProvider` (кеш). Поиск применяется к пулу (`_filterCatalog`).
- **Скролл/перф** — лаги ленты от полноразмерного декода фото. Фикс: `cacheWidth` у всех
  `Image.network` сетки/ленты (`promo_product_card` \_GridThumb 400/\_Thumb 220, `catalog_card` 400,
  `catalog_product_sheet` \_DetailImage 700/\_RecoThumb 320) — декод под размер ячейки, нет
  re-decode при скролле. Без нового пакета (cached_network_image не тянул — лишние нативные pod).
- **Онбординг у залогиненного** (фикс): был единственный гейт redirect по `currentUser` —
  гонка с фоновым восстановлением сессии, залогиненный успевал увидеть Welcome. Решение:
  стартовый `SplashScreen` (initialLocation `/splash`) + `appStartProvider`/`resolveStartDestination`
  решают по ПЕРСИСТНУТЫМ токенам ДО показа Welcome: есть токены → Home (онбординг скрыт); нет
  токенов, но `OnboardingStore.seen()` → Home; иначе Welcome. `welcome_screen` зовёт `markSeen()`
  при завершении. `main.dart` больше не дёргает `_restoreSession` (логика в appStartProvider).
  `widget_test` override'ит `appStartProvider`→Welcome (иначе спиннер сплеша «висит таймером»).
- Тесты: `app_start_controller_test` (5 кейсов), `reco_pool_test` (тумблер + fromJson), `widget_test`
  починен. Flutter 62/62, analyze 0. Backend `MobileCatalogServiceTest` +2 кейса.

## 2026-06-17 — UI-доводки (категории-тап, порядок секций, логотип)

- **Дерево категорий** (`category_sheet.dart`): тап по ВСЕЙ строке раскрывает подкатегории
  (раньше только по шеврону); выбор фильтра — только по чекбоксу справа (GestureDetector,
  зона +8). Применено и к `_TreeNodeTile`, и к `_CategoryRow` (плоский список поиска).
- **Карточка товара**: `_RecommendationsSections` (Альтернативы/Дополнения) перенесён ПЕРЕД
  секцией Q&A (рекомендации важнее справки).
- **Логотип**: текстовый `PharmaWordmark` («Epharm») заменён на `PharmaLogo` — SVG-глиф
  «чек-штамп» (как admin `Logo.tsx`), через `flutter_svg` `SvgPicture.string`. Заменён во всех
  9 местах (главная/профиль/чеки header, welcome, splash, phone/otp/profile_form). Читается на
  зелёном (белый чек + синий ⊕) и на белом (зелёный контур). Имя бренда не финал — показываем знак.
  Admin: Sidebar/LoginPage тоже без текста «Epharm» (остался глиф Logo), тесты обновлены.
- Деплой 2026-06-17: admin-frontend (логотип) + iOS на iPhone + APK. Backend не трогали. Flutter 62/62.
