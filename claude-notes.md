# Epharm — заметки для контекста

> **Читать этот файл первым в каждой новой сессии.** Содержит сжатое описание проекта, статус и план — чтобы не тратить токены на повторное изучение HTML-прототипа и ТЗ.

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

### Этап 5 — QR-сканер фискального чека

- Добавить `mobile_scanner: ^5.x` в pubspec.
- В UploadPromptSheet опция «Сканировать QR» → QRScanScreen с реальным детектором.
- Парсинг ОФД-URL вида `https://consumer.oofd.kz/?i=...&f=...&s=...&t=...` → попытка дёрнуть API ОФД (или фолбэк на mock-парсинг). Поля заполняются автоматически → Receipt Review (можно сразу заполнить `pharmacy` и `promos` из payload'а).

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
