# Mobile Build Artifacts

`builds/` contains scripts and optional review artifacts for the Flutter mobile app.

Current app metadata:

| Field                  | Value             |
| ---------------------- | ----------------- |
| `pubspec.yaml` version | `0.1.1+2`         |
| iOS bundle id          | `kz.pharmacy.app` |
| Android application id | `kz.pharmacy.app` |
| Display name           | `Epharm`          |

Generated APK/IPA/app zip artifacts should not be committed unless there is an explicit release handoff.

The current device build is `Epharm-iOS-0.1.1+2-Runner.app.zip`. It is signed with the
development profile for the registered pilot iPhone and was verified with
`codesign --verify --deep --strict` after extracting the ZIP. A development profile is
device-specific and short-lived; rebuild it before the profile expires or when adding another iPhone.

## Important API_BASE Rule

`builds/build_all.sh` uses:

```bash
API_BASE="${API_BASE:-https://epharm.inkar.kz}"
```

The current shared demo backend is:

```text
https://epharm.inkar.kz
```

So for demo/pilot builds, run:

```bash
API_BASE=https://epharm.inkar.kz bash builds/build_all.sh
```

## Build Script

```bash
export PATH="$HOME/development/flutter/bin:$PATH"
cd /Users/amir/Desktop/work/pharma/PharmaPayV2
API_BASE=https://epharm.inkar.kz bash builds/build_all.sh
```

The script:

1. recreates `/tmp/codesign_shim`;
2. runs `flutter clean`;
3. runs `flutter pub get`;
4. builds Android release APK with `USE_API=true`;
5. builds iOS release app bundle with `--no-codesign`;
6. writes artifacts to `builds/`.

## Manual Android Build

```bash
flutter build apk --release \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz
```

Requires Android signing files when producing release-signed APKs.

## Manual iOS Build

Unsigned app bundle:

```bash
flutter build ios --release --no-codesign \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz
```

Distribution IPA requires paid Apple Developer account and provisioning:

```bash
flutter build ipa --release \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz
```

## iOS xattr / iCloud Note

This workspace is under Desktop/iCloud on the main machine. If codesign fails with:

```text
resource fork, Finder information, or similar detritus not allowed
```

recreate the shim:

```bash
mkdir -p /tmp/codesign_shim
printf '#!/bin/sh\nexec /usr/bin/codesign --no-strict "$@"\n' > /tmp/codesign_shim/codesign
chmod +x /tmp/codesign_shim/codesign
export PATH="/tmp/codesign_shim:$PATH"
```

or keep build output outside iCloud-synced folders.

## Install

Android:

- transfer APK to device;
- allow install from source;
- open APK.

iOS unsigned `.app`:

- open Xcode;
- Window -> Devices and Simulators;
- select device;
- drag `Runner.app` into Installed Apps;
- trust developer profile on device if needed.

TestFlight/App Store:

- requires paid Apple Developer Program and signed IPA.
