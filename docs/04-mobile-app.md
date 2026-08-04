# Mobile App

Path: `lib/`.

Stack:

- Flutter 3.27 / Dart 3.6;
- Riverpod 2.6;
- go_router 14.6;
- `http`;
- `flutter_secure_storage`;
- `camera`, `image_picker`;
- `cached_network_image` + `flutter_cache_manager`;
- `flutter_svg`, `url_launcher`, `pinput`.

The app defaults to the real backend:

```dart
ApiConfig.useApi == true
ApiConfig.baseUrl == https://epharm.inkar.kz
```

For the current shared demo, always pass:

```bash
--dart-define=API_BASE=https://epharm.inkar.kz
```

Offline mocks remain available with `--dart-define=USE_API=false`.

## Features

| Feature       | Path                     | Current role                                                                        |
| ------------- | ------------------------ | ----------------------------------------------------------------------------------- |
| Welcome/start | `features/welcome`       | Splash decides route from persisted tokens/onboarding state before showing welcome. |
| Auth          | `features/auth`          | Phone OTP, registration form, token storage.                                        |
| Home          | `features/home`          | Balance card, banners, filters, promo grid, refresh/resume sync.                    |
| Promotions    | `features/promotions`    | Active campaign feed from `/api/mobile/promotions`, filters/sort.                   |
| Catalog       | `features/catalog`       | Product detail sheet, recommendation sections, image gallery/card components.       |
| Receipts      | `features/receipts`      | Upload photo, bonus card capture, claimed promo id, receipt list/detail/status.     |
| Profile       | `features/profile`       | `/api/mobile/me` profile/balance refresh.                                           |
| Profile pages | `features/profile_pages` | FAQ, instruction, cooperation, terms, privacy, video instruction.                   |
| Training      | `features/training`      | Assigned routes, event selection/QR, progress, notifications, certificates/rewards. |

## App Flow

1. `SplashScreen` checks persisted tokens/onboarding.
2. User enters app/welcome/home.
3. Public home can show banners/promotions without login.
4. Bonus/receipt-sensitive actions require auth.
5. Auth flow: phone -> OTP -> optional registration -> home.
6. Work shifts are managed only in Standard-N/company software on the pharmacy workstation; the
   mobile application does not start or end shifts.
7. Home grid shows active promo campaigns.
8. Product sheet can show bonus CTA if active campaign exists.
9. Upload prompt opens camera/gallery.
10. Receipt review currently requires the card step; campaign id can be carried from the bonus CTA.
11. Submit uploads multipart receipt to backend.
12. Receipt list/detail shows moderation status; pull-to-refresh updates.
13. Profile -> Training shows only the current pharmacist's assigned programs. The pharmacist can
    start available stages, select an eligible event and check in by QR, but cannot change format or
    record an assessment result.

QR/OFD scanning was removed by product decision. Receipt validation is not based on OCR.

## Data Layer

Each domain keeps repository interfaces with API and mock implementations. Providers select by
`ApiConfig.useApi`.

Examples:

- `AuthRepository` -> `/api/mobile/auth/**`;
- `ReceiptRepository` -> `/api/mobile/receipts`;
- `CatalogRepository` -> `/api/mobile/catalog/**`;
- `PromotionsRepository` -> `/api/mobile/promotions`;
- `MeRepository` -> `/api/mobile/me`;
- `BannerRepository` -> `/api/mobile/banners`.
- `ApiTrainingRepository` -> `/api/mobile/training/**`.

Tokens and onboarding/card defaults use secure storage. `ApiClient` handles refresh and distinguishes
auth failures from transient refresh failures.

## Images and Performance

Scrollable product/banner images use a shared `MediaImage`/cache pipeline:

- HTTP Medusa images are proxied through `/api/media/img`;
- disk cache bucket: `epharm_media`;
- stale period: 14 days;
- `PaintingBinding.imageCache` is tuned at startup;
- hot list/grid images set cache widths to avoid over-decoding.

Receipt photos and special product gallery paths can still use explicit image logic where needed.

## Design

Current mobile design source of truth:

- `_reference/design-tokens.md`;
- `lib/core/theme/*`;
- `lib/core/widgets/*`.

Current visual direction:

- coral/cream Claude-style brand;
- `brandGreen*` names remain in code, but values are coral;
- semantic success green is kept for approved states;
- Manrope with Roboto/SF fallback for the KZT glyph and platform text edge cases;
- bottom navigation has two icon-only tabs plus a center scan FAB.

## Build and Run

```bash
# shared demo backend
flutter run \
  --dart-define=USE_API=true \
  --dart-define=API_BASE=https://epharm.inkar.kz

# local backend, iOS simulator
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://localhost:8080

# local backend, Android emulator
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://10.0.2.2:8080

# offline mock mode
flutter run --dart-define=USE_API=false
```

`builds/build_all.sh` reads `API_BASE` from the environment and defaults to `https://epharm.inkar.kz`.

```bash
API_BASE=https://epharm.inkar.kz bash builds/build_all.sh
```

## iOS Build Gotchas

The repo lives under Desktop/iCloud on the main workstation. iCloud xattrs can break codesign. The
current project includes `ios/fix_build.sh`; if `/tmp/codesign_shim` disappears after reboot, recreate it
before iOS builds or use the build script.

Do not run the Flutter build pipeline directly from Xcode before `flutter pub get`/`flutter run` has
generated artifacts. Prefer `flutter run` with `ios/Runner.xcworkspace` only for signing inspection.

## Checks

```bash
flutter analyze lib test
flutter test
```
