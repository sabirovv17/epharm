# Epharm — Dev Onboarding: запуск приложения на телефоне

Цель: новый разработчик запускает рабочее мобильное приложение на своём телефоне
(iOS **или** Android), связанное с общим backend и админкой.

## Контекст: локально ничего поднимать не нужно

Backend, админка, каталог, чеки и БД уже работают на общем демо-сервере:

```
https://epharm.78-140-246-238.sslip.io
```

Приложение просто нацеливается на этот адрес флагами `--dart-define`. Что ты делаешь
в приложении (регистрация, загрузка чека) — сразу видно в админке: одна общая база.

## Предусловия

- Доступ к репозиторию `github.com/sabirovv17/epharm` (приватный — попроси владельца
  добавить коллаборатором). Рабочая ветка: `feat/mobile-backend`.
- **Flutter 3.27.x** stable + Dart 3.6 (`flutter --version`).
- Для iOS: **macOS + Xcode + CocoaPods**.
- Для Android: **Android Studio** (или Android SDK + platform-tools).

## Клонирование и зависимости

```bash
git clone https://github.com/sabirovv17/epharm.git
cd epharm
git checkout feat/mobile-backend
flutter pub get
flutter doctor          # нужный таргет (iOS/Android) должен быть зелёным
flutter devices         # узнать id своего подключённого устройства
```

## Параметры запуска (одинаковые для обеих платформ)

```
--dart-define=USE_API=true
--dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
```

---

## 🤖 Случай 1 — запуск на Android

### 1a. Просто поставить готовый APK (без сборки)

Открыть на телефоне ссылку и установить (в настройках Android разрешить «установку из
неизвестных источников» для браузера). APK уже нацелен на сервер:

```
https://epharm.78-140-246-238.sslip.io/s3/epharm-receipts/epharm-demo.apk
```

### 1b. Запуск из исходников на устройстве

1. На телефоне включи **Режим разработчика** (7 тапов по «Номер сборки» в «О телефоне»),
   затем **Отладку по USB**.
2. Подключи кабелем, подтверди «Разрешить отладку с этого компьютера».
3. Запуск:
   ```bash
   flutter run -d <android-device-id> \
     --dart-define=USE_API=true \
     --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
   ```
   (для production-варианта добавь `--release`).

Собрать APK-файл самому:

```bash
flutter build apk --release \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
# → build/app/outputs/flutter-apk/app-release.apk
```

---

## 🍏 Случай 2 — запуск на iPhone (iOS)

iOS требует подпись (code signing). В проекте уже настроена автоматическая подпись
(`ios/Runner.xcodeproj`): команда `P55D384HK5`, bundle id `kz.pharmacy.app`,
`CODE_SIGN_STYLE = Automatic`.

### Шаги

1. **Apple ID в Xcode:** Xcode → Settings → Accounts → добавь Apple ID с доступом к
   команде `P55D384HK5` (либо используй свою — см. примечание ниже).
2. **На iPhone:** подключи кабелем, разблокируй, нажми **«Доверять этому компьютеру»**.
3. **Developer Mode (iOS 16+):** Настройки → Конфиденциальность и безопасность →
   Режим разработчика → вкл → перезагрузка. Один раз.
4. **Запуск:**
   ```bash
   flutter run --release -d <ios-device-id> \
     --dart-define=USE_API=true \
     --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io
   ```
5. **Доверить профиль** (после установки): Настройки → Основные →
   **VPN и управление устройством** → «Программа разработчика» → нажми на свой Apple ID →
   **«Доверять»**.
6. Открой иконку **Epharm** на экране.

### Примечание: подпись своей командой

Если нет доступа к `P55D384HK5` — подпиши своей:

- открой `ios/Runner.xcworkspace` в Xcode → таргет **Runner** → Signing & Capabilities →
  выбери свою **Team**;
- если Team бесплатная (личный Apple ID), поменяй `PRODUCT_BUNDLE_IDENTIFIER` на уникальный
  (напр. `kz.pharmacy.app.<имя>`), иначе подпись не выдастся;
- на бесплатной подписи приложение работает ~7 дней, потом переустановить.

---

## Вход в приложении (dev-режим)

Вход/регистрация — по номеру телефона + **OTP-код `544544`** (dev-режим, реальная SMS
не отправляется). Подходит любой номер.

## Проверка связки «приложение ↔ админка»

1. В приложении: вход (любой номер + `544544`) → каталог и аптеки видны.
2. Загрузи фото чека в приложении.
3. В админке (`https://epharm.78-140-246-238.sslip.io`, вход админом) → раздел
   **«Сверка»** → твой чек в очереди → **«Подтвердить»**.
4. Баланс в приложении обновится — полный цикл «чек → бонус» на общей базе.

---

## Траблшутинг

- **`flutter devices` не видит телефон** — кабель должен быть data (не только зарядка);
  на iPhone «Доверять», на Android «Отладка по USB».
- **iOS: `resource fork, Finder information, or similar detritus not allowed`** при codesign —
  проект лежит в синхронизируемой папке (iCloud/Dropbox), на файлах висят xattr. Решение:
  вынеси проект из синхронизируемой папки, либо собирай через шим:
  ```bash
  mkdir -p /tmp/codesign_shim
  printf '#!/bin/sh\nexec /usr/bin/codesign --no-strict "$@"\n' > /tmp/codesign_shim/codesign
  chmod +x /tmp/codesign_shim/codesign
  export PATH="/tmp/codesign_shim:$PATH"   # перед flutter run
  ```
  Шим использует реальную подпись, только смягчает строгую проверку xattr.
- **iOS: `invalid code signature … profile has not been explicitly trusted`** при запуске —
  профиль не доверен: см. шаг 5 (VPN и управление устройством → Доверять).
- **iOS: `Developer Mode disabled`** — включи Режим разработчика (шаг 3).
- **Каталог «недоступен»** — проверь флаг `API_BASE` и что сервер жив:
  `curl https://epharm.78-140-246-238.sslip.io/api/health` → должно вернуть `200`.
