# Mobile Working Notes

## Operational Contract

- For every code or deployable artifact change, run the relevant local checks first.
- After checks pass, deploy the affected part to the shared server unless the user explicitly says not to deploy.
- After deploy, verify production health and the exact changed surface.
- For POSM client changes, build/publish the win-x64 package, register or place the release where POSM auto-update can download it, and verify `/api/posm/app/version`.
- For POSM client changes, deploy the current `App` and `Models` source folders to the shared server first; the ZIP is a derived test/distribution artifact and must not be the only deployed copy.
- Do not move secrets into new files or logs; keep existing keys/passwords only where they already belong.

Current working memory for the Flutter mobile app. Historical implementation notes were removed from
this file; use git history if an old decision needs forensic detail.

## Product

- Product name in UI: Epharm.
- Package/project name remains `pharmacy`.
- Bundle/application id: `kz.pharmacy.app`.
- Current shared backend: `https://epharm.78-140-246-238.sslip.io`.
- Default code `API_BASE` is `https://api.epharm.kz`; for demo/pilot builds pass the sslip host.

## Stack

- Flutter 3.27 / Dart 3.6.
- Riverpod for app/server state.
- go_router for routing.
- `http` for backend.
- `flutter_secure_storage` for tokens/onboarding/card default.
- `cached_network_image` + `flutter_cache_manager` for storefront/banner image caching.
- `camera` / `image_picker` for receipt photo.
- `flutter_svg` for logo.

## Current Features

| Feature | Current behavior |
| --- | --- |
| Splash/welcome | Splash resolves tokens/onboarding before showing welcome. |
| Auth | Phone -> OTP -> register if needed. Dev OTP is `544544` while backend allows it. |
| Home | Public banners/promotions, balance for logged-in user, filters/sort, scan FAB. |
| Promotions | Active backend campaigns from `/api/mobile/promotions`. |
| Product detail | Detail sheet, image gallery, Q&A, bonus CTA, recommendations. |
| Receipts | Photo + bonus card + optional claimed promo id. No manual pharmacy/promo picker. |
| Receipt list/detail | Pull-to-refresh, auth-aware errors, status/detail sheets. |
| Profile pages | FAQ, instruction, cooperation, terms, privacy. |

Cancelled/removed:

- QR/OFD receipt validation.
- OCR-based receipt parsing.
- Manual pharmacy selection in the receipt flow.
- Manual promo grid selection in the receipt flow.
- Separate catalog route on Home; catalog/product detail is reached through campaign/product surfaces.

## Architecture

```text
lib/
├── core/
│   ├── config/api_config.dart
│   ├── network/       # ApiClient, token/card/image cache stores
│   ├── router/
│   ├── storage/
│   ├── theme/
│   ├── validation/
│   └── widgets/
└── features/
    ├── auth
    ├── catalog
    ├── home
    ├── profile
    ├── profile_pages
    ├── promotions
    ├── receipts
    └── welcome
```

Repository pattern remains:

- API implementation when `USE_API=true`;
- mock implementation when `USE_API=false`.

## Current Design

- Coral/cream Claude-style palette.
- Compatibility names remain `brandGreen*`/`brandBlue*`; values are coral.
- Green is semantic success only.
- Manrope is primary font.
- Always provide fallback for `₸` where inline styles may bypass theme inheritance.
- Bottom nav: Home/Profile icon-only tabs + center scan FAB.

Design files:

- `_reference/design-tokens.md`;
- `lib/core/theme/*`;
- `lib/core/widgets/*`.

## Image Pipeline

Use `MediaImage` for Medusa/banner images in scrolling contexts.

It provides:

- `/api/media/img` proxy for raw HTTP Medusa images;
- disk cache `epharm_media`;
- tuned in-memory cache;
- decode cache width.

Do not reintroduce plain `Image.network` for hot list/grid storefront images unless there is a specific
reason.

## Receipt Flow

Current `ReceiptDraft`:

```dart
ReceiptDraft {
  photoPath,
  card,
  promoIds
}
```

`promoIds` is set only by a bonus CTA entry path and is cleared for normal FAB/upload entry. This
prevents orphan promo ids from sticking to unrelated receipts.

Submit sends multipart photo and optional `promoIds`.

## Auth / Token Gotchas

- `ApiClient` uses single-flight refresh to avoid concurrent 401 refresh races.
- Transient refresh errors must not clear tokens; only explicit auth failures do.
- Receipt list distinguishes `UNAUTHORIZED`/401 and offers login again.

## iOS Build Gotchas

Project under Desktop/iCloud can get xattrs that break codesign.

Recreate shim after reboot:

```bash
mkdir -p /tmp/codesign_shim
printf '#!/bin/sh\nexec /usr/bin/codesign --no-strict "$@"\n' > /tmp/codesign_shim/codesign
chmod +x /tmp/codesign_shim/codesign
export PATH="/tmp/codesign_shim:$PATH"
```

Prefer `flutter run` / `flutter build`; do not rely on raw Xcode Run before Flutter artifacts exist.

## Commands

```bash
# demo backend
flutter run --dart-define=USE_API=true --dart-define=API_BASE=https://epharm.78-140-246-238.sslip.io

# local backend
flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://localhost:8080

# offline
flutter run --dart-define=USE_API=false

# checks
flutter analyze lib test
flutter test
```

Build demo artifacts:

```bash
API_BASE=https://epharm.78-140-246-238.sslip.io bash builds/build_all.sh
```

## Definition of Done

- Reproduce bug first when feasible.
- Keep change scoped.
- Update tests.
- Run `flutter analyze lib test` and `flutter test`.
- Update this file or maintained docs after non-trivial behavior/design changes.
