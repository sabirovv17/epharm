# Mobile Design Tokens

Current source of truth for the Flutter mobile app design.

Implementation lives in:

- `lib/core/theme/app_colors.dart`;
- `lib/core/theme/app_gradients.dart`;
- `lib/core/theme/app_shadows.dart`;
- `lib/core/theme/app_radii.dart`;
- `lib/core/theme/app_typography.dart`;
- `lib/core/widgets/*`.

Historical HTML handoff files may still mention green/blue PharmaPay patterns and the old receipt
flow. Treat this file and Flutter code as current.

## Brand

The current visual direction is Claude-style coral/cream:

- token/class names such as `brandGreen600` remain for compatibility;
- values are coral, not green;
- former blue accent is now a deeper coral;
- real green is reserved for semantic success/approved states only.

## Colors

### Brand Coral (`brandGreen*` names in code)

| Token | Hex | Use |
| --- | --- | --- |
| `brandGreen50` | `#FBF3EE` | Subtle selected/tinted surfaces. |
| `brandGreen100` | `#F8E7DD` | Soft chips, icon tile tint. |
| `brandGreen200` | `#F0C8B4` | Borders/rings. |
| `brandGreen300` | `#E4A485` | Secondary illustration tint. |
| `brandGreen400` | `#E0916B` | Light coral gradient stop. |
| `brandGreen500` | `#DB7F57` | Mid coral gradient stop. |
| `brandGreen600` | `#D97757` | Primary CTA, active nav, main brand stroke. |
| `brandGreen700` | `#BE5A38` | Pressed/strong coral, labels on coral tints. |
| `brandGreen800` | `#9A4427` | Deep outline/accent. |

### Coral Accent (`brandBlue*` compatibility names)

| Token | Hex | Use |
| --- | --- | --- |
| `brandBlue100` | `#F8E7DD` | Soft accent tile. |
| `brandBlue200` | `#F0C8B4` | Disabled/subtle accent. |
| `brandBlue600` | `#BE5A38` | Deep accent, logo stamp/disc. |
| `brandBlue700` | `#A8472A` | Pressed accent. |

### Surfaces and Ink

| Token | Hex | Use |
| --- | --- | --- |
| `paperCanvas` | `#FAF7F2` | App canvas. |
| `paperCard` | `#FFFFFF` | Cards/sheets. |
| `paperInput` | `#F3EEE7` | Inputs, soft rows, placeholders. |
| `ink900` | `#221C16` | Primary text. |
| `ink700` | `#423B32` | Strong body. |
| `ink500` | `#6F665B` | Secondary text. |
| `ink400` | `#9D9388` | Caption/placeholder/inactive icons. |
| `ink300` | `#D4CCC0` | Hairlines. |

### Semantic

- success green: approved/confirmed only;
- amber/gold: warning, bonus/coin, manual moderation;
- red: danger/rejected/error.

## Typography

Primary font: Manrope bundled locally.

Use Roboto/SF/system fallback for KZT glyph and platform text edge cases. Manrope subset may not
contain `₸`.

| Role | Size / line | Weight |
| --- | --- | --- |
| Display/logo | 32-38 | 800 |
| H1 | 26 / 32 | 800 |
| H2 | 22-24 / 28 | 800 |
| List row title | 17 / 22 | 800 |
| Body strong/button | 16 / 22 | 700-800 |
| Body | 14-16 / 20-22 | 600 |
| Caption | 12-13 / 18 | 600 |
| Micro | 11-12 / 16 | 700 |

Android Cyrillic text fields should disable autocorrect/suggestions and provide Roboto/system fallback.

## Shape and Elevation

| Token | Value | Use |
| --- | --- | --- |
| `brXs` | 8 | Small chips/OTP. |
| `brSm` | 12 | Icon tiles. |
| `brMd` | 16 | Cards, rows, inputs. |
| `brLg` | 20 | Panels. |
| `brXl` | 24 | Promo/balance cards. |
| `br2Xl` | 28 | Sheet top corners/header curves. |
| `brFull` | 999 | Pills/circles. |

Current shadow recipes:

- card: soft warm shadow for white cards on cream;
- elevated: sheets/modals;
- fab: coral glow for primary CTA/FAB;
- navTop: top edge of bottom nav.

## Layout

- Screen edge padding: 20px.
- General spacing scale: 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 64.
- Fixed-format widgets (bottom nav, FAB, cards, image cells) must have stable dimensions.

## Current Mobile Patterns

### Navigation

- Two icon-only bottom tabs: Home and Profile.
- Center scan FAB is an action, not a tab.
- Receipt upload is opened from FAB, balance CTA, or bonus CTA.

### Home

- Public home can show banners/promotions before login.
- Promotions are real backend campaigns, not mock product cards.
- Filters/sorting operate on the active promotion pool.
- Pull-to-refresh invalidates promotions, banners, and `/me`.

### Product Card / Sheet

- Product images use the media cache/proxy pipeline in scrolling contexts.
- Product detail can show:
  - active campaign bonus CTA;
  - cross-sell with active campaign;
  - related/supplementary recommendations;
  - alternatives;
  - Q&A collapsed section.

### Receipt Flow

Current receipt draft:

```dart
ReceiptDraft {
  photoPath,
  card,
  promoIds
}
```

Manual promo and pharmacy selection are no longer part of the current flow. Pharmacy comes from the
profile/backend context and product/bonus matching is handled by backend/POSM evidence. `promoIds` is
only an optional claimed-campaign hint from a bonus CTA.

### Images

Use `MediaImage` for Medusa-backed images in lists/grids/carousels. It handles:

- HTTP -> HTTPS media proxy;
- disk cache;
- decode cache width.

Do not add another image pipeline for storefront images without a specific reason.
