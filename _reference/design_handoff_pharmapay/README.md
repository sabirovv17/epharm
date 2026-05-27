# Handoff: PharmaPay — Pharmacist cashback app (iOS + Android)

A mobile app for pharmacists in Kazakhstan. Pharmacists scan receipts for promoted medicines (Лифта, Аквадетрим, Эссенциале, etc.) and receive tiered cashback bonuses on their PharmaPay balance. They can also complete short training courses to earn bonuses and learn about the products they sell.

There are **two prototypes in this bundle** — one for iOS, one for Android. They share screen logic, data, brand palette, copy and flows; only the system chrome (status bar, bottom-nav, device frame) differs.

---

## About these files

The files in `prototypes/` are **design references created in HTML + React + Tailwind**. They are not production code to copy directly — they are clickable hi-fi mockups built so reviewers can see exact look, behaviour, copy and interactions.

The task is to **recreate these designs in the target codebase** using its established patterns:
- **iOS** — SwiftUI (preferred) or UIKit. SF Pro for system chrome, Manrope (custom font) for body copy.
- **Android** — Jetpack Compose with Material 3. Roboto for system chrome, Manrope for body copy.
- **Cross-platform / hybrid** — React Native, Flutter, or similar. Map the tokens in `design-tokens.md` to the framework's theming primitives.

Use this folder's `design-tokens.md` as the source-of-truth for colour, type, spacing, radius and shadow values. The HTML prototypes implement the same tokens via Tailwind config — the values match exactly.

## Fidelity

**High-fidelity (hi-fi).** Final colours, typography, spacing, copy and interactions. Pixel-perfect recreation is expected.

## Open the prototypes

Open the HTML files in a browser:

| File | Frame | What it shows |
|---|---|---|
| `prototypes/PharmaPay.html` | iPhone 14 (390×844) | iOS chrome — Dynamic Island status bar, iOS bottom-nav |
| `prototypes/PharmaPay (Android).html` | Pixel-style (412×892) | Android chrome — Material 3 status bar with triangle/fan icons, M3 Navigation Bar with pill indicator behind active icon |

Both files load the **same** `src/screens/*.jsx` modules — only `window.PLATFORM` differs, which the platform-aware primitives (`StatusBar`, `BottomNav`, `DeviceFrame` in `src/ui.jsx` and `src/app.jsx`) switch on.

---

## App flow (state machine)

```
WELCOME (3 onboarding slides)
    │  «Начать» on the last slide
    ▼
HOME (unauthenticated)
    │  «Войти» on the welcome banner or any auth-gated action
    ▼
PHONE (+7 pinned, 10 national digits)
    ▼
SMS (4-digit OTP, auto-advances 1234 after 600 ms in the demo)
    ▼
ИИН + ФИО (one screen — full name + 12-digit IIN)
    ▼
HOME (authenticated — balance 0 ₸ replaces the welcome banner)
    ├── tap any catalog tile → ProductDetailSheet
    ├── tap «Чек» tab or any «Загрузить чек» → UploadPrompt sheet
    │       │
    │       ▼
    │   CAMERA (viewfinder for receipt)
    │       │  «Capture»
    │       ▼
    │   SUCCESS («Чек успешно отправлен»)
    ├── tap «Обучение» tab → TrainingScreen
    │       │  tap any course
    │       ▼
    │   CourseDetail
    └── tap «Профиль» → ProfileScreen (ФИО + телефон + Help + About + Logout)
            │  «Выйти»
            ▼  resets phone/iin/fio/authed; returns to unauthenticated HOME
```

Defined as a single `SCREENS` enum in `src/app.jsx`. `BottomNav` shows on `HOME / TRAINING / PROFILE` only (it's hidden during auth, camera, success and course-detail).

---

## Screens

### 1. Welcome — 3-slide onboarding (`src/screens/welcome.jsx`)

- Green gradient background (`grad-welcome`).
- White `PharmaPay` wordmark top-left.
- Centre stage: three peeking phone mockups (small previews of Home / Camera / Success screens) — the middle one is full size with a 4 px white-40 ring.
- Below: headline (26 / 800 white) + body copy (15 / 500 white-95).
- Page dots (3, active = white 6 px wide pill, inactive = 1.5 px circle 40% white).
- Bottom CTA — `ButtonPrimary variant="white"` on slides 1+2 («Далее»), `variant="green"` on slide 3 («Начать»).

Tapping «Начать» drops the user into **unauthenticated Home**. There is no «Welcome 2 / login gate» any more — registration is opt-in from inside the app.

### 2. Home (Главная) — `src/screens/home.jsx`

Two states.

**Unauthenticated:**
- Green header (`grad-blueHead`) with iOS status bar + `PharmaPay` wordmark.
- Inside the header: **welcome banner** — `bg-brand-green-700/40 backdrop-blur` card (radius 28, padding 20), title «Добро пожаловать!» (26 / 800 white), white pill CTA «Войти» (h-60, radius 16, green text, shadow-fab).
- Below the header (overlapping by -mt-6): promo carousel, search, filter chips, product catalog (all still browsable).

**Authenticated:**
- Same header, but the welcome banner is replaced by the **balance card**:
  - Coin glyph (gold radial gradient) + «PharmaPay / Баланс» stacked label.
  - Balance amount right-aligned: 24 / 800 white tabular-nums, e.g. `0 ₸` (or `16 000 ₸` once cashback comes in).
  - Two glass-pill buttons below: «История» and «Загрузить чек».

**Always present (both states):**
- **Promo carousel** — horizontal scroll, 200×260 cards with 2 px blue inset outline. Three example promos in the prototype:
  1. «Важная информация» (yellow info)
  2. Huggies × Kotex contest (50 000 ₸ prize)
  3. Kotex monthly bonus (10 000 ₸)
- **Search input** — 56-tall pill, `paper/input` surface, search glyph leading.
- **Filter row** — sort button (44 × 44 white circle), brand button (44 white pill with count badge), then chips: «Все», «Новинки», «Конкурсные» (last one has trophy emoji leading). Single-select.
- **Product catalog** — first item is a *featured big card* (only if it has `featured: true` and passes the filter), the rest are a 2-column grid of small cards.

#### BigProductCard

Per row:
- Hero strip 200 tall with the brand's package mockup centred (see `PackageMock`).
- Floating period badge top-left (`с 1–31 мая`, white pill).
- Title (16 / 800 ink-900) + «Куплено: N уп.» (13 / 600 ink-500) right-aligned.
- **Tier ladder** — pill prices (green, white text, 15 / 800, `shadow-fab`) connected by a single horizontal divider line. Between each pair sits a small gift-emoji glyph (28 px) marking the bonus threshold. Labels under each pill: «от 1 шт.», «от 10 шт.», «от 20 шт.».
- Bonus rows — gift glyph + text, 13 / 500 ink-700.

#### SmallProductCard

- 140-tall image area with the brand's colour wash + accent-coloured label (e.g. «АкваДетрим», purple).
- Bottom-left: green period pill.
- Top corners: blue `NEW` badge or trophy emoji for contest items.
- Title (14 / 700 ink-900, line-clamp-2), restriction caption underneath in blue.

#### Filtering

- **«Все»** — show every product (default).
- **«Новинки»** — `p.new === true`. Aquadetrim D3, Aquadetrim Forte, Vitrum C, Essentiale.
- **«Конкурсные»** — `p.contest === true`. Both Лифты, Huggies.
- Tapping a product → opens `ProductDetailSheet` (bottom-sheet with full package render, tier ladder, bonuses, «How to get a bonus» 3-step explanation, and a primary CTA «Загрузить чек»).

#### Sort sheet & Brand sheet

- **Sort** — radio list, 4 options: «Сначала новые акции», «По названию (А–Я)», «По типу акции (по возрастанию)», «По типу акции (по убыванию)».
- **Brand** — full-height sheet with a header («Бренды»), inline «Сбросить» link, search input, scrollable list with checkboxes, footer «Применить» CTA.

### 3. Phone — `src/screens/auth.jsx` → `PhoneScreen`

- Same green gradient as welcome.
- Back arrow top-left (pops to unauthenticated Home).
- Headline «Введите номер телефона 📱» (26 / 800 white).
- Body «На него мы отправим код подтверждения» (14 / 500 white-85).
- **Phone input** — white pill, 64 tall, two parts:
  1. **Pinned `+7`** on the left (non-editable, `select-none`, 22 / 700 ink-900).
  2. Editable national input — placeholder `(___) ___-__-__`, formats live to `(XXX) XXX-XX-XX` as the user types. `type=tel`, `inputMode=tel`, `autoComplete=tel-national`. Auto-focuses on mount.
- Mint CTA «Войти» (h-60 pill, `brand/green/400`) — disabled until 10 national digits entered.
- Decorative Pharm glyphs in the lower half.

### 4. SMS — `src/screens/auth.jsx` → `SmsScreen`

- Same gradient + Logo.
- Headline «Введите код из смс 💬» (26 / 800 white).
- Body «Код отправлен на номер {phone}» (14 / 500 white).
- **4 OTP boxes** — flex row, gap 12. Each: 72 tall, white surface, radius 16, 28 / 800 centred digit, focus ring 2 px `brand/green/400`. Numeric input, single character, auto-advance to next box on input.
- Demo behaviour: 600 ms after mount, the 4 digits auto-fill `1234`.
- Mint CTA «Войти» disabled until 4 digits present.
- Countdown caption «Повторная отправка через: MM:SS» (starts at 1:59, ticks down per second).

### 5. ФИО + ИИН — `src/screens/auth.jsx` → `IinScreen`

- Same gradient + Logo, no decorative subtitle.
- Headline «Завершите регистрацию» (26 / 800 white).
- Two stacked white inputs (radius 16, h-60):
  - **ФИО** — label «Введите ваше ФИО» (14 / 600 white, `mb-1.5`); 18 / 700 ink-900 body; placeholder `Иванов Иван Иванович`; `autoComplete=name`. Considered valid when ≥ 2 whitespace-separated tokens.
  - **ИИН** — label «Введите ИИН» (same style); 20 / 700 ink-900 with `tracking-[0.18em]`; trailing counter `n/12` (12 / 700 ink-400). Numeric-only, max 12 digits.
- Privacy line below the inputs: lock glyph + «Данные защищены и используются только для зачисления выплат» (12 / 500 white-85).
- Mint CTA «Продолжить» — disabled until both ФИО has ≥ 2 words *and* IIN is exactly 12 digits.

On submit: `setAuthed(true)` and route back to Home (now authenticated).

### 6. Обучение (Training) — `src/screens/training.jsx`

- Green header (`grad-blueHead`) with status bar, logo, and a small star-pill in the top-right showing points balance (`{points} б.`).
- **Progress card** — `bg-brand-green-700/50 backdrop-blur` (radius 28, padding 16). Inside:
  - Square gradient tile with a graduation-cap glyph (56 × 56).
  - «Ваш прогресс» (14 / 500 white-85) above «N из M курсов» (22 / 800 white).
  - 2-px progress bar (white-15 track, `brand/green/500` fill).
  - Footer row: «Заработано: 850 ₸ бонусов» on the left, «+200 ₸ за курс» on the right.
- **Resume card** — full-width white card with a 140-tall cover (gradient cover per course), centred play button. Brand pill top-left, reward badge top-right («+300 ₸», green). Title + lesson progress + percent bar.
- **Category chips** — «Все курсы», «Новые (3)», «Рецептурные (5)», «Безрецептурные (7)», «Витамины (4)». Single-select.
- **Course list** — vertical, each is a small horizontal card: 88-square cover tile (play / check / lock icon), brand pill + NEW/Done badges, title (15 / 800 ink-900, line-clamp-2), duration + difficulty caption, optional progress bar, right side: green reward («+200 ₸»), chevron.

Tap → `CourseDetail` screen:
- 280-tall cover with back button + centred big play button.
- Bottom-overlay: brand caption + course title (22 / 800 white).
- White content area (rounded-top-3xl, -mt-4 overlap):
  - Duration · difficulty · lesson count row.
  - Reward callout — blue-tinted card with gift glyph.
  - «Чему вы научитесь» — 4 bullet rows with green-tinted check icons.
  - «Уроки» — numbered list of 4 lessons, each in a paper-input pill: round number badge, lesson title, duration + type caption, status icon (check or play).
  - Bottom CTA — primary blue «Начать курс» / «Продолжить» / «Пройти ещё раз» based on `progress`.

### 7. Профиль — `src/screens/profile.jsx`

Two states.

**Unauthenticated:** green header with the same «Войдите в аккаунт» card pattern, but with an extra subtitle line («Чтобы видеть баланс, историю чеков и участвовать в конкурсах»).

**Authenticated:**
- Avatar circle (56 × 56) with a 2 px white-60 border and user glyph.
- **ФИО** displayed huge — 22 / 800 white, `text-wrap: balance`.
- Phone underneath — 14 / 600 white-85, tabular-nums.
- (ИИН is **never** shown in the profile — it's part of the registration data only.)
- Two glass pills: «История», «Конкурсы».

**Always:**
- White paper background, sections:
  - **Помощь** (24 / 800 ink-900 header) — 4 list rows.
  - **О приложении** — 2 list rows.
- **Each row** — white card (radius 16, padding 16, `shadow-card`), 40-square icon tile in `brand-blue-100` tint with `brand-blue-600` glyph, label 17 / 700 ink-900, trailing chevron.
- **Logout button** (visible only when authenticated) — full-width white card, 60 tall, centred row of logout glyph + «Выйти» in **red** (`text-red-500`, 17 / 700). Tapping it resets `phone`, `iin`, `fio`, and `authed` to defaults and returns to Home.
- Footer: «PharmaPay · v 1.0.4 (2026)» (12 / 500 ink-400, centred).

### 8. Чек (Camera + Upload) — `src/screens/upload.jsx`

**UploadPrompt** (bottom sheet, opens from any «Загрузить чек» tap):
- Green gradient surface (`grad-receipt`).
- Title «Загрузите фото чека» + subtitle in white.
- Three illustrative receipt-icon tiles.
- White CTA «Сделать фото» → goes to **Camera**.
- Plain text link «Отмена» dismisses.

**Camera** (full screen):
- Black background, status bar in light mode.
- Top bar: back arrow + screen title «Сделать фото чека» + ⋯ menu.
- Centre viewfinder — dashed rectangle outline showing the expected receipt area, faint hint text inside.
- Bottom controls: gallery thumbnail, big white shutter (radius full, 72 px), flip-camera glyph.
- Capture → **Success** screen.

**Success**:
- Green header at top with a giant white check on a `brand-green-600` disc.
- Below: white card titled «Чек успешно отправлен» with a body line explaining bonus processing.
- Two CTAs: blue «История и статусы» (primary), `paper-input` «Отправить ещё раз» (secondary).

---

## Shared UI primitives (`src/ui.jsx`)

| Component | Purpose | Key props |
|---|---|---|
| `Logo` | Wordmark "Pharma**Pay**" — Pharma in white, Pay in `brand-blue-600`. | `size: 'sm' | 'md' | 'lg'` |
| `StatusBar` | **Platform-aware**. iOS = SF Pro time + arc wifi + 4 bars + pill battery. Android = Roboto time + signal triangle + wifi fan + vertical capsule battery. | `time, dark, battery` |
| `ButtonPrimary` | Full-width 60-tall pill CTA. | `variant: 'blue' | 'green' | 'mint' | 'white'`, `disabled` |
| `GlassPill` | 52-tall translucent pill on green-header surfaces. | `onClick` |
| `FilterChip` | Filter pill — active (`brand-green-600` fill + `shadow-fab`) vs inactive (white + `shadow-card`). | `active, leading, onClick` |
| `SearchInput` | 56-tall `paper/input` pill, search glyph leading. | `value, onChange, placeholder` |
| `BottomSheet` | Generic bottom-sheet shell with scrim, drag handle, optional `dark` (green gradient) variant. | `open, onClose, height, dark` |
| `Row` | Profile list-row pattern (icon tile + label + chevron). | `icon, label, onClick` |
| `PromoCard` | One of the carousel cards. Dispatches on `promo.kind` to render the info / huggies / kotex variant. | `promo` |
| `BottomNav` | **Platform-aware**. iOS = flat 4-tab grid. Android = M3 Navigation Bar with pill indicator + always-visible labels + gesture handle. | `tab, onTab, onReceipt` |
| `BatteryGlyph` | iOS-style battery pill (`StatusBar` internal). | |
| `AndroidBatteryGlyph` | Vertical capsule (Android `StatusBar` internal). | |

Everything is exported via `Object.assign(window, { … })` because the prototype uses sibling `<script type="text/babel">` files, which don't share scope. In a real React/SwiftUI/Compose project, use normal imports.

---

## Icon set (`src/icons.jsx`)

All glyphs are inline SVG with `currentColor` and a `size` prop. The set covers everything used in the prototype:

```
Back, Search, Clock, Upload, Trophy, Camera, CameraOutline, CameraFill,
Home, HomeFill, User, UserFill, Grad, GradFill,
Sort, Chevron, ChevronDown, Check, Bolt, Gift, Help, Heart,
Doc, DocCheck, Copy, IdCard, Play, Lock, Star, Phone, Logout,
Pharm (multi-colour pharm cross token),
Coin (gold radial token used on the balance card),
GiftEmoji (gift box token used on tier ladders),
TrophyEmoji (trophy token used on contest chips),
```

When rebuilding, substitute the matching icons from your platform's icon library — SF Symbols on iOS, Material Symbols on Android. The decorative tokens (`Pharm`, `Coin`, `GiftEmoji`, `TrophyEmoji`) are custom illustrations; ship them as SVG assets or rebuild as composable shapes.

---

## Data model (`src/data.jsx`)

Static fixtures. The shape is what the production API should return.

```ts
type Promo = {
  id: string;
  title: string;
  subtitle: string;
  period?: string;          // 'с 1–31 мая'
  amount?: string;
  footer?: string;
  note?: string;
  kind: 'info' | 'huggies' | 'kotex';
  bg: string;               // CSS background value (gradient)
};

type Tier = { qty: number; label: string; price: string };

type Product = {
  id: number;
  brand: string;            // 'AIGP' | 'Natrol' | 'Polpharma Santo' | ...
  name: string;
  period: string;
  bought: number | null;
  tiers: Tier[];            // [] for small cards
  bonuses: string[];        // gift-row text for big cards
  restrictions?: string;    // green caption on small card
  pkg: { bg: string; label: string; sub?: string; maker?: string; accent?: string };
  featured?: boolean;       // → render as BigProductCard
  new?: boolean;            // appears in 'Новинки' filter
  contest?: boolean;        // appears in 'Конкурсные' filter
};

type Course = {
  id: string;
  title: string;
  brand: string;
  duration: string;
  reward: number;           // in ₸
  progress: number;         // 0..100
  lessons: number;
  difficulty: string;       // 'Базовый' | 'Средний' | 'Продвинутый'
  cover: string;            // CSS gradient for the cover tile
  new?: boolean;
  completed?: boolean;
  locked?: boolean;
};
```

`PROMOS`, `PRODUCTS`, `BRANDS`, `SORT_OPTIONS`, `PROFILE_HELP`, `PROFILE_ABOUT`, `TRAINING_PROGRESS`, `TRAINING_CATEGORIES`, `TRAINING_COURSES` are all on `window.PP_DATA` in the prototype.

---

## Brand & design tokens

All raw values are in **`design-tokens.md`** (open it). Highlights:

- **Primary green**: `#16C97A` (CTA, balance card, active tabs, header gradients).
- **Secondary blue**: `#2A2BE2` (NEW badge, `.pay` wordmark accent, profile-row icon tile, FAB shadow).
- **Headline font**: Manrope (cyrillic-friendly geometric sans). Weights 500 / 600 / 700 / 800.
- **System chrome font**: SF Pro Display / Roboto (platform-native).
- **4-pt spacing scale**: `4 · 8 · 12 · 16 · 20 · 24 · 28 · 32 · 40 · 48 · 64`.
- **Radii**: 8 / 12 / 16 / 20 / 24 / 28 / pill (most common: 16 for cards, 28 for sheet tops, pill for buttons).
- **Shadows**: `card` (subtle), `elevated` (sheets), `fab` (green glow on green CTAs / chips).
- **Logo**: Receipt Stamp mark — `logo/marks.jsx` → `MarkReceiptCross` (white receipt + green stroke + blue stamp disc with white cross). Spec in `design-tokens.md` § 7.

---

## Platform-specific notes

### iOS

- **Status bar** — height 54, time **9:41** left, icons right (cellular bars, wifi arcs, battery pill with fill bar — no % number).
- **Device frame** — radius 56 outer / 48 inner, Dynamic Island 110 × 34 centred at top:8.
- **Bottom nav** — flat 4-tab grid, white surface, `pt-2 pb-7` (home-indicator safe area), active state is just colour change (no background pill).
- **Body font** — Manrope. System chrome font — SF Pro Display.

### Android (Material 3)

- **Status bar** — height 38, time **9:41** left in Roboto 14 / 500, icons right: signal triangle, wifi fan, vertical battery capsule.
- **Device frame** — radius 48 outer / 42 inner, hole-punch camera (14 × 14 black disc, centred at top:8).
- **Bottom nav** — Material 3 Navigation Bar, 80 dp tall. Active item icon sits inside a 64 × 32 pill (`#C9F2DE`, secondary-container). Labels always visible — Roboto 12, weight 700 active / 500 inactive. Gesture handle (108 × 4 px pill, ink-900 / 45%) below the nav.
- **Body font** — Manrope. System chrome font — Roboto.

The screens themselves are **identical** between platforms — only the chrome differs. When implementing natively, keep this discipline: one set of screens, two thin sets of system-chrome primitives.

---

## Interactions & state management

### State that lives at the root (`src/app.jsx` → `App`)

```js
screen          // SCREENS enum — 'welcome' | 'phone' | 'sms' | 'iin' | 'home' | 'training' | 'course' | 'profile' | 'camera' | 'success'
activeTab       // 'home' | 'training' | 'profile' — which bottom-nav tab is highlighted
authed          // boolean — false until ИИН/ФИО submitted
phone           // string — '+7 (XXX) XXX-XX-XX'
iin             // string — 12 digits
fio             // string — full name
chip            // 'all' | 'new' | 'contest' — active filter chip on Home
sortIdx         // 0..3 — selected sort option
brandSel        // string[] — checked brands in the Brand filter sheet
sortOpen        // bool — Sort sheet visibility
brandsOpen      // bool — Brand sheet visibility
uploadOpen      // bool — UploadPrompt sheet visibility
activeCourse    // Course | null — for CourseDetail
activeProduct   // Product | null — for ProductDetailSheet
```

### Transitions

- Welcome `Начать` → `setScreen(HOME); setAuthed(false)`
- Any `Войти` CTA → `setScreen(PHONE)`
- Phone submit → `setScreen(SMS)`
- SMS submit → `setScreen(IIN)`
- IIN/ФИО submit → `setAuthed(true); setScreen(HOME)`
- Bottom-nav tap → updates `activeTab` and routes to the right screen
- `Чек` tab tap: if authed → `setUploadOpen(true)`; otherwise → `setScreen(PHONE)`
- Camera capture → `setScreen(SUCCESS)`
- Success «Отправить ещё раз» → `setScreen(CAMERA)`
- Success «История и статусы» → `setActiveTab(PROFILE); setScreen(PROFILE)`
- Profile logout → clear `phone, iin, fio`; `setAuthed(false); setActiveTab(HOME); setScreen(HOME)`

### Animation

- **Screen transitions** — light `.screen` fade with a 6 px vertical translate over 250 ms.
- **Filter chips** — colour change is instant (no transition); pressed state uses `active:scale-95`.
- **Buttons** — `active:scale-[0.99]` (subtle press).
- **Android nav-bar pill** — `background 160ms ease` so the pill fades in/out under the active icon.

### Form validation

- **Phone** — submit enabled only when 10 national digits entered.
- **OTP** — submit enabled only when all 4 boxes filled.
- **ФИО** — valid when ≥ 2 whitespace-separated tokens.
- **ИИН** — must be exactly 12 numeric digits.
- IIN+ФИО submit requires BOTH valid simultaneously.

---

## Recreating in your codebase

1. **Add the design tokens** — read `design-tokens.md` and map every value into your theme system (Tailwind config / Material `MaterialTheme.colorScheme` / SwiftUI Color + Font extensions).
2. **Build the platform primitives first** — StatusBar, BottomNav, DeviceFrame are the platform-specific parts. Wire them to your nav stack.
3. **Build the shared UI primitives** — ButtonPrimary, GlassPill, FilterChip, SearchInput, BottomSheet, Row, PromoCard. They are all in `src/ui.jsx` for reference.
4. **Build screens top-down** — Welcome → Home (unauthed) → Auth flow → Home (authed) → Training → Profile → Camera/Success.
5. **Wire state** — use the table above. In SwiftUI use `@State`/`@Observable`; in Compose use `mutableStateOf` + remembered state holders; in React Native use whatever store you've adopted.
6. **Use the prototype interactively** — open the HTML files side-by-side with your implementation. Every spacing, colour and copy decision is exact.

---

## Assets

The prototype ships **zero raw images**. Everything is rendered via:

- Inline SVG icons (`src/icons.jsx`).
- CSS gradients for product packages (`PRODUCTS[i].pkg.bg`) and promo backgrounds.
- A stylised `<PackageMock>` component (in `src/screens/home.jsx`) that draws a fake medicine box with brand colours, MNN, manufacturer, side ribbon.

When you implement, replace the package mocks with **real product photos** from the brands. The promo carousel will also need real campaign artwork from each brand's marketing team.

---

## Files in this bundle

```
design_handoff_pharmapay/
├── README.md                       — this document
├── design-tokens.md                — all colours, type, spacing, radius, shadow values + logo spec
├── prototypes/
│   ├── PharmaPay.html              — iOS entry (window.PLATFORM = 'ios')
│   ├── PharmaPay (Android).html    — Android entry (window.PLATFORM = 'android')
│   └── src/
│       ├── app.jsx                 — root state machine, device frame switch
│       ├── ui.jsx                  — shared UI primitives + platform-aware chrome
│       ├── icons.jsx               — every SVG glyph used
│       ├── data.jsx                — static fixtures (products, promos, brands, courses)
│       └── screens/
│           ├── welcome.jsx         — 3-slide onboarding
│           ├── auth.jsx            — Phone → SMS → ФИО+ИИН
│           ├── home.jsx            — Home + ProductDetailSheet + Sort/Brand sheets
│           ├── training.jsx        — Training tab + CourseDetail
│           ├── profile.jsx         — Profile tab (authed/unauthed) + Logout
│           └── upload.jsx          — UploadPrompt sheet + Camera + Success
└── logo/
    └── marks.jsx                   — 6 logo concept variants, with MarkReceiptCross as the canonical mark
```

---

## Questions for the team before implementation

1. **Auth backend** — what's the OTP provider (KazInfoTeh / TeleAcc / custom SMS gateway)?
2. **Receipt OCR** — server-side or on-device? Which provider (Google Vision, AWS Textract, custom)?
3. **Cashback rules engine** — where do the tier ladders + bonus thresholds live? Does each brand define their own?
4. **Training content** — CMS-driven or hard-coded in the app bundle?
5. **Localisation** — Russian is the prototype language. Kazakh-language strings exist on two of the promo cards. Plan for a translation pass.
6. **iOS minimum target** — iOS 16 (for Dynamic Island demo accuracy)?
7. **Android minimum target** — Android 12 / API 31 (for proper Material 3 theming)?

---

End of handoff. Any questions, ping the design team and reference this file.
