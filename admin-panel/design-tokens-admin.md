# PharmaPay Admin Console — Design Tokens

Дизайн-токены для **HQ web** админ-консоли PharmaPay (`PharmaPay Admin.html` + `admin-panel/references/*`). Это **продолжение** мобильного design system'а (см. `design-tokens.md`), а не отдельная система: бренд-зелёный, бренд-синий, Manrope, логотип — общие. Этот файл описывает то, что админ-консоль **добавляет** поверх мобильной базы: расширенную палитру серых и зелёных, монохромный шрифт для табличных чисел, плотную сетку, sidebar, topbar, таблицы, модалки, дровер и командную палитру.

> Source-of-truth: Tailwind config + `<style>` блок внутри `PharmaPay Admin.html`. JSX-имплементация — в `admin-panel/references/`.

---

## 1. Когда что использовать

| Поверхность   | Где жить зелёному                                                     | Где жить синему                                                         |
| ------------- | --------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Mobile app    | Hero — заливает шапку, welcome-карточку, primary CTA, бонусную карту  | Акценты — NEW-бэйдж, success-disc, ссылки                               |
| Admin console | Accent — активный пункт sidebar, primary CTA, success-чип, lift-линия | Info-чип, role-бэйдж, иконки в blue-tile (например, профайл-роу аптеки) |

Канва админки **тихая и светлая** (`paper/DEFAULT`). Бренд-зелёный должен «выстреливать» точечно: тёмный sidebar `ink/900` существует именно для того, чтобы зелёному было от чего отталкиваться. Не заливай большие площади зелёным.

---

## 2. Палитра

### 2.1 Brand green — расширенная шкала (50–800)

| Token             | Hex       | Где                                                                                               |
| ----------------- | --------- | ------------------------------------------------------------------------------------------------- |
| `brand/green/50`  | `#EDFBF3` | Выделенная строка таблицы, soft-success surface, фон KPI-плитки                                   |
| `brand/green/100` | `#D7F5E4` | Фон chip-green, прогресс-филл курсов, heatmap level 1                                             |
| `brand/green/200` | `#A9EBC6` | Selection highlight, heatmap level 2, ring/border                                                 |
| `brand/green/300` | `#6FDDA0` | Зарезервирован — для illustration                                                                 |
| `brand/green/400` | `#3DCDA2` | Heatmap level 3, градиент-стоп карточек курсов                                                    |
| `brand/green/500` | `#21D17A` | Mid-stop header gradient (общий с mobile)                                                         |
| `brand/green/600` | `#16C97A` | **Primary CTA**, активный таб-индикатор, акцент sidebar-active, sparkline stroke, heatmap level 4 |
| `brand/green/700` | `#0F8F55` | Лейбл активного таба, текст success-чипа, hover/pressed CTA, sidebar contract-widget              |
| `brand/green/800` | `#0B6E42` | Зарезервирован — outline на самых тёмных поверхностях                                             |

### 2.2 Brand blue

Hex-коды те же, что в mobile spec. В админке используется дополнительно:

| Token            | Hex       | Где (admin)                                            |
| ---------------- | --------- | ------------------------------------------------------ |
| `brand/blue/100` | `#E8EAFE` | Фон info-chip, профайл-роу иконки, синяя мини-карточка |
| `brand/blue/200` | `#C9CCFF` | Disabled-blue, тонкие акценты                          |
| `brand/blue/600` | `#2A2BE2` | Текст info-чипа, role-бэйдж, stamp в логотипе          |
| `brand/blue/700` | `#1F1FCC` | Pressed blue CTA                                       |

### 2.3 Ink — нейтральная шкала (11 ступеней)

| Token     | Hex       | Где                                                                       |
| --------- | --------- | ------------------------------------------------------------------------- |
| `ink/50`  | `#F7F8FB` | Empty-state icon tile, sticky table-header surface                        |
| `ink/100` | `#EEF0F5` | Hover icon-buttons, neutral chip background, heatmap level 0, key-cap фон |
| `ink/200` | `#E1E4EC` | Toggle off track, button outline, вертикальные сепараторы в topbar        |
| `ink/300` | `#C2C7D2` | Drag-handle glyph, dashed strokes, weak placeholders                      |
| `ink/400` | `#9098A6` | Caption text, empty-state icons, chevron-right в роу                      |
| `ink/500` | `#5A6173` | Secondary body, table-header label, sub-titles                            |
| `ink/600` | `#3F465A` | Strong neutral text (button-ghost label, kbd text)                        |
| `ink/700` | `#2A2F40` | Body strong (form fields, helper text)                                    |
| `ink/800` | `#1A1D2B` | Hover state ink CTAs                                                      |
| `ink/900` | `#0F1424` | Headings, table values, dark CTA fill, sidebar-bg base                    |

### 2.4 Paper — поверхности

| Token           | Hex       | Где                                                     |
| --------------- | --------- | ------------------------------------------------------- |
| `paper/DEFAULT` | `#F4F6FA` | App canvas (фон main-зоны)                              |
| `paper/card`    | `#FFFFFF` | Все белые карточки, тела модалок/дровера                |
| `paper/input`   | `#F2F4F8` | Soft input variant, command-palette search, fake-чек    |
| `paper/hover`   | `#F7F9FC` | Row hover, sticky toolbar background, modal footer band |

### 2.5 Accent / status

| Token            | Hex                   | Где                                    |
| ---------------- | --------------------- | -------------------------------------- |
| `accent/success` | `#16C97A`             | Совпадает с brand/green/600            |
| `accent/warning` | `#F1B416`             | Toggle/Pause, warning chip             |
| `accent/amber`   | `#F4B73A`             | Coin glyph, Platinum-tier, OCR-medium  |
| `accent/danger`  | `#E5484D`             | Danger-кнопка, error-chip              |
| `accent/purple`  | `#8B5CF6`             | Третичный акцент (цвета сетей, курсов) |
| Red surfaces     | `#FEE2E2` / `#B91C1C` | Danger-кнопка фон / нажатый текст      |
| Amber surfaces   | `#FEF3C7` / `#B45309` | Warning-chip фон / текст               |

---

## 3. Типографика

Тот же **Manrope** для всего UI. Админка добавляет **JetBrains Mono** — только для табличных чисел и key-cap'ов.

```html
<link
  href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap"
  rel="stylesheet"
/>
```

| Роль             | Шрифт          | Размер / Line | Вес                                | Где                                                 |
| ---------------- | -------------- | ------------- | ---------------------------------- | --------------------------------------------------- |
| Page title (H1)  | Manrope        | 24 / 30       | 800                                | `PageHeader` title                                  |
| Section title    | Manrope        | 15 / 22       | 800                                | Шапка `SectionCard`                                 |
| KPI value        | Manrope        | 28 / 28       | 800                                | `Metric` (с `tracking-tight`)                       |
| Table header     | Manrope        | 11 / 14       | 600 uppercase, ls 0.04em           | `<th>`                                              |
| Table body       | Manrope        | 14 / 20       | 500–800                            | `<td>` — 800 для основного, 500 для второстепенного |
| Form label       | Manrope        | 12 / 16       | 600                                | `Field` label                                       |
| Helper / hint    | Manrope        | 12 / 18       | 500                                | `Field` hint и error                                |
| Chip text        | Manrope        | 12 / 12       | 600                                | `.chip`                                             |
| Sidebar label    | Manrope        | 14 / 18       | 600 (700 active)                   | Nav item                                            |
| Sidebar group    | Manrope        | 10 / 12       | 700 uppercase, ls 0.1em, 40% white | Group header                                        |
| Eyebrow / kicker | Manrope        | 11 / 14       | 700 uppercase, ls 0.06em           | Step-лейблы в rule-builder                          |
| Tabular numbers  | JetBrains Mono | inherits      | 500–600                            | Axis ticks, kbd, fake-чек                           |
| Key-cap (`.kbd`) | JetBrains Mono | 11 / 12       | 600                                | `⌘`, `K`, `esc`                                     |

Утилита `.num` (`font-variant-numeric: tabular-nums`) — на любое число: валюту, проценты, ID, табличные ячейки.

### 3.1 Деньги и числа — правила форматирования

- **Все суммы пишутся полностью**: `1 842 300 ₸`, никогда `1.84 М ₸`. Хелпер `AD.fmtKzt(n)` — `new Intl.NumberFormat('ru-RU').format(n) + ' ₸'`.
- Простые счётчики (без валюты) — `AD.fmt(n)`. NBSP-группировка тысяч.
- Везде, где есть число, добавь `.num` — таблицы выровняются ровно.
- Телефоны и ИИНы — тоже `.num`.

---

## 4. Размеры и сетка

Шаг 4 px. Десктоп-специфичные размеры:

| Элемент                        | Размер                                              |
| ------------------------------ | --------------------------------------------------- |
| Sidebar — expanded             | **260 px**                                          |
| Sidebar — collapsed            | **72 px**                                           |
| Sidebar nav row                | h 40, padding-x 10, gap 12                          |
| Topbar                         | h 64, padding-x 24                                  |
| Page max-width                 | нет — заполняет вьюпорт (min-width 1280 px на root) |
| Content padding                | 24 horizontal, 0 top, 40 bottom                     |
| `.card` padding (default)      | 20                                                  |
| `.card-soft` padding (compact) | 16                                                  |
| Vertical rhythm between cards  | 16–20 (`gap-4` / `gap-5`)                           |
| Table cell padding             | 10/12 (header), 14/12 (body)                        |
| Modal width                    | 460–620                                             |
| Drawer width                   | 480–560 (правый край)                               |

---

## 5. Радиусы

В админке радиусы **меньше**, чем в мобиле:

- `radius/xs` (4 px) — kbd, точки в heatmap
- `radius/sm` (6 px) — chip, step-индикатор
- `radius/md` (10 px) — кнопки, inputs, search-pills, иконки строк
- `radius/lg` (12 px) — `.card-soft`, status icon-tiles
- `radius/xl` (16 px) — `.card` (default), modal body
- `radius/2xl` (20 px) — внешний контур модалок, углы drawer
- `radius/full` — toggle, avatar, kbd-dot, chip-dot

---

## 6. Тени

| Token             | Value                                                             | Где                                         |
| ----------------- | ----------------------------------------------------------------- | ------------------------------------------- |
| `shadow/card`     | `0 1px 2px rgba(15,20,36,0.04), 0 4px 16px rgba(15,20,36,0.06)`   | `.card`, строки списка                      |
| `shadow/elevated` | `0 4px 8px rgba(15,20,36,0.06), 0 12px 32px rgba(15,20,36,0.10)`  | Модалки, дропдауны, тосты, command palette  |
| `shadow/fab`      | `0 8px 20px rgba(22,201,122,0.35)`                                | Primary green CTA glow                      |
| `shadow/sidebar`  | `inset -1px 0 0 rgba(15,20,36,0.06)`                              | Правый край light-sidebar (зарезервировано) |
| `shadow/kbd`      | `0 1px 0 rgba(15,20,36,0.12), inset 0 -1px 0 rgba(15,20,36,0.08)` | Key-cap depth                               |

Sidebar-expand tab имеет кастомную тень `4px 0 12px rgba(15,20,36,0.18)` — чтобы плавающий chevron «висел» над канвой.

---

## 7. Layout-компоненты

### 7.1 Sidebar

- **Фон**: `radial-gradient(120% 80% at 0% 0%, #15192A 0%, #0F1424 60%) #0F1424` (почти чёрный с тёплым акцентом в левом верхнем углу).
- **Active row** (`.sidebar-active`): `linear-gradient(180deg, rgba(22,201,122,0.18), rgba(22,201,122,0.08))` + `inset 2px 0 0 #16C97A` + иконка/лейбл уходят в `text-brand-green-400` / белый.
- **Hover row** (`.sidebar-hover:hover`): `rgba(255,255,255,0.04)` — едва заметный лифт.
- **Group headers** — uppercase font-bold 10/12 на 40% белого, padding-x 16, margin-bottom 6.
- **Логотип** — 64-tall блок, отделён `border-b border-white/5`. В свёрнутом виде логотип-плитка сама становится кнопкой развернуть **+** плавающий tab на внешнем правом крае (24×48, ink/900, `shadow/elevated`). Два пути к развернуть — намеренно.
- **Contract widget** внизу — карточка `bg-gradient-to-br from-brand-green-700/40 to-brand-green-700/10`, показывает бренд, количество брендов, % использованного бюджета, прогресс-бар, абсолютные ₸. Кликом открывает `ContractModal`.

### 7.2 Topbar

- Surface белый, `border-bottom: 1px solid rgba(15,20,36,0.06)`. Sticky `top: 0`, `z-index: 30`.
- **Слева** — хлебная крошка (`HQ › <Section>`).
- **По центру** — фейковый search input (кнопка стилизованная под input), открывает command palette. Справа kbd-подсказка `⌘ K`.
- **Справа** — селектор периода (Май 2026 ▾), bell с красной точкой, history, вертикальный разделитель, role-пилюля (avatar + ФИО + role/company стэком, chevron-down). Клик → role-switcher modal.

---

## 8. Контролы

### 8.1 Buttons (`.btn`)

Размеры: `btn-sm` (h 32), `btn-md` (h 38, default), `btn-lg` (h 44). Icon-only: `btn-icon` (36×36).

| Variant       | Background                      | Text                    | Border                  | Где                                     |
| ------------- | ------------------------------- | ----------------------- | ----------------------- | --------------------------------------- |
| `btn-primary` | `brand/green/600` (hover `700`) | white                   | —                       | Главные CTA (Save, Create, Approve)     |
| `btn-ink`     | `ink/900` (hover `800`)         | white                   | —                       | Strong-secondary (Refresh, тёмные CTA)  |
| `btn-ghost`   | transparent (hover `ink/100`)   | `ink/600` (hover `900`) | —                       | Cancel, link-like, default icon buttons |
| `btn-outline` | white (hover `paper/hover`)     | `ink/900`               | `ink/200` (hover `300`) | Третичные, secondary в модалках         |
| `btn-danger`  | white (hover `#FEE2E2`)         | `#B91C1C`               | `#FECACA`               | Деструктив (Block user, Archive)        |
| `btn-icon`    | inherits                        | —                       | —                       | Квадрат-иконка с опциональным `.tip`    |

Все: weight 600, radius 10, transition 80–120 ms, `active:scale(0.985)`.

### 8.2 Inputs (`.inp`)

- h 38, padding-x 12, radius 10, белая поверхность, `1 px ink-200` border.
- Focus: `border-color: brand/green/600` + `box-shadow: 0 0 0 3px rgba(22,201,122,0.18)`.
- Placeholder `ink/400`. Body `ink/900`, 14/20/400.
- Textarea (`<textarea class="inp">`): height auto, padding 10/12, min-height 64, `resize: vertical`.
- Search variant `inp-search` — добавляет 36-px left padding для лидирующего search-glyph (позиционируется absolute).

### 8.3 Select

- Тот же шелл, что у `.inp`, + `appearance-none` + 9-px right padding под `IconChevDown` (absolute, `ink/400`, non-interactive).
- Cursor pointer. Placeholder option `disabled` при пустом value.
- Используется при ≤ ~10 опций; если больше — заменяй на command-palette паттерн.

### 8.4 Toggle (switch)

- 36×20 pill. Off `ink/200`, on `brand/green/600`.
- 16×16 white thumb, top/left 2 (off), left 18 (on). `box-shadow: 0 1px 2px rgba(0,0,0,0.15)`.
- Transition 160 ms на background и позицию thumb.
- Optional `label` — body-strong 13/600 справа.

### 8.5 Tabs

- Underline-стиль. Tab h 40, padding-x 4, gap-between 28.
- Inactive: `ink/500`, weight 600. Hover: `ink/900`.
- Active: `brand/green/700` text + `border-bottom: 2 px brand/green/600`.
- Count-badge: 11/700 в 18-px pill. Active: `brand/green/100` / `brand/green/700`. Inactive: `ink/100` / `ink/500`.

---

## 9. Данные и таблицы

### 9.1 Tables (`.tbl`)

- Full-width, `border-collapse: separate`, no `border-spacing`.
- `<th>` — 11/700 uppercase, ls 0.04em, `ink/500`, padding 10/12, surface `ink/50`, **sticky top** для скроллящихся таблиц.
- `<td>` — 14/500–800 `ink/900`, padding 14/12, `border-bottom: 1 px rgba(15,20,36,0.04)`.
- Row hover: surface `paper/hover`. Selected: `brand/green/50` + `inset 3 px 0 0 brand/green/600` на первой ячейке.
- Числовые ячейки — класс `.num`.
- Используй chips в статус-колонках; inline `<ProgressBar>` (h 6) в процент-колонках.

### 9.2 Chips (`.chip`)

- Inline-flex, gap 6, padding 4/10, radius full, font 12/600.
- Варианты: `chip-green`, `chip-blue`, `chip-amber`, `chip-red`, `chip-ink`.
- Лидирующая точка `.chip-dot` (6×6 round) — в status-чипах усиливает цвет.
- `StatusChip` мэппинг:
  - `active → chip-green + #16C97A`
  - `paused → chip-amber + #F1B416`
  - `draft|archived → chip-ink + #9098A6`
  - `pending → chip-blue + #2A2BE2`
  - `rejected → chip-red + #E5484D`
  - `approved → chip-green`

---

## 10. KPI и графика

### 10.1 KPI tile (`<Metric>`) — для дашборда

- `.card` (white, 16-radius, `shadow/card`), padding 20.
- Top: label (13/600 `ink/500`) слева, 36×36 rounded-12 акцент-плитка справа. Палитра: `green | blue | amber | purple | ink`, каждая = `{accent-100}` фон + `{accent-700}` glyph.
- Value: 28/800 `ink/900`, `tracking-tight`, `tabular-nums`.
- Optional `sub` рядом: 14/600 `ink/500`.
- Bottom: `meta` caption (12/600 `ink/500`) + optional `delta` chip.
- Всегда 4-up: `grid grid-cols-4 gap-4`.

### 10.2 SummaryBar — для перегруженных страниц

Когда 4 больших KPI-плитки забивают экран (как в Rules Engine), они заменяются **горизонтальной summary-bar**:

- Одна белая `.card`, разделена `divide-x divide-ink-100`, 4 секции `flex-1`.
- В каждой секции: 1.5×9 цветной dot слева, label uppercase 11/700, значение 19/800 `.num` + suffix 12/600.
- Экономит ~140 px по высоте, читается так же.

### 10.3 Sparkline / charts

- `<Sparkline>` — width 64–120, height 22–36, stroke 1.8, optional 12 %-fill. Inline в таблицах и метрик-плитках.
- Big chart дашборда: 720×240 viewBox, dual-line (shown vs accepted), 5 горизонтальных grid-линий `rgba(15,20,36,0.06)`, axis-лейблы JetBrains Mono `ink/400`, accepted-точки 3 px white-fill / green-stroke.
- Heatmap: 12 колонок (недели) × 7 рядов (дни). Cell 1:1, radius 3. Палитра `heat-0` (`ink/100`) → `heat-4` (`brand/green/600`).

---

## 11. Overlays

### 11.1 Modal (`<Modal>`)

- Centered, max-width 460–620. Backdrop `rgba(15,20,36,0.45)` + `backdrop-filter: blur(2px)`.
- Body: `.card` + `shadow/elevated`. Header 20/20/12, footer 20/12 (bg `paper/hover`).
- Header: title 16/800 `ink/900`, subtitle 13/500 `ink/500`. Close-кнопка (icon-ghost) справа сверху, ESC закрывает.
- Slide-in: `translateX(24px) → 0` за 220 ms `cubic-bezier(0.2, 0.8, 0.2, 1)` + opacity fade.

### 11.2 Drawer (`<Drawer>`)

- Right-aligned, 480–560 wide, full-height, white.
- Slide-in справа, та же easing что у Modal.
- Header 20/20/16 + bottom-border. Footer 20/12 + `paper/hover`. Content scroll.

### 11.3 Toast (`<ToastHost>`)

- Bottom-center, стак с gap 8, z-index 90.
- `.card` + `shadow/elevated`, padding 10/16, min-width 280.
- Лидирующая 6-px точка (`success / error / info`). Body 14/600 `ink/900`. Action-лейбл справа в `brand/green/700` 13/700.
- Auto-dismiss 3.2 s. API: `push(msg, { kind, action, duration })`.

### 11.4 Command palette (⌘K)

- Top-aligned 12 vh от верха, max-width 560.
- Header: 48-tall row с search glyph + autoFocus input + `esc` kbd.
- Body: scroll-список, item = 28-px icon-плитка + bold label + caption + правый kind-label (`Раздел / Товар / Аптека`).
- Фильтрует названия разделов + top-8 товаров + top-6 аптек. Enter на разделе → переход.
- Триггер: ⌘K / Ctrl+K **или** клик на topbar search input.

---

## 12. Drag & drop

- `.drag-handle` cursor `grab` / `grabbing`. Лидирующая колонка с иконкой на draggable-роу.
- `.dragging` (на исходном элементе во время drag) → opacity 0.4.
- `.drop-over` (на роу под курсором) → `inset 0 2px 0 brand/green/600` сверху как drop-индикатор.
- Используется в: Rules list reorder, Screen playlists reorder.

---

## 13. Empty states

- `<Empty>` — центр, 48-py padding. 56-px icon-плитка в `ink/100` / `ink/400`, title 15/800, body 13/500 `ink/500`, optional CTA.
- `<ComingSoonBanner>` — full-width blue-tinted, info-icon слева, title+body справа. Внутри секций, где данные — заглушка.

---

## 14. Шаблон страницы

Все секции собираются по одному скелету:

```jsx
<div className="flex flex-col gap-4">          {/* gap-5 для дашборда, gap-4 для денсных */}
  <PageHeader title="…" subtitle="…" actions={…} />

  {/* — Тяжёлый дашборд: */}
  <div className="grid grid-cols-4 gap-4">{/* 4 KPI-плитки */}</div>

  {/* — Плотная страница с фокусом на работу: */}
  <SummaryBar metrics={…} />

  <div className="grid grid-cols-N gap-4">
    <SectionCard title="…" action={…}> …таблица / лист / форма… </SectionCard>
    …
  </div>
</div>
```

- `PageHeader`: 24/800 title, 14/500 subtitle (max-width 680), правый кластер кнопок. Не больше **2 кнопок** в шапке — остальное в `⋯` / «Ещё ▾» меню.
- Гриды всегда через `gap-4` (16) или `gap-5` (20) — не per-card margins.
- `SectionCard padded={false}` когда внутри таблица (padding ячеек заменяет).

### 14.1 Антипаттерны (учились на этих ошибках)

- ❌ **Стена из KPI-плиток на каждой странице.** Уже 4 плитки + табы + таблица → перегруз. На плотных страницах заменяй на SummaryBar.
- ❌ **Слишком много кнопок в шапке.** «Импорт CSV», «История версий», «Экспорт», «Новое» — пять кнопок. Оставь **primary + Ещё ▾**.
- ❌ **Тяжёлая строка списка.** На Rules-listе было: drag-handle + 2 продукта + chip + sparkline + 3 метрики + меню `⋯`. Получилось 8 элементов в роу. Сейчас: продукт → продукт + конверсия + бонус + статус. Всё остальное — в правую панель.
- ❌ **Фиксированная высота bottom-sheet'а.** В моб-приложении ту же проблему ловили: `CardSheet` имел `height="88%"`, на маленьких экранах кнопка съезжала. Правило: sheet растёт по контенту, кап `max-height: 88%`. (Перенесено сюда для общей памяти команды.)

---

## 15. Структура файлов

```
admin-panel/
├── design-tokens-admin.md       ← этот документ
└── references/
    ├── app.jsx                  — root, роутер секций, ⌘K listener
    ├── layout.jsx               — Sidebar, Topbar, RoleSwitcher, ContractModal, CommandPalette
    ├── icons.jsx                — Все SVG-иконки админки (Ic… + Object.assign на window)
    ├── ui.jsx                   — Button, IconButton, Input, Select, Toggle, Tabs, StatusChip,
    │                              Avatar, Modal, Drawer, ToastHost, Metric, SectionCard,
    │                              ProgressBar, Sparkline, Empty, ComingSoonBanner
    ├── data.jsx                 — Все fixtures (Rules, Pharmacies, Pharmacists, Payouts, AI Exam,
    │                              Promos, Lift, Screens, LMS) на window.AD
    └── sections/                — По одному файлу на раздел сайдбара
        ├── dashboard.jsx
        ├── rules.jsx            ← Главный раздел, наибольшая детализация
        ├── promo.jsx
        ├── screens.jsx
        ├── pharmacies.jsx
        ├── pharmacists.jsx
        ├── reconcile.jsx        ← Сверка чеков
        ├── ai_exam.jsx          ← AI-Экзаменация
        ├── finance.jsx          ← Финансы / выплаты
        ├── lift.jsx             ← Аналитика lift
        ├── lms.jsx              ← Обучение
        └── settings.jsx

PharmaPay Admin.html             ← корневой HTML, подключает все .jsx через text/babel
```

**Babel scope-правило:** каждый `<script type="text/babel">` транспилируется в свой scope. Чтобы компоненты были видны другим файлам, в конце каждого файла стоит `Object.assign(window, { … })`. Не оборачивай переменные в `const styles = {…}` с одинаковым именем — будет коллизия (см. `claude.md`/правила). Используй уникальные имена или inline-стили.

---

## 16. Чего здесь нет

- **Брендовых ассетов** — логотип PharmaPay лежит в `logo/marks.jsx` (общий с моб. приложением).
- **Шрифтовых файлов** — Manrope и JetBrains Mono грузятся с Google Fonts через `<link>` в шапке HTML.
- **Спецификации `inpharm console` light-зелёного варианта** — текущая сборка чёрного-sidebar + light-canvas; light-sidebar вариант зарезервирован под `shadow/sidebar` и пока не разведён в продакшен.

Любые правки палитры/типографики — синхронизируй с корневым `design-tokens.md` (§1–2).
