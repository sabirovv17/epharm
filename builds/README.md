# Epharm — Build артефакты

> Готовые APK / IPA сборки текущей версии для рассмотрения / тестирования.
> Сборки лежат в корне `builds/`, обновлять — `bash builds/build_all.sh` (см. инструкцию ниже).

## Текущая версия

- `pubspec.yaml` → `version: 0.1.0+1`
- Bundle ID (iOS): **`kz.pharmacy.app`**
- ApplicationId (Android): **`kz.pharmacy.app`**
- Package name (`pubspec.yaml`): `pharmacy`
- Display name (iOS `CFBundleDisplayName` / Android `android:label`): **Epharm**

> Был ребрендинг bundle ID `kz.pharmapay.pharmapay → kz.pharmacy.app` чтобы свежие сборки на симуляторе/устройстве не конфликтовали со старыми (iOS уникально идентифицирует приложения именно по bundle ID). Брендовое имя в UI/сторах осталось «Epharm».

## Файлы (последняя сборка)

| Файл                           | Размер | Платформа                 | Описание                                                                                                                                                                                                                                                                   |
| ------------------------------ | ------ | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Epharm-v0.1.0-release.apk`    | ~21 MB | Android 5.0+              | Release APK (universal: arm64-v8a + armeabi-v7a + x86_64). На устройстве отображается под брендовым именем **«Epharm»** (`AndroidManifest.xml` → `android:label`). Подписан release-keystore (`android/keystore.jks`, alias `pharmacy`, не коммитится — см. `.gitignore`). |
| `Epharm-v0.1.0-Runner.app.zip` | ~23 MB | iOS 12+ (физ. устройство) | Release .app bundle **без distribution-подписи** (`--no-codesign`). Распаковать → перетащить `Runner.app` в Xcode → Window → Devices & Simulators → выбранное устройство → раздел Installed Apps. Установится с Personal Team signing (7-дневный лимит).                   |

> Для дистрибуции через App Store / TestFlight нужен **платный Apple Developer ($99/год)** + valid provisioning profile. С ним собирается через `flutter build ipa --release` → `build/ios/ipa/*.ipa`.

## Как обновить сборки

```bash
export PATH="$HOME/development/flutter/bin:$PATH"
cd /Users/amir/Desktop/work/pharma/PharmaPayV2

# 1) Очистка
flutter clean
flutter pub get

# 2) Android APK (release, универсальный, подписан release-keystore)
#    Требуется android/key.properties + android/keystore.jks (не коммитятся).
flutter build apk --release
cp build/app/outputs/flutter-apk/app-release.apk \
   builds/Epharm-v0.1.0-release.apk

# 3) iOS — два варианта в зависимости от наличия Apple Developer account

# 3a) Если есть платный Apple Developer ($99/год) — IPA для распространения:
flutter build ipa --release
cp build/ios/ipa/*.ipa builds/Epharm-v0.1.0.ipa

# 3b) Если только Personal Team (бесплатный аккаунт) — IPA не распространяется,
#     но можно сделать .app bundle и установить через Xcode → Window → Devices:
flutter build ios --release --no-codesign
cd build/ios/iphoneos && zip -r ../../../builds/Epharm-v0.1.0-Runner.app.zip Runner.app && cd -
```

## Как установить APK на Android

1. Положить файл в любое место устройства (через USB, AirDrop-Android-эквивалент, или скачать через браузер на устройстве).
2. Открыть его в файловом менеджере → разрешить установку из источников → готово.

## Как поставить IPA / .app на iOS

**Если IPA подписан (Distribution profile):**

- TestFlight (через App Store Connect)
- Apple Configurator 2 на Mac → подключить устройство → перетащить IPA
- AirDrop на устройство → автоматическая установка

**Если только .app bundle (без подписи):**

- Открыть Xcode → Window → Devices & Simulators → выбрать устройство → перетащить `Runner.app` в раздел Installed Apps. Apple подписи Personal Team — приложение проработает 7 дней, потом нужно пересоздавать.

## Известные ограничения

- APK release собирается без обфускации (debug-симоволы оставлены, чтобы proguard не съел что-нибудь критичное в Riverpod-кодгенах). Для App Store / Google Play надо включить `--obfuscate --split-debug-info=...`.
- IPA подпись зависит от наличия Apple Developer Team. Бесплатный Personal Team не подходит для distribution.
- На macOS 15 / iCloud Drive — перед каждой сборкой убедись что shim для codesign жив (см. `claude-notes.md` → раздел «iOS codesign fix»).

## Что внутри (для review-команды)

- **Welcome → Auth → Home — golden path** работает, OTP принимает только `544544`.
- **Home tab** — sticky зелёная шапка с PharmaWordmark + BalanceCard (для authed) / welcome-gate. Промо-карусель, search input, фильтры (Sort/Brand-sheet/chips Все/Новинки/Конкурсные), 9 товаров mock-каталога. TierLadder в featured-карточке с pastel-green pills + gift-маркерами.
- **Receipts (полный flow)** — больше не отдельный таб, доступ через pill «Загрузить чек» в BalanceCard на Home:
  - `UploadPromptSheet` (Сделать фото / Из галереи / Сканировать QR — последнее snackbar-заглушка)
  - `CameraScreen` (реальная камера + flash auto/on/off + front/back switch)
  - `ReceiptReviewScreen` — 3-row checklist: акции / адрес аптеки / номер карты
  - `PromoPickerScreen` — каталог с toggle «Добавить/Добавлено»
  - `AddressSheet` — pharmacy picker с geolocation-banner (mock «Алматы · 8 аптек»)
  - `CardSheet` — bonus-card capture с card-preview (зелёный gradient) + 3×4 numeric keypad
  - `SuccessScreen` — зелёный grad-welcome + синий disc + 2 CTA: «История и статусы» (pop to Home → push `ReceiptsListScreen`), «Отправить ещё раз» (pop to Home → open `UploadPromptSheet`)
  - `ReceiptsListScreen` (история со статусами) — pill «История» в BalanceCard / Profile header. **Stat-pills в стиле GlassPill** (green-500@55%, 22/800 counter, 12/800 label) — унифицирует визуальный язык со всеми green-pills (BalanceCard, Profile). **Pull-to-refresh**: haptic medium-impact + `ref.invalidate(receiptListProvider)`. Empty/loading/error states обёрнуты в `AlwaysScrollableScrollPhysics` чтобы pull работал даже при пустом списке.
- **BottomNav: 2 icon-only таба + центральный зелёный FAB-камера** — домик / [FAB scan] / человечек (2026-05-26). Текст-лейблы убраны (только иконки 34 px, active = filled rounded, inactive = outlined). FAB открывает тот же `UploadPromptSheet` что и pill «Загрузить чек» в BalanceCard — primary action под большим пальцем. Active-tab цвет = `brandGreen600` (раньше был blue). Шторка стала тоньше: top padding 10→6, bottom 28→8, inner height 56→48. Эволюция: 4 → 3 → 2 таба + label → icon-only compact. См. design-tokens §6.6.
- **Home — единый скролл (2026-05-26)**: зелёная шапка теперь часть `CustomScrollView` (первый sliver), а не sticky-`Positioned`. Раньше при прокрутке header висел поверх — UX путаный на short-screen Android. Теперь header / promo / search / фильтры / товары прокручиваются единым flow.
- **Профиль** — все sub-pages: FAQ с видео-инструкцией (открывает YouTube), Подробная инструкция (5 шагов), Сотрудничество, Условия, Политика. **«Обучение»** добавлено первым пунктом «Помощь» (school-иконка → push `LearningStubScreen` со stub-карточкой).
- **Обучение** — push-route stub-экран (раньше был tab), доступен из Profile → Помощь → «Обучение».
- **App icon** — Receipt Stamp (green variant) на iOS + adaptive icon на Android. Генерируется из `tools/gen_app_icon.py` + `tools/gen_adaptive_icon.py`.

## Скрипт автоматизации (опционально)

`builds/build_all.sh`:

```bash
#!/bin/sh
set -e
export PATH="$HOME/development/flutter/bin:$PATH"
cd "$(dirname "$0")/.."

VERSION=$(grep "^version:" pubspec.yaml | awk '{print $2}' | sed 's/+.*//')

# Воссоздать codesign shim (после reboot Mac обнуляется /tmp)
mkdir -p /tmp/codesign_shim
cat > /tmp/codesign_shim/codesign << 'SHIM'
#!/bin/sh
exec /usr/bin/codesign --no-strict "$@"
SHIM
chmod +x /tmp/codesign_shim/codesign

flutter clean
flutter pub get

# APK (signed via android/key.properties → keystore.jks)
flutter build apk --release
cp build/app/outputs/flutter-apk/app-release.apk \
   "builds/Epharm-v${VERSION}-release.apk"

# iOS (.app bundle, без distribution-подписи)
flutter build ios --release --no-codesign
cd build/ios/iphoneos
zip -qr "../../../builds/Epharm-v${VERSION}-Runner.app.zip" Runner.app
cd -

echo "✓ builds/ обновлён"
ls -lh builds/
```
