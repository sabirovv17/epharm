# PharmaPay — Design Tokens

Tokens extracted from the live prototype (`PharmaPay.html` + screens in `src/screens/*`). The system after the **green-primary / blue-secondary swap** — green is the brand colour for headers, CTAs, balance, active tabs; blue is reserved for accents, NEW badges, FAB-style shadows, links and the «Pay» wordmark.

App flow: **Welcome onboarding (3 slides) → browseable Home (unauthed)**. Users can explore the catalog, promos and filters without registering. Tapping any «Войти» CTA — on the Home welcome banner, in Профиль, or on the «Чек» tab — starts the auth flow: **Phone (pinned +7) → SMS (4-digit OTP) → ФИО + ИИН on one screen → Home (authed, balance 0 ₸)**.

> All raw token values live in the Tailwind config inside `PharmaPay.html` (`tailwind.config.theme.extend`).

---

## 1. Colors

### Brand — Green (primary)

| Token | Hex | Use |
|---|---|---|
| `brand/green/800` | `#0B6E42` | Deepest — reserved for outlines/illustrations on darkest surfaces |
| `brand/green/700` | `#0F8F55` | Balance card surface tint, active states on dark gradients, label colour on green-tinted chips, success-banner title |
| `brand/green/600` | `#16C97A` | Primary CTA "Начать" / "Загрузить чек", price pills in tier ladder, active filter chip, **active bottom-nav tab** (icon + label), ring on profile success, filled checklist tile, numeric-keypad action button |
| `brand/green/500` | `#21D17A` | Header gradient mid-stop, period badges on small cards, success ticks |
| `brand/green/400` | `#3DCDA2` | Header gradient top-stop, mint "Войти" OTP submit |
| `brand/green/300` | `#6FDDA0` | Reserved — illustration mid-tone |
| `brand/green/200` | `#A9EBC6` | Hairline border on filled checklist rows and geolocation banner; success-chip ring |
| `brand/green/100` | `#E5F8EE` | Light tint for outline/tag backgrounds; counter pill in catalog filter row; brand-pill on cards |
| `brand/green/50`  | `#F2FBF6` | Subtle surface fill for filled checklist rows; selected list-row in pharmacy picker |

### Brand — Blue (secondary / accents)

| Token | Hex | Use |
|---|---|---|
| `brand/blue/700` | `#1F1FCC` | Deepest blue — pressed state |
| `brand/blue/600` | `#2A2BE2` | `.pay` wordmark accent, NEW badge, training-progress fill, profile-row icon glyph, ringbar in lesson list |
| `brand/blue/500` | `#3F47F0` | Promo carousel outline alt, small-card NEW pill |
| `brand/blue/400` | `#5560FB` | Course-cover gradient |
| `brand/blue/300` | `#8189FF` | Translucent accents |
| `brand/blue/200` | `#C9CCFF` | Subtle blue chips / disabled |
| `brand/blue/100` | `#E8EAFE` | Profile-row icon-tile background, info-pill background |

### Neutrals

| Token | Hex | Use |
|---|---|---|
| `text/primary` (`ink/900`) | `#0F1424` | Headings, body |
| `text/strong` (`ink/700`) | `#2A2F40` | Card body, bonus rows |
| `text/secondary` (`ink/500`) | `#5A6173` | Subtitles, helper |
| `text/tertiary` (`ink/400`) | `#9098A6` | Placeholders, captions, inactive tab |
| `text/hairline` (`ink/300`) | `#C2C7D2` | Dividers, OTP inactive borders |
| `text/onBrand` | `#FFFFFF` | Text on green / blue fills |
| `surface/canvas` (`paper/DEFAULT`) | `#F4F6FA` | Page background |
| `surface/card` (`paper/card`) | `#FFFFFF` | Card / list-row background |
| `surface/input` (`paper/input`) | `#F2F4F8` | Search, sheet rows, brand filter input, OTP box (focus) |
| `border/hairline` | `rgba(15,20,36,0.06)` | Card edges |
| `overlay/scrim` | `rgba(15,20,36,0.45)` | Bottom-sheet backdrop |

### Status / accent

| Token | Hex | Use |
|---|---|---|
| `accent/trophy` | `#F4B73A` | Trophy icon background, contest tag |
| `accent/warning` | `#F1B416` | "Важная информация" promo border-stripe |
| `accent/coin` | `#F4B73A → #B97F11` | Gold coin glyph radial gradient |

---

## 2. Typography

Family: **Manrope** (well-supported Cyrillic, geometric).
Status-bar number specifically uses **SF Pro Display / -apple-system** to look like real iOS chrome.
Fallback stack: `-apple-system, "SF Pro Display", system-ui, sans-serif`.

| Role | Size / Line | Weight | Notes |
|---|---|---|---|
| Display | 32–34 / 38 | 800 | Logo wordmark "PharmaPay", «Добро пожаловать!» |
| H1 | 26 / 32 | 800 | Auth screen headlines ("Введите номер телефона", "Завершите регистрацию") |
| H2 | 22 / 28 | 800 | Screen titles ("Помощь", "О приложении", "Сортировка", "Бренды"), profile name |
| H3 / Title | 18–20 / 24 | 700–800 | List-row title, balance card amount, big-card product name |
| Body-strong | 16 / 22 | 700 | Button labels, filter chip, list-row label |
| Body | 14–16 / 20–22 | 500–600 | Card body, help-text, field labels in auth |
| Caption | 12–13 / 18 | 500–600 | Helper text, dates, restrictions, phone under name in profile |
| Status-bar | 17 / 22 | 600 | iOS-style time (SF Pro Display, `-0.32px` tracking, tabular-nums) |
| Micro | 11–12 / 16 | 600–700 | Tab labels, NEW pill, period badges, length counter `n/12` |

Weights used: **500 / 600 / 700 / 800**.

---

## 3. Radii

| Token | px | Use |
|---|---|---|
| `radius/xs` | 8 | Small chips, OTP boxes |
| `radius/sm` | 12 | Inline chips |
| `radius/md` | 16 | List-row, search input, small product card image |
| `radius/lg` | 20 | Balance card inside header, sheet titles |
| `radius/xl` | 24 | Promo card |
| `radius/2xl` | 28 (`'3xl'` token) | Header bottom curve, bottom-sheet top, product-card outer |
| `radius/full` | 9999 | Buttons, filter pills, tab-bar tabs |

---

## 4. Shadows

| Token | Value | Use |
|---|---|---|
| `shadow/card` | `0 1px 2px rgba(15,20,36,0.04), 0 4px 16px rgba(15,20,36,0.06)` | List rows, white cards |
| `shadow/elevated` | `0 4px 8px rgba(15,20,36,0.06), 0 12px 32px rgba(15,20,36,0.10)` | Bottom sheets, modals |
| `shadow/fab` | `0 8px 20px rgba(22,201,122,0.35)` | Primary CTA, active filter chip, tier pills (green glow) |
| `shadow/navTop` | `0 -2px 12px rgba(15,20,36,0.05)` | Top edge of bottom-nav |
| `shadow/inset-glass` | `inset 0 0 0 1px rgba(255,255,255,0.18), inset 0 1px 0 rgba(255,255,255,0.22)` | Translucent header pills ("История", "Загрузить чек") |

---

## 5. Spacing — 4-pt scale

`4 · 8 · 12 · 16 · 20 · 24 · 28 · 32 · 40 · 48 · 64`

Common layouts:
- Screen edge padding: **20 px** (`px-5`)
- Card inner padding: **16 px** (`p-4`)
- Vertical rhythm between blocks: **16–24 px**
- Section header → first card: **8–12 px**
- Bottom-nav height: **~84 px** (incl. home indicator, `pt-2 pb-7`)
- iOS status bar slot: **54 px** tall, time at left (pl-7), icons at right (pr-6, gap-6)

---

## 6. Components

### 6.1 Buttons

| Variant | Surface | Text | Radius | Height | Notes |
|---|---|---|---|---|---|
| Primary green | `brand/green/600` | white, 18/700 | pill | 60 | "Начать", main CTA on cards/sheets |
| Primary blue | `brand/blue/600` | white, 18/700 | pill | 60 | "Загрузить чек" inside detail sheet, "Применить" on brand filter |
| Mint OTP | `brand/green/400` | white, 18/700 | pill | 56 | "Войти" submit on phone & OTP screens |
| White on green-header | `#FFFFFF` | `brand/blue/600`, 20/700 | 16 px | 64 | "Войти" on welcome-gate |
| Glass pill (on header) | `rgba(255,255,255,0.16)` + inner-glass shine | white, 15–16/600 | pill | 52 | "История", "Загрузить чек" |
| Filter chip — active | `brand/green/600` fill, `shadow/fab` | white, 16/700 | pill | 44 | Single chip can be active at a time |
| Filter chip — inactive | white, `shadow/card` | `ink/900`, 16/700 | pill | 44 | |
| Ghost link | transparent | `brand/blue/500`, 14–16/600–700 | — | — | "Конкурсная акция", "Сбросить" |
| Disabled | same surface, `opacity: 0.5`, no interaction | — | — | — | Used until phone has 11 digits / IIN has 12 |

### 6.2 Inputs

- **Phone field — pinned `+7` prefix**
  - Surface white, radius 16, height 64 px
  - Left block: non-editable `+7` (22/700, `ink/900`, `pl-4 pr-2`, `select-none`)
  - Right side: editable national-digits input, placeholder `(___) ___-__-__`
  - Auto-formats as `(XXX) XXX-XX-XX` while typing; the canonical full number is stored as `+7 (XXX) XXX-XX-XX`
  - `type="tel"`, `inputMode="tel"`, `autoComplete="tel-national"`
  - Focus ring: 2 px `brand/blue/300` (applied to whole pill via `focus-within:ring`)
  - Auto-focuses on screen mount
  - Submit enabled only when 10 national digits are present
- **ФИО field**
  - Surface white, radius 16, height 60 px, padding 16 horizontal
  - Above: white label "Введите ваше ФИО" (14/600, `px-1 mb-1.5`)
  - Body 18/700 `ink/900`, placeholder `Иванов Иван Иванович` in `ink/300`
  - `autoComplete="name"`, `spellCheck={false}`
  - Considered valid when ≥ 2 whitespace-separated tokens
- **IIN field**
  - Surface white, radius 16, height 60 px
  - Above: white label "Введите ИИН"
  - Body 20/700 `tracking-[0.18em]`, trailing counter `n/12` in `ink/400` 12/700
  - 12-digit Kazakhstani identifier; numeric-only; submit on the combined screen requires ФИО valid **and** IIN length === 12
- **OTP boxes** — 56×72, radius 16, white surface, 28/800 centred digit, focus ring `brand/green/400` 2 px. Auto-advance to next box on input.
- **Search input** — `surface/input`, height 56, radius 16, leading search glyph 22 px, placeholder `ink/400`.

### 6.3 Cards

| Card | Surface | Padding | Radius | Shadow |
|---|---|---|---|---|
| List row (profile) | white | 16 | 16 | `shadow/card` |
| Small product | white | 12 | 16 | `shadow/card` |
| Big product | white | 16 | 28 | `shadow/card` |
| Promo (carousel) | per-promo bg | varies | 24 | `shadow/card` + 2 px inset outline `brand/blue/500` |
| Balance (on green header) | `bg-brand-green-700/40` | 16 | 24 | inner 1 px white-10 border |
| Progress (training) | `bg-brand-green-700/40` | 16 | 24 | as above |

### 6.4 List rows (profile)

- Surface white, radius 16, height 64, padding 16, `shadow/card`
- Leading icon tile: 40×40, radius 12, `brand/blue/100` tint, glyph in `brand/blue/600`
- Trailing chevron in `ink/400`

### 6.4a Profile header (authed)

- Avatar circle 56×56, 2 px white-60 border, user glyph 30 px white
- Right of avatar: **ФИО** as title — 22/800 white, line-height tight, `text-wrap: balance`
- Phone below name — 14/600 white opacity-85, `tabular-nums`
- ИИН is **not** rendered in the profile (kept only inside the registration flow)
- Two glass pills below the header row: «История» and «Конкурсы»

### 6.4b Profile header (unauthed)

- «Войдите в аккаунт» welcome card on the green header:
  - Surface `bg-brand-green-700/40`, radius 24, padding 20, border 1 px white-15
  - Title 22/800 white, subtitle 13/500 white-85 («Чтобы видеть баланс, историю чеков и участвовать в конкурсах»)
  - CTA: 56-pill white-on-`brand/green/600`, 18/800, `shadow/fab`

### 6.5 Filter chips — see Buttons table above

### 6.6 Bottom navigation — 2 icon-only tabs + center scan FAB

Evolution: **4 tabs → 3 tabs → 2 tabs + center FAB → icon-only + compact** (all 2026-05). Each iteration removed weight whose role was better served elsewhere:
- «Чек» tab removed when receipt entry-point moved to the BalanceCard pill «Загрузить чек».
- «Обучение» tab removed when the section became a stub-only destination and was moved into the Profile menu as the first row under «Помощь».
- Camera (primary action — «сфоткать чек») was promoted from a hidden pill into a center FAB so the most frequent operation sits under the thumb.
- Text labels («Главная» / «Профиль») removed: with only two tabs the icons are unambiguous (house / person), and removing the labels frees vertical space + lets the icons size up for visual weight.

Layout:
- White background, top edge `shadow/navTop`
- **Compact padding**: container `padding-top: 6, padding-bottom: 8`, then a `SafeArea(top: false)` so the OS home-indicator gets the gesture-safe inset but we don't over-pad on Android. Inner row `height: 48` (was 56 when labels were present). Total bar height ≈ 62 px + SafeArea inset.
- Two tab cells (equal `Expanded`) with an empty center slot reserved for the FAB. The FAB is a `Positioned` element on top, offset `top: -24` so half of it lifts above the bar.
- Tab destinations: **домик** (Home, index 0, left) / **человечек** (Profile, index 1, right)
- Center scan button:
  - 64 × 64 circle, fill **`brand/green/600`**, 4 px white ring, `shadow/fab` glow
  - Glyph: `photo_camera_rounded` (Material rounded) 28 px white
  - Tap → opens the same `UploadPromptSheet` as the BalanceCard «Загрузить чек» pill (camera / gallery / QR)
  - **Not a tab destination** — does not change the active tab index. It is a pure action.
- Tab styling:
  - **Icon size 34 px** (bumped from 28 after labels were removed — the extra ≈ 22 px of vertical real estate goes into the glyph for visual weight)
  - No text labels rendered. Semantic label («Главная» / «Профиль») passed through `Semantics.label` for screen readers / accessibility.
  - **Active**: `*_rounded` filled variant (`Icons.home_rounded`, `Icons.person_rounded`) in `brand/green/600` (switched from `brand/blue/600` in 2026-05-26 to keep the system anchored to the primary brand colour)
  - **Inactive**: `*_outlined` thin variant (`Icons.home_outlined`, `Icons.person_outline`) in `ink/400`
  - The filled/outlined contrast is the active-state cue (instead of color-only) — when you tap, the icon literally fills in.
- Android variant (Material 3 NavigationBar): same icon-only treatment, active icon sits inside a 64×32 pill in `#C9F2DE` (green-50 tint), icon in `brand/green/700`. Center FAB lifts above the bar the same way.

### 6.7 Bottom sheets

- Surface white (or `grad-receipt` green for upload prompt)
- Top corners radius 28 (`rounded-t-3xl`)
- Grabber 36×4 px `ink/300`
- Padding: 24 px top, 20 px sides, 32 px bottom (safe area)
- Backdrop `overlay/scrim` (`rgba(15,20,36,0.45)`)
- Used for: Sort, Brands, Upload-prompt, Product-detail

### 6.8 Tier ladder (product cards)

- Row of `n` pill prices (≥1, typically 3): `brand/green/600` background, white 15/800 text, height 34, min-w 64, radius pill, `shadow/fab`
- Single horizontal hairline divider (1.5 px, `paper/input`) connects all pills
- Gift-emoji glyph (28 px) sits centred over the divider between each pair of pills
- Labels under each pill in `ink/700`, 13/700

### 6.9 FAQ screen — «Вопросы и ответы»

Opened from Profile → «Помощь» → «Вопросы и ответы». A pale lavender-blue canvas with a list of expanding white question cards.

- **Canvas** — `#EFF3FB` (one-off page background; lighter than `surface/canvas` because the screen is read-heavy and white question cards need contrast)
- **Title** — 26/800 `ink/900`, centred, 12 px below the status bar / back arrow
- **Back chevron** — `I.Back` 26 px, `ink/900`, top-left at screen-edge padding
- **Question card (closed)**
  - Surface white, radius **8** px, hairline `inset 0 0 0 1px rgba(15,20,36,0.04)` (no drop shadow)
  - Padding 20 px horizontal × 20 px vertical
  - Question text 17/600 `ink/900`, `text-wrap: balance`
- **Question card (open)**
  - Same surface, radius **16** px, `0 2px 12px rgba(15,20,36,0.06)` + hairline
  - Margin 8 px top + 8 px bottom — the active item lifts off the list
- **Toggle button** — `+` ↔ `×`
  - 24 × 24 box, glyph is a 22 × 22 SVG with two 16-px strokes at the centre
  - Stroke **1.4 px** `ink/900`, `stroke-linecap: square`
  - Closed = `+` (rotate 0°). Open = same glyph rotated **−45°** to read as `×`.
  - Transition: `transform 280ms cubic-bezier(0.2, 0.8, 0.2, 1)`
  - Aligned to the top of the question (`mt-0.5`) so it sits with the first text line in multi-line questions
- **Answer panel**
  - Padding 20 px horizontal × 24 px bottom, 4 px top gap from the question
  - Max-height transition: `420ms` ease-out on open, `260ms` on close; opacity cross-fades
  - Body text 15/400 `ink/900`, `leading-snug` (1.4)
  - Bullets — 5 × 5 px black disc, 12 px gap to text, items separated by 12 px gap
  - Inline accent links — `brand/green/400` (#3DCDA2), weight 600, no underline. Render via the `FaqText` helper using `[link]…[/link]` markers in the data string. **Bold** runs use `**…**` markers.
- **Video instruction footer**
  - Centred label «Посмотрите видео-инструкцию» — 16/600 `ink/900`
  - 16:9 video card below: blue gradient (`#1147A8 → #1E70D8 → #29A5E0`), 24 px radius, `shadow/card`
  - YouTube-style red play button: 56 × 40, radius 6, `#FF0033`, centred white triangle

### 6.10 iOS status bar (chrome)

- Height **54 px**, transparent over the screen content
- Time **9:41**, SF Pro Display 17/600, tracking `-0.32px`, tabular-nums, aligned `pl-7`
- Right cluster — gap 6 px, padded `pr-6`:
  - Cellular: 4 progressively-taller rounded bars (17×11 viewbox, all filled)
  - Wi-Fi: 3 concentric arcs (16×11)
  - Battery: hairline pill (25×12, 1 px outline @ 55% opacity, radius 3.5) + 2×5 tip, **solid fill bar** representing charge, no % number
- All glyphs inherit `currentColor` — pass `dark` prop for white-on-gradient screens

### 6.11 Device frame

- iPhone 14 viewport 390×844
- Outer bezel radius 56, inner screen radius 48
- Dynamic Island: 110×34, black, top 8 px, centred
- Outer shadow: `0 50px 100px rgba(0,0,0,0.6), inset 0 0 0 1px rgba(255,255,255,0.05), inset 0 0 0 2px rgba(0,0,0,0.6)`

---

## 7. Logo — Receipt Stamp (canonical)

The brand mark is **Receipt Stamp**: a stylised pharmacy receipt with the medical cross stamped over the top — a literal visualisation of "scan your receipt, get a bonus".

> Source: `logo/marks.jsx` → `MarkReceiptCross`. Designed in a 64×64 viewBox.

### 7.1 Anatomy

| Part | Role | Spec |
|---|---|---|
| Receipt body | Recognition silhouette | White fill, **3 px** stroke in `brand/green/600`, rounded stroke joins. Zig-zag bottom edge (5 V-notches) for "receipt" reading. |
| Receipt lines | Suggest content | 3 horizontal rules, `brand/green/600` @ 45% opacity, radius 1.2, lengths 20 / 14 / 17 (descending) |
| Stamp disc | Brand accent | 11-radius circle in `brand/blue/600`, overlaps top edge of receipt (centred at x=32, y=14) |
| Cross | Primary brand symbol | White, 3 px wide × 14 px tall vertical + 14 wide × 3 tall horizontal arms, radius 0.8 |

### 7.2 Colours

The mark is **two-colour** and uses tokens already in the palette — no new hexes introduced.

| Element | Token | Hex |
|---|---|---|
| Receipt body fill | `surface/card` | `#FFFFFF` |
| Receipt body outline + lines | `brand/green/600` | `#16C97A` |
| Stamp disc fill | `brand/blue/600` | `#2A2BE2` |
| Cross fill | `text/onBrand` | `#FFFFFF` |

#### Variants

- **On light surfaces** — default colours above.
- **On green surfaces** (`grad-blueHead`, success cards) — invert: receipt stroke + lines become white, stamp stays `brand/blue/600`, cross stays white. Set `color="#FFFFFF"` on `<MarkReceiptCross/>`.
- **On dark surfaces** — same as default; the white receipt body provides the contrast.
- **Monochrome** (single-colour print / favicon at small sizes) — entire mark uses one of: `ink/900`, `text/onBrand`, or `brand/green/600`. Stamp and receipt become the same fill; receipt lines drop to 30% opacity.

### 7.3 Sizing & padding

- **Design grid** — 64 × 64 viewBox. Stamp diameter ≈ 22 (~34% of canvas). Receipt body 32 × 43.
- **Minimum size** — 24 px (smaller and the receipt lines stop reading).
- **Favicon (16 / 32 px)** — drop the receipt lines, drop stroke to 2 px. Below 16 px use the cross-on-disc only.
- **Clear space** — at least **8 px** in 64-unit space (≈ 12.5% of mark side) free of any other graphic element on every side.

### 7.4 Lockups

- **Horizontal** — mark on the left, wordmark **PharmaPay** to the right. Gap = 12 px at 56-px mark. Wordmark cap height ≈ 70% of mark height. Use this in headers, email signatures, the in-app top bar.
- **Stacked** — mark above, wordmark centred below. Gap 8 px at 88-px mark. Use on launch screens and splash.
- The mark also stands **alone** as the app icon, tab-bar glyph and avatar at sizes ≥ 40 px.

### 7.5 App icon

- iOS squircle tile, radius ≈ 22.4% of side.
- Default tile background: `grad/header` (green gradient) — mark rendered in white-receipt-on-blue-stamp mode (`color="#FFFFFF"`).
- Alt tiles approved: white, `brand/blue/600`, `ink/900` (all with the white-receipt + blue-stamp variant).
- The mark sits centred at ≈ **66%** of tile width.

### 7.6 Don'ts

- Don't recolour the stamp anything other than `brand/blue/600` (or white-on-blue invert).
- Don't add a drop shadow to the mark itself — the surface it sits on already carries `shadow/card`.
- Don't rotate the receipt or the stamp; both stay axis-aligned.
- Don't separate the stamp from the receipt — they always read as one composite mark.
- Don't fill the receipt body with a colour other than white; the white interior is what makes the receipt read.

---

## 8. Key gradients

| Token | Value |
|---|---|
| `grad/welcome` | `linear-gradient(180deg, #3DCDA2 0%, #21D17A 50%, #16A65C 100%)` |
| `grad/header` (`grad-blueHead`) | `linear-gradient(180deg, #21D17A 0%, #16A65C 100%)` |
| `grad/receiptSheet` | `linear-gradient(150deg, #3DCDA2 0%, #21D17A 50%, #16A65C 100%)` |
| `grad/courseCover-blue` | `linear-gradient(135deg, #2A2BE2 0%, #5560FB 100%)` |
| `grad/courseCover-green` | `linear-gradient(135deg, #16A65C 0%, #21D17A 100%)` |
| `grad/coin` | `radial-gradient(35% 35%, #FFE07A 0%, #F4B73A 60%, #B97F11 100%)` |

---

## 9. Naming conventions

- Tailwind palette: `brand-green-{50…800}`, `brand-blue-{100…700}`, `ink-{50…900}`, `paper-{DEFAULT|card|input|hover}`.
- Custom shadows: `shadow-card`, `shadow-elevated`, `shadow-fab`, `shadow-navTop`, `shadow-sidebar`, `shadow-kbd`.
- Custom gradients are class names (`grad-welcome`, `grad-blueHead`, `grad-receipt`) defined in the `<style>` block of `PharmaPay.html`.
- Admin console adds prefixed utility classes: `.card`, `.card-soft`, `.tbl`, `.btn-*`, `.inp`, `.chip-*`, `.toggle`, `.tab`, `.kbd`, `.sidebar-bg`, `.topbar-bg`, `.heat-{0..4}` — all defined in the `<style>` block of `PharmaPay Admin.html`.

---

## 10. Admin Console (HQ web)

The HQ admin console (`PharmaPay Admin.html`) is a separate surface from the mobile app — a **desktop web** product for HQ Inkar, Ledex category management and brand managers. It **extends** the mobile design system rather than replacing it: same brand green / blue / ink, same Manrope, same logo. It adds the missing pieces a data-dense desktop tool needs: dark sidebar chrome, broader ink scale, status/accent palette, tables, dropdowns, toggles, modals, drawers and a keyboard-driven command palette.

> Source-of-truth: Tailwind config + `<style>` block inside `PharmaPay Admin.html`.

### 10.1 Extended palette

#### Brand green — expanded scale

| Token | Hex | Use (admin) |
|---|---|---|
| `brand/green/50`  | `#EDFBF3` | Selected table row, soft success surface, KPI tile background |
| `brand/green/100` | `#D7F5E4` | Success chip background, course-progress fill, heatmap level 1 |
| `brand/green/200` | `#A9EBC6` | Selection highlight, heatmap level 2, ring/border accents |
| `brand/green/300` | `#6FDDA0` | (reserved — illustration mid-tone) |
| `brand/green/400` | `#3DCDA2` | Heatmap level 3, course-cover gradient stop, decorative |
| `brand/green/500` | `#21D17A` | Header gradient mid-stop (shared with mobile) |
| `brand/green/600` | `#16C97A` | Primary CTA, active tab indicator, sidebar-active accent, sparkline stroke, heatmap level 4 |
| `brand/green/700` | `#0F8F55` | Active tab label, success chip text, CTA hover/pressed, sidebar contract widget gradient |
| `brand/green/800` | `#0B6E42` | (reserved — deepest, for outlines on darkest surfaces) |

#### Brand blue — expanded scale

Identical hexes to the mobile spec, but the admin uses these additionally for:
- `brand/blue/100` (`#E8EAFE`) — info chip background, profile-row icon tile, brand-blue mini-card surface
- `brand/blue/200` (`#C9CCFF`) — disabled-blue chips, hairline accents
- `brand/blue/600` (`#2A2BE2`) — info-state chip text, role badge, stamp-disc in logo
- `brand/blue/700` (`#1F1FCC`) — pressed state of blue CTAs

#### Ink — expanded neutral scale

Eleven steps. Used for text, borders, hover surfaces, dark sidebar.

| Token | Hex | Use (admin) |
|---|---|---|
| `ink/50`  | `#F7F8FB` | Empty-state icon tile background, sticky table-header surface |
| `ink/100` | `#EEF0F5` | Hover surface for icon buttons, neutral chip background, heatmap level 0 (no data), key-cap background |
| `ink/200` | `#E1E4EC` | Toggle off track, button outline, vertical separators in topbar |
| `ink/300` | `#C2C7D2` | Drag-handle glyph, dashed-receipt strokes, weak placeholders |
| `ink/400` | `#9098A6` | Caption text, table empty-state icons, chevron-right in rows |
| `ink/500` | `#5A6173` | Secondary body, table-header label, sub-titles |
| `ink/600` | `#3F465A` | Strong neutral text (button-ghost label, kbd text) |
| `ink/700` | `#2A2F40` | Body strong (form fields, helper text) |
| `ink/800` | `#1A1D2B` | Hover state of ink CTAs |
| `ink/900` | `#0F1424` | Headings, table values, dark CTA fill, sidebar-bg base |

#### Paper — surfaces

| Token | Hex | Use |
|---|---|---|
| `paper/DEFAULT` | `#F4F6FA` | App canvas (main content background) |
| `paper/card`    | `#FFFFFF` | All white cards, modal/drawer bodies |
| `paper/input`   | `#F2F4F8` | Soft input variant, command-palette search, fake receipt panel |
| `paper/hover`   | `#F7F9FC` | Row hover, sticky toolbar background, modal footer band |

#### Accent / status

| Token | Hex | Use |
|---|---|---|
| `accent/success`  | `#16C97A` | Same as brand green 600 |
| `accent/warning`  | `#F1B416` | Toggle/Pause status, warning chip |
| `accent/amber`    | `#F4B73A` | Coin glyph, trophy, Platinum-tier chip, OCR-medium score |
| `accent/danger`   | `#E5484D` | Danger button text, error chip text, "block user" CTA |
| `accent/purple`   | `#8B5CF6` | Tertiary accent (chains, course cover variants) |
| Red surfaces      | `#FEE2E2` / `#B91C1C` | Danger button background / pressed-text |
| Amber surfaces    | `#FEF3C7` / `#B45309` | Warning chip background / text |

### 10.2 Typography — admin additions

The admin keeps **Manrope** for everything UI-facing. It introduces a **monospace** family for tabular numbers and code/key-cap glyphs.

```html
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
```

| Role | Family | Size / Line | Weight | Use |
|---|---|---|---|---|
| Page title (H1) | Manrope | 24 / 30 | 800 | `PageHeader` title |
| Section title  | Manrope | 15 / 22 | 800 | `SectionCard` header |
| KPI value      | Manrope | 28 / 28 | 800 | `Metric` value (with `tracking-tight`) |
| Table header   | Manrope | 11 / 14 | 600 (uppercase, `letter-spacing: 0.04em`) | `<th>` text |
| Table body     | Manrope | 14 / 20 | 500–800 | `<td>` text — 800 for primary, 500 for secondary |
| Form label     | Manrope | 12 / 16 | 600 | `Field` label |
| Helper / hint  | Manrope | 12 / 18 | 500 | `Field` hint and error |
| Chip text      | Manrope | 12 / 12 | 600 | `.chip` |
| Sidebar label  | Manrope | 14 / 18 | 600 (700 active) | Nav item |
| Sidebar group  | Manrope | 10 / 12 | 700 uppercase, `letter-spacing: 0.1em`, 40% white | Group header |
| Eyebrow / kicker | Manrope | 11 / 14 | 700 uppercase, `letter-spacing: 0.06em` | Card "step" labels in rule builder |
| Tabular numbers | JetBrains Mono | inherits | 500–600 | Axis ticks, sparkline labels, kbd, receipt mock |
| Key-cap (`kbd`) | JetBrains Mono | 11 / 12 | 600 | `⌘`, `K`, `esc` indicators |

The utility class `.num` applies `font-variant-numeric: tabular-nums` to any text — used on all currency values, percentages, IDs and table numerics.

### 10.3 Spacing & sizing

Same 4-pt scale as mobile. Desktop-specific dimensions:

| Element | Size |
|---|---|
| Sidebar — expanded | **260 px** |
| Sidebar — collapsed | **72 px** |
| Sidebar nav row | height 40 px, padding-x 10 px, gap 12 px |
| Topbar | height 64 px, padding-x 24 px |
| Page content max-width | none — fills viewport (min-width 1280 px enforced on root) |
| Content padding | 24 px horizontal, 0 top (topbar provides separation), 40 px bottom |
| Card padding (default) | 20 px |
| Card padding (compact) | 16 px (`.card-soft`) |
| Vertical rhythm between cards | 16–20 px (`gap-4` or `gap-5`) |
| Table cell padding | 10 px vertical (header), 14 px / 12 px (body) |
| Modal width | 460–620 px depending on form complexity |
| Drawer width | 480–560 px (right-aligned) |

### 10.4 Radii — admin specifics

Same scale as mobile but **smaller defaults**:
- `radius/xs` (4 px) — kbd, chip-dots in heatmap
- `radius/sm` (6 px) — chip, page-step indicator
- `radius/md` (10 px) — buttons, inputs, search pills, list-row icons
- `radius/lg` (12 px) — `.card-soft`, status icon tiles
- `radius/xl` (16 px) — `.card` (default), modal body
- `radius/2xl` (20 px) — modal outer (rounded-2xl), drawer corners
- `radius/full` — toggle, avatar, kbd-dot, chip-dot

### 10.5 Shadows

| Token | Value | Use |
|---|---|---|
| `shadow/card` | `0 1px 2px rgba(15,20,36,0.04), 0 4px 16px rgba(15,20,36,0.06)` | Default elevation on `.card`, list rows |
| `shadow/elevated` | `0 4px 8px rgba(15,20,36,0.06), 0 12px 32px rgba(15,20,36,0.10)` | Modals, dropdowns, toasts, command palette |
| `shadow/fab` | `0 8px 20px rgba(22,201,122,0.35)` | Primary green CTA glow |
| `shadow/sidebar` | `inset -1px 0 0 rgba(15,20,36,0.06)` | Right edge of light sidebar variants (reserved — current sidebar is dark) |
| `shadow/kbd` | `0 1px 0 rgba(15,20,36,0.12), inset 0 -1px 0 rgba(15,20,36,0.08)` | Key-cap depth |

Sidebar-expand tab uses a custom shadow `4px 0 12px rgba(15,20,36,0.18)` to lift the floating chevron off the canvas.

### 10.6 Sidebar

- **Background**: `radial-gradient(120% 80% at 0% 0%, #15192A 0%, #0F1424 60%) #0F1424` (a near-black with a hint of warmth in the top-left corner).
- **Active row** (`.sidebar-active`): `linear-gradient(180deg, rgba(22,201,122,0.18), rgba(22,201,122,0.08))` + `inset 2px 0 0 #16C97A` left accent + icon and label switch to `text-brand-green-400` / white.
- **Hover row** (`.sidebar-hover:hover`): `rgba(255,255,255,0.04)` — barely-there lift.
- **Group headers** — uppercase `font-bold` 10/12 at 40% white opacity, padding-x 16 px, margin-bottom 6 px.
- **Logo block** — 64 px tall, separated from nav by `border-b border-white/5`. Logo tile is 36 px (collapsed) or 36 px + wordmark (expanded).
- **Collapse / expand**:
  - Expanded → chevron-left button in the header collapses it.
  - Collapsed → the logo tile itself becomes a clickable expand button **and** a floating **chevron-right tab** appears on the outer right edge (24 × 48 px, ink-900 fill, `shadow-elevated`). Two paths is intentional — easier discovery.
- **Contract widget** at the bottom of the sidebar — gradient card `bg-gradient-to-br from-brand-green-700/40 to-brand-green-700/10`, displays brand, brand count, % budget used, progress bar, absolute KZT figures. Clickable → opens `ContractModal`.

### 10.7 Topbar

- **Surface** white with `border-bottom: 1px solid rgba(15,20,36,0.06)`. Sticky `top: 0` with `z-index: 30`.
- **Left** — breadcrumb (`HQ › <Section>`).
- **Centre** — fake search input (button styled as input) that opens the **command palette** on click. Includes a `⌘ K` kbd hint on the right.
- **Right** — period selector (Май 2026 ▾), notification bell with red dot, history button, vertical separator, then the **role pill**: avatar + ФИО + role/company stacked, chevron-down. Clicking the pill opens the role-switcher modal.

### 10.8 Buttons (`.btn`)

Sizes: `btn-sm` (h 32), `btn-md` (h 38, default), `btn-lg` (h 44). Icon-only square: `btn-icon` (36 × 36).

| Variant | Background | Text | Border | Use |
|---|---|---|---|---|
| `btn-primary` | `brand/green/600` (hover `brand/green/700`) | white | — | Main CTAs (Save, Create, Approve) |
| `btn-ink`     | `ink/900` (hover `ink/800`) | white | — | Strong secondary (Refresh, dark CTAs) |
| `btn-ghost`   | transparent (hover `ink/100`) | `ink/600` (hover `ink/900`) | — | Cancel, link-like, default icon buttons |
| `btn-outline` | white (hover `paper/hover`) | `ink/900` | `ink/200` (hover `ink/300`) | Tertiary actions, secondary in modals |
| `btn-danger`  | white (hover `#FEE2E2`) | `#B91C1C` | `#FECACA` | Destructive (Block user, Archive) |
| `btn-icon`    | inherits ghost/outline/etc. | — | — | Square icon-only with optional `.tip` tooltip |

All buttons: `font-weight: 600`, `border-radius: 10 px`, `transition: 80–120 ms`, `active:scale(0.985)` for tap feedback.

### 10.9 Inputs (`.inp`)

- Height 38 px, padding-x 12 px, radius 10 px, white surface, `1 px ink-200` border.
- Focus state: `border-color: brand/green/600` + `box-shadow: 0 0 0 3px rgba(22,201,122,0.18)` ring.
- Placeholder `ink/400`. Body text `ink/900`, 14 / 20, 400.
- `<textarea class="inp">` — height auto, padding 10 / 12, min-height 64 px, `resize: vertical`.
- Search variant: `inp-search` adds 36 px left padding for the leading 16-px search glyph. Wrap in a `relative` div with the glyph positioned absolutely.

### 10.10 Select (custom-styled native)

- Same shell as `.inp` with appearance-none + 9-px right padding for a custom `IconChevDown` glyph (positioned absolutely, ink/400, non-interactive).
- Cursor pointer. Placeholder option disabled when value is empty.
- Used everywhere there are ≤ ~10 options; for more options or with search, the command palette pattern replaces it.

### 10.11 Toggle (switch)

- 36 × 20 pill, off background `ink/200`, on background `brand/green/600`.
- 16 × 16 white thumb, top 2 / left 2 px (off), left 18 px (on). `box-shadow: 0 1px 2px rgba(0,0,0,0.15)`.
- Transition 160 ms on both background and thumb position.
- Optional `label` prop renders body-strong 13/600 to the right.

### 10.12 Tabs

- Underline style. Tab height 40 px, padding-x 4 px, gap-between 28 px.
- Inactive: `ink/500`, weight 600. Hover: `ink/900`.
- Active: `brand/green/700` text + `border-bottom: 2 px brand/green/600`.
- Count badge: 11/700 in a 18-px tall pill. Active palette: `brand/green/100` / `brand/green/700`. Inactive: `ink/100` / `ink/500`.

### 10.13 Tables (`.tbl`)

- Full-width `border-collapse: separate`, no border-spacing.
- Header `<th>` — 11 / 700 uppercase, `letter-spacing: 0.04em`, `ink/500`, padding 10 / 12, surface `ink/50`, **sticky top** for scrollable tables.
- Body `<td>` — 14 / 500–800 `ink/900`, padding 14 / 12, `border-bottom: 1 px rgba(15,20,36,0.04)`.
- Row hover: surface `paper/hover`. Selected row: surface `brand/green/50` + `inset 3 px 0 0 brand/green/600` on the first cell.
- Numeric cells: add class `num` for tabular-nums.
- Use chips for status columns; inline `<ProgressBar>` (height 6 px) for percent columns.

### 10.14 Chips (`.chip`)

- Inline-flex, gap 6 px, padding 4 / 10, radius full, font 12 / 600.
- Variants: `chip-green`, `chip-blue`, `chip-amber`, `chip-red`, `chip-ink`.
- Leading dot: `.chip-dot` (6 × 6 round) — used in status chips to reinforce the colour code.
- `StatusChip` mapping: `active → chip-green + #16C97A dot`; `paused → chip-amber + #F1B416`; `draft|archived → chip-ink + #9098A6`; `pending → chip-blue + #2A2BE2`; `rejected → chip-red + #E5484D`; `approved → chip-green`.

### 10.15 KPI tile (`<Metric>`)

- `.card` (white, 16-radius, `shadow/card`), padding 20.
- Top row: label (13 / 600 `ink/500`) on the left, 36 × 36 rounded-12 accent tile on the right with icon. Accent palette: `green | blue | amber | purple | ink`, each tile is `{accent-100}` background with `{accent-700}` icon glyph.
- Value: 28 / 800 `ink/900`, `tracking-tight`, `tabular-nums`.
- Optional `sub` next to value: 14 / 600 `ink/500`.
- Bottom row: `meta` caption (12 / 600 `ink/500`) + optional `delta` chip (`+x.x%` arrow-up in green-chip, or `−x.x%` arrow-down in red-chip).
- Always 4-up in a `grid grid-cols-4 gap-4`.

### 10.16 Sparkline / charts

- `<Sparkline>` — width 64–120 px, height 22–36 px, stroke 1.8 px, optional 12 %-opacity fill. Used inline in tables and metric tiles.
- Big chart in dashboard: 720 × 240 px viewBox, dual-line (shown vs accepted), 5 horizontal grid lines at `rgba(15,20,36,0.06)`, axis labels in JetBrains Mono at `ink/400`, accepted-line dots are 3 px white-fill / green-stroke.
- Heatmap: 12 columns (weeks) × 7 rows (days). Cell aspect 1:1, radius 3 px. Palette `heat-0` (`ink/100`) → `heat-4` (`brand/green/600`).

### 10.17 Modals (`<Modal>`)

- Centered, max-width 460–620 px. Backdrop `rgba(15,20,36,0.45)` + `backdrop-filter: blur(2px)`.
- Body: `.card` + `shadow/elevated`. Header padding 20 / 20 / 12, footer padding 20 / 12 (bg `paper/hover`).
- Header: title 16 / 800 `ink/900`, subtitle 13 / 500 `ink/500` underneath. Close button (icon-ghost) top-right, ESC closes.
- Slide-in animation: `translateX(24px) → 0` over 220 ms `cubic-bezier(0.2, 0.8, 0.2, 1)` with opacity fade.

### 10.18 Drawer (`<Drawer>`)

- Right-aligned, 480–560 px wide, full-height white.
- Slide-in from the right using the same easing as Modal.
- Header padding 20 / 20 / 16 with bottom border. Footer padding 20 / 12 with `paper/hover` background, content area scrolls.

### 10.19 Toast (`<ToastHost>`)

- Bottom-centred, stack with 8 px gap, z-index 90.
- Each toast: `.card` + `shadow/elevated`, padding 10 / 16, min-width 280 px.
- Leading 6-px round dot tinted by kind (`success / error / info`). Body 14 / 600 `ink/900`. Optional action label on the right in `brand/green/700` 13 / 700.
- Auto-dismiss 3.2 s by default. Programmatic `push(msg, { kind, action, duration })`.

### 10.20 Command palette (⌘K)

- Top-aligned 12 vh from viewport top, max-width 560 px.
- Header: 48-tall row with search glyph + autoFocus input + `esc` kbd.
- Body: scrollable list, items show 28-px icon tile + bolded label + caption + right-aligned kind label (`Раздел / Товар / Аптека`).
- Filters across all section names, top 8 products, top 6 pharmacies. Enter on a section navigates to it.
- Triggered by ⌘K / Ctrl+K **or** by clicking the topbar search input.

### 10.21 Drag & drop affordance

- `.drag-handle` cursor `grab` / `grabbing`. Reserved as a leading icon column on draggable rows.
- `.dragging` (applied to the source element during drag) → 0.4 opacity.
- `.drop-over` (applied to the row currently under the cursor) → `inset 0 2px 0 brand/green/600` top hairline as a drop indicator.
- Used in: Rules list reorder, Screen playlists reorder.

### 10.22 Empty / coming-soon

- `<Empty>` — centred, 48-py padding. 56-px square icon tile in `ink/100` / `ink/400`, then title 15 / 800, body 13 / 500 `ink/500`, optional CTA.
- `<ComingSoonBanner>` — full-width blue-tinted card, info icon left, title + body right. Used inside section bodies where data is intentionally a placeholder.

### 10.23 Money & numbers (admin formatting)

- **All currency values are written in full** — `1 842 300 ₸`, never `1.84 М ₸`. Helper: `AD.fmtKzt(n)` returns `new Intl.NumberFormat('ru-RU').format(n) + ' ₸'`.
- Pure counts use `AD.fmt(n)` — same NBSP-thousand grouping without the suffix.
- Apply `.num` (`tabular-nums`) to every cell, badge or metric showing a numeric value — keeps tables aligned across rows.
- Phone numbers and IINs also use `.num`.

### 10.24 Page composition pattern

Every section follows the same skeleton, defined by `<PageHeader>` + a series of `.card`s:

```jsx
<div className="flex flex-col gap-5">
  <PageHeader title="…" subtitle="…" actions={…} />
  <div className="grid grid-cols-4 gap-4">{/* 4 KPI tiles */}</div>
  <div className="grid grid-cols-N gap-4">
    <SectionCard title="…" action={…}> …table or content… </SectionCard>
    …
  </div>
</div>
```

- `PageHeader` is 24/800 title, 14/500 subtitle (max-width 680 px), right-aligned button cluster.
- Grids always use `gap-4` (16 px) or `gap-5` (20 px) — never per-card margins.
- Section cards may render `padded={false}` when they contain a table (table-cell padding takes over).

### 10.25 Surfaces in the admin vs surfaces in the app

A small mental model: in the **mobile app**, the green is "hero" — it bathes the header, welcome card, and primary CTA. In the **admin**, the green is "accent" — it's the active sidebar item, the primary CTA, the success chip, the lift chart line — but the canvas itself is light and quiet. The ink-900 sidebar gives the green something to pop against. Don't fill large surfaces with brand green in the admin — keep it for highlights.

---

## 11. Receipt-upload flow (`src/screens/recipe/`)

> **Flutter status (2026-05)**: полностью реализовано в `lib/features/receipts/`. Соответствия:
> - `UploadPrompt` → `upload_prompt_sheet.dart`
> - `CameraScreen` → `camera_screen.dart`
> - `ReceiptReviewScreen` (3-row checklist) → `receipt_review_screen.dart`
> - `PromoPickerScreen` → `promo_picker_screen.dart`
> - `AddressSheet` → `address_sheet.dart`
> - `CardSheet` → `card_sheet.dart`
> - `SuccessScreen` → `success_screen.dart`
> - draft state → `ReceiptDraftNotifier` (Riverpod) в `application/receipts_controller.dart`
> - mock-аптеки → `data/nearby_pharmacies.dart`


The end-to-end «загрузил чек → отправил» journey is implemented as a small dedicated feature folder. The screens use the standard design tokens, but a few component patterns are unique to this flow.

> Source-of-truth: `src/screens/recipe/upload.jsx`, `src/screens/recipe/review.jsx`. See `src/screens/recipe/README.md` for the state-machine.

### 11.1 Entry-point

The receipt flow **no longer has a bottom-nav tab**. It is reachable only via:

- the «Загрузить чек» **glass-pill** on the authenticated Home balance card (the same component the unauthenticated flow uses for «Войти» → `PHONE`).
- a deep-link from the FAQ / Instruction screens («Перейти к загрузке чека» link).

When the receipt tab disappeared, the bottom-nav active state was re-coloured from `brand/blue/600` to `brand/green/600` to keep the system anchored to its primary brand colour. See § 6.6.

### 11.2 Screen stack

```
HOME → UploadPrompt (sheet) → Camera → ReceiptReviewScreen
                                              ├── PromoPickerScreen
                                              ├── AddressSheet
                                              └── CardSheet
                                                       ↓
                                                  Success
```

### 11.3 `ReceiptReviewScreen` — checklist row pattern

- Layout: 12-tall back-header (`‹ Чек`), then a header row that pairs an **88 × 88 receipt thumbnail** with a green **«Чек загружен»** banner card. Below: a vertical stack of three checklist rows.
- Each row is a full-width tappable card (`rounded-2xl`, `shadow-card`), height ≈ 80 px, padding `px-4 py-4`, gap 16 px between rows.
- **Empty state**: surface `paper/card` (white), leading 48 × 48 tile in `brand/green/100` with the action icon in `brand/green/700`, label 17/800 `ink/900`, sub-label 12/600 `ink/500`, trailing chevron `ink/400`.
- **Filled state**: surface flips to `brand/green/50` + 1-px `brand/green/200` border, leading tile becomes `brand/green/600` with **white check-mark + `shadow/fab`**, label shows the captured value (e.g. «3 акции в чеке», pharmacy name, masked card number), sub-label switches to a confirmation caption.
- Footer: 16-px padding-top gradient fade into the sticky CTA. The CTA is the standard `ButtonPrimary variant="green"` (60-tall pill), disabled until all three rows are filled.
- A small lock-glyph card («Данные защищены — мы используем их только для зачисления выплат») sits between the last row and the CTA — 12/500 `ink/500`, surface `paper/input` at 70 % opacity.

### 11.4 `PromoPickerScreen` — adapted catalog grid

Mirrors the Home catalog (grid of small product cards) with two changes:

- Each card has an inline **«Добавить +» / «Добавлено ✓»** toggle button instead of being a passive tile. Empty: `bg-brand-green-100` + `text-brand-green-700`, height 36. Selected: `bg-brand-green-600` + white text + `shadow/fab` + small white check-mark.
- The card itself gets a 2-px `brand/green/600` ring + `bg-brand-green-50` body when selected, plus a 28-px white-ringed check disc in the top-right corner of the image.
- Bottom CTA «Добавить · N» sticks above the safe area on a `from-white via-white/95 to-transparent` gradient fade. Disabled when nothing is selected (label switches to «Выберите акции»).
- Filter row reuses the Home `SearchInput` + sort/brand buttons + `FilterChip` chips (Все / Новинки / Конкурсные).

### 11.5 `AddressSheet` — pharmacy picker (bottom sheet)

- 88 %-max-height bottom sheet (auto-grows with content), white surface, standard `rounded-t-3xl` + grabber.
- Header: «Адрес аптеки» 22/800 + helper 13/500.
- Search input (the shared `SearchInput`).
- **Geolocation banner**: 1-px `brand/green/200` border + `brand/green/50` surface, padding 16, contains:
  - 36 × 36 `brand/green/600` map-pin tile with `shadow/fab`
  - title «Рядом с вами» 14/800 `brand/green/700`
  - helper line with city + nearby pharmacy count, in `brand/green/700` at 70 % opacity
- List rows below: 40 × 40 chain-coloured tile with first letter of chain (white 14/800), name 15/800, sub-line `addr · city` 12/600, trailing distance pill (`brand/green/100` bg, `brand/green/700` text, 11/800, e.g. «340 м»).
- Selected row uses `brand/green/50` surface + `brand/green/200` border. Unselected uses `paper/input` at 70 %.

### 11.6 `CardSheet` — bonus-card capture (bottom sheet)

- Auto-height bottom sheet (no fixed `height` — overall flow grows from header through card-preview → masked field → keypad → CTA, with a 24-px bottom safe-area padding).
- **Card preview** (150 px tall, rounded 24 px) — the system's only place that uses the full **bonus-card gradient**:

  | Token | Value |
  |---|---|
  | `grad/bonusCard` | `linear-gradient(135deg, #16A65C 0%, #21D17A 60%, #3DCDA2 100%)` |

  Layered on top: a small 36 × 24 golden chip (gradient `#F4B73A → #B97F11`), eyebrow «PHARMAPAY» 10/700 uppercase, title «Бонусная карта» 18/800, live-formatted digit groups (4 × 4 chars, gap 10, tabular-nums, 15/800, dots used as masks for unfilled positions), and a tiny caption «Держатель · VISA / KASPIGOLD / HALYK». Outer glow `0 10px 28px rgba(15,143,85,0.35)`.
- **Number field** below the preview — 56-tall pill in `paper/input`, leading credit-card icon in `brand/green/700`, eyebrow «НОМЕР КАРТЫ» 11/700 uppercase, body 16/800 single-line nowrap with letter-spacing 0.08em, trailing `n/16` counter in `ink/400` JetBrains-Mono-style tabular.
- **Numeric keypad** — a 3 × 4 grid (`gap-2.5`), each cell 56-tall (`h-14`). 1-9 + 0 use `bg-paper-input` `text-ink-900` 24/800 with `active:scale-[0.97]`. The 12th slot is the **backspace key**: `bg-brand-green-600`, white icon, `shadow/fab` — same visual weight as a primary CTA so it stands out from the 10 grey digit keys.
- CTA «Привязать карту» appears below the keypad in the standard `ButtonPrimary variant="green"` pill (60-tall), disabled until 16 digits are entered.

### 11.7 `SuccessScreen` checkmark — gotcha

The 112 × 112 success-disc is filled with `brand/blue/600` (the only place the receipt-flow uses blue — it links back to the brand mark, where the stamp disc is blue). The disc has `ring-8 ring-white/20`. **The check-mark inside must explicitly be `text-white`**; the `<I.Check>` icon inherits `currentColor`, so without a colour set the tick rendered black and blended into the deep-blue disc. Add `text-white` on the disc container — it cascades down to the SVG stroke.

### 11.7a `SuccessScreen` actions wiring

The two CTAs underneath the disc are **functional**, not decorative — each is the start of a continuation path:

- **«История и статусы»** — `popUntil((r) => r.isFirst)` to pop the receipt-review/success stack back to Home, then `push(ReceiptsListScreen)`. Don't just pop to root — the user explicitly asked to see history; landing them back on Home wastes a tap.
- **«Отправить ещё раз»** — `popUntil` to Home, then immediately `showUploadPromptSheet(context)` (the same sheet as the BalanceCard «Загрузить чек» pill). The card binding persists across submissions (`ReceiptDraftNotifier.reset(keepCard: true)`), so the user only needs to re-tap promos + pharmacy + take a new photo for the next receipt.

### 11.7b `ReceiptsListScreen` — stat-pills + pull-to-refresh

Three counters on the green header (Подтверждено / На проверке / Ожидает) follow the **GlassPill recipe** (see § 6.1) — surface `brand/green/500 @ 55%` + 1 px white-40 border, radius 16, height 64. Count typography 22/800 white, label 12/800 white @ 92 %. This unifies the visual language with the Profile header pills and BalanceCard glass-pills — every green-header chip in the system uses the same recipe.

The list itself supports **pull-to-refresh**:
- `RefreshIndicator(color: brand/green/600, backgroundColor: white)` wraps the list
- On trigger: `HapticFeedback.mediumImpact()` for tactile confirmation, then `ref.invalidate(receiptListProvider)` + `await ref.read(receiptListProvider.future)` so the spinner stays visible long enough to register
- Empty / loading / error states are also wrapped in a `ListView` with `AlwaysScrollableScrollPhysics()` — otherwise pull-down doesn't fire when there's no content (no scrollable area = no overscroll = no refresh)

### 11.7c `ReceiptsListScreen` — profile-style structural alignment

The screen follows the same structural pattern as the Profile tab:
1. **Green header (compact)**: back-button + `PharmaWordmark` + 3 stat-pills. The «Мои чеки» title is no longer in the header — it moved to a canvas section title (see 2) to mirror Profile's «Помощь» / «О приложении» layout.
2. **Canvas section title** «Мои чеки» — H1 26/800 ink/900, sits on `paper/canvas` above the list, with 12 px gap before the first card.
3. **Receipt cards** in `ReceiptRow` widget — apply the same layering recipe as `ProfileRow` (§ 6.4):
   - Outer `Container(color: white, borderRadius: brLg, boxShadow: shadow/card)`
   - Inner `Material(color: transparent)` for ink ripple
   - `InkWell` + `Padding(16, 14, 16, 14)` for tap area
   - Leading 32 × 32 status-icon (no tile-background — keeps receipt cards lighter than profile rows): `check_circle_rounded` (confirmed, green-700), `hourglass_top_rounded` (inReview, amber-800), `receipt_long_outlined` (awaiting, slate-500), `error_outline_rounded` (rejected, red-700)
   - Centre column: date · time (12/800 ink/500) + title (17/800 ink/900) + amount (14/800 ink/700)
   - Trailing status-pill (unchanged colours)
4. **Sticky CTA «Загрузить чек»** floats above the safe-area at the bottom.

The layering fix is critical: previously `Material(white) > Container(shadow)` rendered the cards as semi-transparent grey because the shadow was darkening the white surface through the empty Container. Same bug we fixed in `ProfileRow` — see § 6.4c.

Card spacing: 12 px between rows (matches Profile menu), screen-edge 20 px.

### 11.8 Receipt thumbnail

A reusable visual: 88 × 88, `paper/input` outer frame with 6-px padding, white inner panel, inside it a stack of three lines (mock receipt rules) + a 36-px QR-style block (built from `repeating-conic-gradient`) + zigzag bottom edge cut via an SVG mask. Sits to the left of the green «Чек загружен» banner in the review header. Use it anywhere you need a small, recognisable «receipt» visual without shipping a raster asset.

### 11.9 Receipt-with-check composite glyph

Used inside the «Чек загружен» banner: a 48 × 48 receipt body (white fill, 2-px green stroke, zigzag bottom) with a 28-px `brand/green/600` disc overlapping the bottom-right corner. The disc carries a white check-mark and a 2-px `brand/green/100` ring so it reads cleanly against the banner's `brand/green/100` background. This is **not** the canonical brand mark — it's a flow-specific composite that signals «receipt accepted». Don't substitute one for the other.

### 11.10 Receipt-flow Don'ts

- Don't put the receipt entry-point back into the bottom nav. The third tab slot is reserved; if the entry-point ever needs more discoverability, add a second pill on the Home balance card or surface it from Notifications — not the nav.
- Don't show the `SuccessScreen` until the user has confirmed in `ReceiptReviewScreen`. The CTA there is the consent-to-submit moment.
- Don't recolour the bonus-card preview to blue. The card is the only large green surface in the flow and that's intentional — it ties payout to brand reward.
- Don't fix the height of `CardSheet` (or any sheet whose body can grow). Let bottom sheets size to content with a `maxHeight: 88%` cap — fixed heights clip CTAs on small devices (this is a real bug we hit when wiring the keypad).

