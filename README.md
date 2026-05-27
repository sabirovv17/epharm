# Epharm (pharmacy)

Кроссплатформенное (iOS + Android) Flutter-приложение для фармацевтов в Казахстане. Фармацевты получают бонусы за переключение клиентов на промо-SKU, проходят обучение и AI-экзамены, отслеживают выплаты.

> Брендовое имя в UI и сторах — **Epharm**. Код-имя пакета (`pubspec.yaml`, bundle ID, applicationId) — **pharmacy** / `kz.pharmacy.app`. См. `claude-notes.md` про этот разрыв.

## Стек

- **Flutter 3.27** (Dart 3.6), iOS-first, Android paritet
- **Riverpod** — state (NotifierProvider / StreamProvider / FutureProvider)
- **go_router** — навигация с auth-aware redirect
- **Manrope** (variable font) бандлится локально (`assets/fonts/Manrope/`)
- **Mock** repository pattern — backend пока in-memory, замена на HTTP позже без рефакторинга presentation

## Запуск

```bash
export PATH="$HOME/development/flutter/bin:$PATH"
flutter pub get
flutter run                     # iOS-симулятор по умолчанию
flutter run -d "iPhone 17"      # явный выбор
flutter analyze                 # должен быть чистым
```

## Структура

```
PharmaPayV2/
├── claude-notes.md             # ЖИВОЙ контекст — читать первым в новой сессии
├── _reference/                 # дизайн-эталоны (HTML/JSX/MD)
│   ├── design-tokens.md        # каноничные дизайн-токены (palette, typography, components, §11 receipt-flow)
│   ├── Fonts.md                # типографика подробно
│   ├── recipe/                 # JSX-эталон чек-flow → реализован в lib/features/receipts/
│   ├── profile-pages/          # JSX-эталон profile sub-pages
│   └── design_handoff_pharmapay/  # полный HTML/JSX прототип
├── builds/                     # готовые APK/IPA артефакты + README + build_all.sh
├── tools/                      # gen_app_icon.py + gen_adaptive_icon.py (Python+Pillow генератор иконок)
└── lib/
    ├── core/
    │   ├── theme/              # app_colors, app_typography, app_radii, app_shadows, app_spacing, app_gradients, app_theme
    │   ├── router/             # go_router config
    │   └── widgets/            # PharmaWordmark, GlassPill, BrandIcons, SearchInput, FilterChipRow…
    └── features/
        ├── welcome/            # WelcomeScreen — 3 слайда с peeking phone-previews
        ├── auth/               # phone → OTP → profile_form
        ├── home/               # HomeTab + ProfileTab + BottomNav + PromoCarousel + Product cards + TierLadder
        ├── receipts/           # UploadPromptSheet + CameraScreen + ReceiptReviewScreen (3-row checklist) +
        │                       #   PromoPickerScreen + AddressSheet + CardSheet + SuccessScreen + ReceiptsListScreen
        └── profile_pages/      # FAQ + Instruction + Cooperation + Terms + Privacy
```

## Build артефакты

`builds/` содержит готовые APK/IPA для рассмотрения / тестирования. Подробности — `builds/README.md`. Обновить — `bash builds/build_all.sh`.

## Дизайн-токены

Источник истины — `_reference/design-tokens.md`. Цвета хардкодить запрещено — только через `AppColors.brandGreen600` (и подобное). Шрифт через helpers `AppTypography.*`. См. claude-notes.md → раздел «Ключевые соглашения».

## Документация

- **`claude-notes.md`** — живая контекстная заметка (статус, соглашения, открытые вопросы). **Читать первым в каждой новой сессии.**
- **`_reference/design-tokens.md`** — каноничные дизайн-токены и component spec
- **`_reference/Fonts.md`** — типографика
- **`builds/README.md`** — как собирать и устанавливать APK/IPA

## Ключевые соглашения (краткая выжимка)

- Никогда не хардкодить цвета — только через `AppColors.*`
- Тенге `₸`: всегда `fontFamilyFallback: ['Roboto', 'sans-serif']` — Manrope-Variable не содержит U+20B8
- TextField на Android: `autocorrect: false`, `enableSuggestions: false`, `smartDashesType: disabled`, `smartQuotesType: disabled` — иначе IME форсит Latin-словарь и съедает кириллицу
- Любой push-route обязан возвращать `Scaffold` (или хотя бы `Material`) — иначе yellow-underline debug-стиль
- shadow/fab = `0 10px 24px rgba(22,201,122,0.55)` (зелёный glow CTA)
- iOS codesign shim в `/tmp/codesign_shim/codesign` — пересоздавать после reboot Mac (см. claude-notes)
