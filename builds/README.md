# Mobile Build Artifacts

`builds/` contains scripts and optional review artifacts for the Flutter mobile app.

Current app metadata:

| Field                  | Value             |
| ---------------------- | ----------------- |
| `pubspec.yaml` version | `0.1.2+4`         |
| iOS bundle id          | `kz.pharmacy.app` |
| Android application id | `kz.pharmacy.app` |
| Display name           | `Epharm`          |

Generated APK/IPA/app zip artifacts should not be committed unless there is an explicit release handoff.

The legacy device build `Epharm-iOS-0.1.1+2-Runner.app.zip` is a Personal Team development artifact
whose stated profile expiry was 2026-08-24. It is expired and must not be distributed. Use TestFlight
and the paid-team procedure in `docs/IOS-DISTRIBUTION.md` for real users.

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

1. runs `flutter clean`;
2. runs `flutter pub get`;
3. builds Android release APK with `USE_API=true` and requires the private release keystore;
4. builds an unsigned iOS review bundle with `--no-codesign`;
5. writes review artifacts to `builds/`.

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

Distribution IPA requires the paid-team setup from `docs/IOS-DISTRIBUTION.md`:

```bash
export APPLE_DEVELOPMENT_TEAM=ABCDE12345
API_BASE=https://epharm.inkar.kz ./tools/build-ios-release.sh
```

## iOS xattr / iCloud Note

This workspace is under Desktop/iCloud on the main machine. If codesign fails with:

```text
resource fork, Finder information, or similar detritus not allowed
```

move the checkout/build output outside iCloud-synced folders and rebuild from clean source. Never
weaken signature validation with `--no-strict`.

## Install

Android:

- transfer APK to device;
- allow install from source;
- open APK.

iOS unsigned `.app` is for build inspection only and cannot be installed as a release artifact.
For a developer-only device run:

- open `ios/Runner.xcworkspace` in Xcode;
- select a development team and a registered device;
- run the `Runner` scheme.

TestFlight/App Store:

- requires paid Apple Developer Program and signed IPA.
