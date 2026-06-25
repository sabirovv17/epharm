# Fonts

Current font rules for Epharm mobile/admin.

## Primary Font

Manrope is the primary UI font for both mobile and admin.

Mobile bundles Manrope locally under `assets/fonts/Manrope/`.
Admin loads `@fontsource/manrope`.

Use weights 500/600/700/800. Avoid adding new font families unless the design tokens are updated.

## Admin Mono Font

Admin uses JetBrains Mono for:

- key caps;
- tabular IDs/numbers when needed;
- dense chart/table numeric affordances.

Admin dependency: `@fontsource/jetbrains-mono`.

## Platform/System Fallbacks

Use system fallback where needed:

- iOS chrome/text fallback: `-apple-system`, SF Pro;
- Android fallback: Roboto/system;
- KZT glyph `₸`: include Roboto/system fallback in inline Flutter text styles that may bypass the
  global theme.

## Current Typography Sources

- Mobile: `_reference/design-tokens.md` and `lib/core/theme/app_typography.dart`.
- Admin: `admin-panel/design-tokens-admin.md` and Tailwind config.

## Gotchas

- Manrope subset may not contain `₸`; always ensure fallback for money labels.
- Android Cyrillic text fields should disable autocorrect/suggestions and include Roboto/system fallback
  to avoid IME replacement issues.
- Do not use Google Fonts runtime download in Flutter mobile; the app should work in restricted/offline
  network conditions.
