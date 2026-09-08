# iOS Distribution and TestFlight

The repository contains no certificate, provisioning profile, `.p8` key, account password, or fixed
Apple team. `kz.pharmacy.app` remains the production bundle identifier, while the paid team is injected
outside Git through `APPLE_DEVELOPMENT_TEAM`.

## Account owner setup

1. Enroll the organization in the paid Apple Developer Program.
2. Register or transfer the explicit App ID `kz.pharmacy.app` to that team.
3. Create the matching App Store Connect app record and accept active agreements.
4. Add the paid account in Xcode and allow Xcode to manage signing. Ensure Keychain contains a valid
   `Apple Distribution` identity for the team.
5. Store the distribution certificate, profile and App Store Connect API key only in the protected
   `mobile-production` GitHub environment. Never add them to this repository or to ordinary Flutter
   `--dart-define` values.

The manual-dispatch workflow `.github/workflows/mobile-testflight.yml` builds only an existing
immutable `vX.Y.Z` tag and expects these environment secrets:

- `IOS_DISTRIBUTION_CERTIFICATE_BASE64`, `IOS_CERTIFICATE_PASSWORD`, `IOS_KEYCHAIN_PASSWORD`;
- `IOS_PROVISIONING_PROFILE_BASE64`, `APPLE_DEVELOPMENT_TEAM`;
- `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_PRIVATE_KEY_P8`;
- optional Sentry upload values documented in the workflow.

Protect the environment with a required reviewer. The workflow creates ignored signing/export files
on the ephemeral runner and uploads the IPA to TestFlight; no signing material is retained in Git.

## Build a distribution IPA

Every App Store Connect upload needs a unique, monotonically increasing build number. Update
`version:` in `pubspec.yaml`, then run on a trusted macOS release machine:

```bash
export APPLE_DEVELOPMENT_TEAM=ABCDE12345
API_BASE=https://epharm.inkar.kz ./tools/build-ios-release.sh
```

The script creates ignored local signing/export configuration, builds an `app-store-connect` IPA and
performs strict signature verification. It intentionally stops when only an Apple Development/Personal
Team identity is installed.

Upload the resulting IPA with Xcode Organizer or Apple's Transporter, wait for processing, complete
export-compliance/privacy metadata, and distribute to an internal TestFlight group first. External
testers require Apple's beta review. Production rollout requires product-owner approval and a green
P0 merge gate for the exact source revision.

## Acceptance evidence

Keep these values in the release record, not in Git:

- Git commit and `pubspec.yaml` version/build number;
- paid Team ID and App Store Connect build id;
- signature authority reported as Apple Distribution;
- provisioning profile expiry and application identifier ending in `kz.pharmacy.app`;
- TestFlight install/login/catalog/receipt-camera/QR smoke results on a clean device;
- rollback decision and the previous approved TestFlight build.

Never work around `codesign` with `--no-strict`. If extended attributes from a synced folder break
signing, use a non-synced checkout/build directory and rebuild from clean source.
