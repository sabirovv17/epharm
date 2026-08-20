# Dev Onboarding: Mobile App on a Phone

Goal: run the current Epharm mobile app on Android or iPhone against the shared backend.

## Shared Backend

Use:

```text
https://epharm.inkar.kz
```

No local backend is needed for this onboarding path. Actions in the app hit the shared database and
are visible in the admin console.

## Prerequisites

- Access to the private repository.
- Flutter 3.27.x / Dart 3.6.
- Android Studio or Android SDK for Android.
- macOS + Xcode + CocoaPods for iOS.

## Setup

```bash
git clone <repo-url>
cd PharmaPayV2
flutter pub get
flutter doctor
flutter devices
```

Run flags:

```text
--dart-define=USE_API=true
--dart-define=API_BASE=https://epharm.inkar.kz
```

## Android

Run from source:

```bash
flutter run -d <android-device-id> \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz
```

Build APK:

```bash
flutter build apk --release \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz
```

The demo APK may also be hosted under the shared `/s3/` path when published by the team.

## iPhone

iOS requires signing.

```bash
flutter run --release -d <ios-device-id> \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz
```

If signing fails:

1. Open `ios/Runner.xcworkspace`.
2. Select target `Runner`.
3. Choose an available Team in Signing & Capabilities.
4. If using a free Personal Team, change bundle id to a unique one.
5. Trust the developer profile on the iPhone after install.

If macOS/iCloud xattrs break codesign, recreate the shim:

```bash
mkdir -p /tmp/codesign_shim
printf '#!/bin/sh\nexec /usr/bin/codesign --no-strict "$@"\n' > /tmp/codesign_shim/codesign
chmod +x /tmp/codesign_shim/codesign
export PATH="/tmp/codesign_shim:$PATH"
```

## Login

While dev OTP is active, the OTP code is:

```text
5445
```

Use any phone number for a pilot/dev registration unless a specific seeded user is needed.

## Verify App <-> Admin

1. Login in the mobile app.
2. Confirm promotions/catalog/banners load.
3. Upload a receipt photo.
4. Open admin: `https://epharm.inkar.kz`.
5. Go to Reconcile.
6. Approve/reject the receipt.
7. Pull-to-refresh mobile receipt list/balance.

## Troubleshooting

| Symptom                               | Fix                                                                      |
| ------------------------------------- | ------------------------------------------------------------------------ |
| `flutter devices` does not show phone | Use a data cable; trust computer; enable USB debugging / Developer Mode. |
| App cannot reach backend              | Check `API_BASE`; `curl https://epharm.inkar.kz/api/health`.             |
| iOS profile not trusted               | iPhone Settings -> VPN & Device Management -> trust developer.           |
| iOS Developer Mode disabled           | Enable Developer Mode and reboot.                                        |
| Local backend on physical phone       | Use Mac LAN IP, not `localhost`.                                         |
