# PharmaPay — Design Tokens

Tokens extracted from the live prototype (`PharmaPay.html` + screens in `src/screens/*`). The system after the **green-primary / blue-secondary swap** — green is the brand colour for headers, CTAs, balance, active tabs; blue is reserved for accents, NEW badges, FAB-style shadows, links and the «Pay» wordmark.

App flow: **Welcome onboarding (3 slides) → browseable Home (unauthed)**. Users can explore the catalog, promos and filters without registering. Tapping any «Войти» CTA — on the Home welcome banner или в Профиль — starts the auth flow: **Phone (pinned +7) → SMS (4-digit OTP) → ФИО + ИИН on one screen → Home (authed, balance 0 ₸)**. Загрузка чека после авторизации идёт через pill «Загрузить чек» на BalanceCard → 4-step receipt-flow (Camera → Review → Promos/Address/Card sub-screens → Success). См. §6.6 + §11 в каноничном `_reference/design-tokens.md`.

> All raw token values live in the Tailwind config inside `PharmaPay.html` (`tailwind.config.theme.extend`).

---

## 1. Colors

### Brand — Green (primary)

| Token | Hex | Use |
|---|---|---|
| `brand/green/700` | `#0F8F55` | Deepest green — balance card surface tint, active states on dark gradients |
| `brand/green/600` | `#16C97A` | Primary CTA "Начать" / "Загрузить чек", price pills in tier ladder, active filter chip, active tab icon + label, ring on profile success |
| `brand/green/500` | `#21D17A` | Header gradient mid-stop, period badges on small cards, success ticks |
| `brand/green/400` | `#3DCDA2` | Header gradient top-stop, mint "Войти" OTP submit |
| `brand/green/100` | `#E5F8EE` | Light tint for outline/tag backgrounds |

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
| `accent/danger` | `#EF4444` | Кнопка «Выйти» (logout) — icon + label, единственное место destructive-action в приложении |

---

## 2. Typography

Family: **Manrope** (well-supported Cyrillic, geometric).
Status-bar number specifically uses **SF Pro Display / -apple-system** to look like real iOS chrome.
Fallback stack: `-apple-system, "SF Pro Display", system-ui, sans-serif`.

| Role | Size / Line | Weight | Notes |
|---|---|---|---|
| Display | 32–34 / 38 | 800 | Logo wordmark "PharmaPay", «Добро пожаловать!» |
| H1 | 26 / 32 | 800 | Auth screen headlines ("Введите номер телефона", "Завершите регистрацию"), **section titles in Profile** («Помощь», «О приложении») |
| H2 | 22–24 / 28–30 | 800 | Sheet titles ("Сортировка", "Бренды"), profile authed name (24/800) |
| H3 / Title | 17–20 / 24 | **800** | Balance card amount, big-card product name |
| List-row title | 18 / 22 | **800** | Заголовок строки в Profile-списке (Помощь / О приложении). Особый case — плотнее обычного body-strong, потому что строка по факту работает как мини-заголовок секции. Раньше было 17/800, увеличили до 18/800. |
| Body-strong | 16 / 22 | **800** | Button labels, filter chip label, glass-pill label, phone under name (15/800) |
| Body | 14–16 / 20–22 | **700** | Card body, help-text, field labels in auth, FAQ answer text |
| Caption | 12–13 / 18 | **700** | Helper text, dates, restrictions, footer микротекст |
| Status-bar | 17 / 22 | 600 | iOS-style time (SF Pro Display, `-0.32px` tracking, tabular-nums) |
| Micro | 11–12 / 16 | **800** | Tab labels, NEW pill, period badges, length counter `n/12` |

Weights used: **500 / 600 / 700 / 800**. Самый плотный — 800. 

**Important — рефакторинг 2026-05 «+10% жирнее»**: все роли подняты на одну ступень — body 600→700, caption 600→700, body-strong 700→800, micro 700→800, button 700→800. Это даёт «плотный/контрастный» вид по всему приложению (см. claude-notes.md). H1-H3 и Display уже были 800 (max), не меняются. Manrope-variable бандл поддерживает 500/600/700/800; выше 800 не идём — упёрлись в верхнюю границу шрифта.

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
| `shadow/card` | `0 1px 3px rgba(15,20,36,0.08), 0 6px 18px rgba(15,20,36,0.12)` | List rows, white cards. Достаточно плотная, чтобы карточки чётко отделялись от paperCanvas (`#F4F6FA`). |
| `shadow/elevated` | `0 4px 8px rgba(15,20,36,0.06), 0 12px 32px rgba(15,20,36,0.10)` | Bottom sheets, modals |
| `shadow/fab` | `0 10px 24px rgba(22,201,122,0.55)` | Primary CTA, active filter chip, tier pills (зелёное свечение). Усилено с 0.35 → 0.55 для большего «pop» — теперь зелёные CTA и pills чётко видно на любом фоне. |
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
| Glass pill (on header) | `brand-green-500 @ 55%` (#21D17A) + 1 px white-40 border + soft white halo | white, 15/800 | pill | 52 | "История", "Конкурсы", "Загрузить чек". Эволюция: 16% white (сливалось) → 40% white (выглядело беловато) → теперь explicit **brand-green-500 @ 55%** — saturated green pill поверх зелёного gradient'а header'а (#21D17A → #16A65C), pills заметно зелёнее и ярче. Padding-x = 10 (раньше 14), gap icon→label = 4 (раньше 6), fontSize 15 (раньше 16) — чтобы длинный label «Загрузить чек» помещался без ellipsis в pill ~170 px ширины (половина экрана при 2-pill row). |
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
- **Search input** — **белая** поверхность, height 56, radius 16, 1 px бордюр `ink/300 @ 50%`, `shadow/card`. Leading glyph 22 px `ink/500`, placeholder `ink/400` 16/700. Раньше surface был `paper/input` (#F2F4F8) — практически сливался с канвасом `paper/DEFAULT` (#F4F6FA); теперь явная белая карточка с бордюром + тенью, чтобы поле читалось как полноценный input.

### 6.3 Cards

| Card | Surface | Padding | Radius | Shadow |
|---|---|---|---|---|
| List row (profile) | white | 16 | **20** (`lg`) | `shadow/card` — раньше было 16 (`md`); ушли в lg для более «iOS-friendly» rounding |
| Small product | white | 12 | 20 (`lg`) | `shadow/card` |
| Big product | white | 16 | 28 (`xxl`) | `shadow/card` |
| Promo (carousel) | per-promo bg | varies | 24 (`xl`) | `shadow/card` + 2 px inset outline `brand/blue/500` |
| Balance (on green header) | `bg-brand-green-700/40` | 16 | **28** (`xxl`) | inner 1 px white-10 border. Раньше 24 — увеличили до 28, чтобы совпадало с big-card и нижней curve хедера. |
| Progress (training) | `bg-brand-green-700/40` | 16 | 24 (`xl`) | as above |
| Welcome-gate (Login invite) | `bg-brand-green-700/40` | 20 | 24 (`xl`) | 1 px white-15 border, без shadow |

### 6.4 List rows (profile)

- Surface **чисто белая** (`#FFFFFF`), radius `lg` (20), padding 16/16, `shadow/card`. Важно: white-fill задаётся в Container (а не в Material под ним), чтобы тень не «затемняла» поверхность через прозрачный Container — раньше карточки выглядели сероватыми именно из-за этой ошибки в layering.
- Leading icon — **flat glyph 28 px в `brand/green/600`** (primary-brand-accent) в SizedBox 32×32. Tile нет — иконка плоская, как на дизайн-эталоне (сердце/?/копия/clipboard).
- Label — 18/800 `ink/900`, `line-height: 1.25` (List-row title role). Раньше 17/800 — подняли на 1 px после ревью с дизайном.
- Trailing chevron — `Icons.chevron_right_rounded`, 22 px `ink/400`
- Gap icon → label = 14 px, gap label → chevron = 8 px

### 6.4a Profile header (authed)

- Avatar circle 56×56, 2 px white-60 border, user glyph `Icons.person_rounded` 32 px white
- Right of avatar: **ФИО** as title — **24/800 white** (H2), line-height 1.15, max 2 lines, ellipsis. Раньше 22/800 — увеличили до 24/800, чтобы имя было самым ярким элементом шапки и соответствовало размеру section headers в теле.
- Phone below name — **15/700 white-90**, `tabular-nums`, line-height 1.15. Раньше 14/600 — подняли до Body-strong, чтобы не терялось на gradient header'е.
- Gap avatar → текст-блок = 12 px, gap имя → телефон = 4 px
- ИИН is **not** rendered in the profile (kept only inside the registration flow)
- Two glass pills below the header row (gap 12 px to header, gap 12 px between pills): «История» (`Icons.access_time_rounded`) and «Конкурсы» (`Icons.emoji_events_outlined`). См. §6.1 Glass pill.

### 6.4b Profile header (unauthed)

- «Войдите в аккаунт» welcome card on the green header:
  - Surface `bg-brand-green-700/40`, radius `xl` (24), padding 20, border 1 px white-15
  - Title 22/800 white (H2), subtitle 13/500 white-85 («Чтобы видеть баланс, историю чеков и участвовать в конкурсах»), gap 6 px
  - CTA: 56-pill, surface **white**, label «Войти» 18/800 `brand-green-600`, `shadow/fab` зелёное свечение. Тап → запускает auth flow с `/auth/phone`.
  - Карточка занимает всю ширину под `PharmaWordmark`, gap 20 px от логотипа

### 6.4c Logout button (Profile, authed only)

- **Чисто белая** surface (тот же layering-приём что и в §6.4: white-fill в Container, Material поверх transparent — иначе тень визуально затемняет surface), radius `lg` (20), height 60, `shadow/card`
- Row centred: `Icons.logout_rounded` 22 px + 8 px gap + label «Выйти» 18/800
- Both icon и label — `accent/danger` (`#EF4444`)
- Размещается под секцией «О приложении», отступ сверху 16 px, на всю ширину
- Тап → выйти из учётки (auth-state reset); видим только когда user != null

### 6.5 Filter chips — see Buttons table above

### 6.6 Bottom navigation — **3 equal tabs**

- 3-column grid, white background, top edge `shadow/navTop`, padding `pt-10 pb-7` (home-indicator safe area)
- Destinations: **Главная / Обучение / Профиль**. Раньше был 4-й tab «Чек» (камера) — удалён, потому что:
  - Загрузка чека уже доступна через explicit pill «Загрузить чек» в BalanceCard на главной (authed)
  - История чеков → push на full-screen route `ReceiptsListScreen` через pill «История» в BalanceCard или в `_AuthedHeader` профиля
  - 3 крупных таба читаются и нажимаются лучше, чем 4 мелких — особенно на iPhone SE
- Icon size **28 px** (outlined inactive / rounded active) — поднят с 24 (больше места после удаления Чек-таба)
- Label **12/800** (раньше 11/800)
- Gap glyph → label = **4 px**
- **Active: icon + label в `brand/blue/600`** (`#2A2BE2`). Inactive: `ink/400`. Weight 800 в обоих состояниях — выделение только цветом.

#### Receipts navigation (после удаления Чек-таба)

| Trigger | Action |
|---|---|
| Pill «Загрузить чек» (BalanceCard) | `showUploadPromptSheet(context)` — bottom-sheet с выбором камеры/галереи/QR |
| Pill «История» (BalanceCard, Profile) | `Navigator.push(ReceiptsListScreen)` — full-screen route с back-кнопкой в header |

`ReceiptsListScreen` имеет встроенную круглую back-кнопку (36×36, white-18 fill, white-40 border) в верхнем левом углу зелёного header'а — обязательная после превращения экрана из таба в push-route.

### 6.7 Bottom sheets

- Surface white (or `grad-receipt` green for upload prompt)
- Top corners radius 28 (`rounded-t-3xl`)
- Grabber 36×4 px `ink/300`
- Padding: 24 px top, 20 px sides, 32 px bottom (safe area)
- Backdrop `overlay/scrim` (`rgba(15,20,36,0.45)`)
- Used for: Sort, Brands, Upload-prompt, Product-detail

### 6.8 Tier ladder (product cards)

**Современный обновлённый стиль** (см. скриншот «AIGP Лифта»):
- **Pills** — `72×40` rounded-square (radius 16), **pastel fill** `brand/green/100` (#E5F8EE) с бордюром 1 px `brand/green/500 @ 35%` и тенью `0 4px 10px rgba(brand-green-600, 0.08)`. Текст `brand/green/700` 15/800. Стиль «светлый/полупрозрачно-белый» — раньше был тёмный gradient `green-500→600` с белым текстом, который выглядел слишком «давящим» на белой карточке. Цвет акцента сохранён через текст и тонкий бордюр.
- **Pills выравниваются по ЛЕВОМУ краю своей колонки**, не центром.
- **Track** — 8px высоты, `paper/input` (`#F2F4F8`), радиус 4.
- **Gift-маркеры** — `36×36` белый кружок с border `brand/green/500 #21D17A` 2px + shadow `0 2px 6px rgba(0,0,0,0.10)`, внутри `GiftEmojiGlyph size=22`. Кружки лежат на track в начале колонок 2 и 3 (под пилюлями этих колонок), а **НЕ** в gap-центрах между колонками. Раньше border был `brand/blue/300` (из первичного синего эталона) — сменили на зелёный, чтобы обводка была в цвет основного «зелёного интерфейса»; сам gift-glyph внутри остаётся синим (он маркирует «бонус» и должен выделяться как secondary accent).
- **Labels** «от N шт.» — 13/700 `ink/700`, выровнены **по левому краю** колонки (под левым краем пилюли).
- **Vertical rhythm внутри ladder**: pills → 14 px → track-with-markers (32 px высота) → 6 px → labels.

> **Историческая версия** (до правок 2026-05): pill = full-radius capsule `brand/green/600` fill, white 15/800 text, height 34, min-w 64, `shadow/fab` зелёное свечение; markers = bare GiftEmoji 28 px в gap-центрах между пилюлями над hairline divider. Стиль был «насыщенный», но не масштабировался при близком расположении нескольких карточек и плохо читался pill против shadow/fab → перешли на pastel-fill + circle-обёрнутые markers.

### 6.9 iOS status bar (chrome)

- Height **54 px**, transparent over the screen content
- Time **9:41**, SF Pro Display 17/600, tracking `-0.32px`, tabular-nums, aligned `pl-7`
- Right cluster — gap 6 px, padded `pr-6`:
  - Cellular: 4 progressively-taller rounded bars (17×11 viewbox, all filled)
  - Wi-Fi: 3 concentric arcs (16×11)
  - Battery: hairline pill (25×12, 1 px outline @ 55% opacity, radius 3.5) + 2×5 tip, **solid fill bar** representing charge, no % number
- All glyphs inherit `currentColor` — pass `dark` prop for white-on-gradient screens

### 6.10 Device frame

- iPhone 14 viewport 390×844
- Outer bezel radius 56, inner screen radius 48
- Dynamic Island: 110×34, black, top 8 px, centred
- Outer shadow: `0 50px 100px rgba(0,0,0,0.6), inset 0 0 0 1px rgba(255,255,255,0.05), inset 0 0 0 2px rgba(0,0,0,0.6)`

### 6.11 Profile sub-pages (Помощь / О приложении дочерние)

Все полноэкранные страницы, открываемые из списков «Помощь» и «О приложении» в Профиле — FAQ, инструкция, сотрудничество, terms, privacy — следуют единому паттерну. См. также `_reference/profile-pages/README.md`.

**Канвас экрана**
- Background: `#EFF3FB` (pale lavender / blue-50). Specifically **NOT** `paper/DEFAULT` (`#F4F6FA`) — лавандовый мягче, белые карточки на нём читаются лучше.

**Header**
- Back chevron слева, 26 px, `ink/900`, тап-зона 40×40, `active:opacity-60`
- Заголовок центром, 26/800 `ink/900`, `leading-tight` (1.15). Для длинных названий (terms / privacy) — 24/800.
- Отступ от back до заголовка: 12 px вертикально, заголовок имеет нижний паддинг 20 px.

**Question card (FAQ accordion)**
- Белая карточка, `border-radius: 8px` (closed) / `16px` (open)
- Shadow: `0 0 0 1px rgba(15,20,36,0.04)` (hairline) — открытая: `+ 0 2px 12px rgba(15,20,36,0.06)`
- Открытая карточка получает margin top/bottom 8 px (lift-off-list effect)
- Question: 17/700 `ink/900`, leading-snug, paddings 20/18
- Toggle icon: `+` 24×24, stroke 1.4 px square caps. Open state — поворот -45° (cubic-bezier 0.2/0.8/0.2/1, 280 ms) → визуально `×`
- Answer: 15/500 `ink/900`, paddings 20/24, gap 12 px между параграфами
- Bullet: 5×5 круг `ink/900`, gap 12 px до текста

**Inline-маркеры в тексте ответов**
- `[link]…[/link]` → `brand/green/400` (#3DCDA2), `font-weight: 600`, без подчёркивания
- `**…**` → `font-weight: 700`

**Long-doc card (terms / privacy)**
- Одна белая карточка-полотно `bg-white rounded-2xl shadow-card`, padding 24/20, на весь скролл
- Body text: 14/600 `ink/900`, `line-height: 1.55`
- `h2` (раздел): 15/800 `ink/900`, uppercase, `letter-spacing: 0.3px`, margin-top 24
- `li`: bullet 5×5 `ink/900`, gap 12 px, 14/600
- `def`: term жирным (800), em-dash, definition обычным весом
- Footer-микротекст: «ТОО PharmaPay · БИН 230440007098 · …», 12/600 `ink/400`, center

**Partner cards (cooperation)**
- 3 gradient карточки 16-radius, padding 20, white-on-color
- Большой translucent glyph `font-size: 96`, color `white/20` в правом нижнем углу
- Title 20/800 white, body 13/600 white/90
- Gradients: green `#21D17A→#16A65C`, blue `#5560FB→#2A2BE2`, gold `#F4B73A→#D69010`

**Contact list (cooperation)**
- Белая карточка radius 16, shadow/card, list rows разделены hairline (`paper/input`)
- Каждая строка: 40×40 icon-tile `brand/blue/100`, иконка `brand/blue/600`, label 15/700, sub 12/600 `ink/400`, chevron справа

**Instruction carousel (instruction)**
- Phone-mockup 200×400, radius 28 (outer) / 24 (inner), внутри стилизованный экран
- Peeking-phones слева/справа: `opacity: 0.4`
- Bottom pager card: gradient blue `#6A7CFF→#5560FB→#4A56F2` (135°), radius 24, padding 24
- «Шаг N.» 22/800 white, описание 14/600 white/95
- Кнопки: secondary border-white/30 bg-white/10, primary white bg + blue text 15/800
- Step dots: 6×6 white/45, активный 18×6 white, gap 6 px

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

- Tailwind palette: `brand-green-{100…700}`, `brand-blue-{100…700}`, `ink-{300…900}`, `paper-{DEFAULT|card|input}`.
- Custom shadows: `shadow-card`, `shadow-elevated`, `shadow-fab`, `shadow-navTop`.
- Custom gradients are class names (`grad-welcome`, `grad-blueHead`, `grad-receipt`) defined in the `<style>` block of `PharmaPay.html`.
