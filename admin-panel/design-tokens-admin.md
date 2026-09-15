# Admin Console Design Tokens

Current source of truth for the HQ web admin design.

Implementation:

- `admin-panel/frontend/tailwind.config.ts`;
- `admin-panel/frontend/src/index.css`;
- `admin-panel/frontend/src/ui/*`;
- this document.

Historical JSX references in `admin-panel/references/` are visual context only.

## Principle

Admin is a dense desktop work surface. The canvas is quiet neutral gray/white; coral is an accent for
active states and primary actions, not a large background fill. Structure and workflows stay stable;
visual polish is delivered through shared tokens and primitives.

The September 2026 refinement uses Refero references as direction, not as a literal clone:

- Dropbox admin for the solid dark navigation, bright work area and thin dividers;
- Polar and N26 for compact density, flat bordered surfaces and restrained radii;
- Tilda for preserving a single warm coral action accent.

Explicitly rejected: decorative gradients, neon color, oversized pill controls, heavy floating shadows
and excessive card nesting.

Class names still use `brand-green-*` and `brand-blue-*` for compatibility, but both ramps are coral.

## Palette

### Brand Coral (`brand-green-*`)

| Token | Hex       | Use                                                  |
| ----- | --------- | ---------------------------------------------------- |
| 50    | `#FBF5F2` | Selected rows, soft surfaces.                        |
| 100   | `#F6E7E0` | Soft chips and tints.                                |
| 200   | `#EBC9BC` | Borders/rings.                                       |
| 300   | `#DBA18B` | Reserved illustration tint.                          |
| 400   | `#CC7A5C` | Light accent/heatmap.                                |
| 500   | `#C26747` | Mid accent.                                          |
| 600   | `#B95336` | Primary CTA, active tab, sidebar accent, chart line. |
| 700   | `#9C4029` | Active label, hover/pressed CTA, strong tint text.   |
| 800   | `#7C3222` | Deep outline/accent.                                 |

### Coral Accent (`brand-blue-*`)

| Token | Hex       | Use                                |
| ----- | --------- | ---------------------------------- |
| 100   | `#F6E7E0` | Info chip/tile background.         |
| 200   | `#EBC9BC` | Disabled/subtle accent.            |
| 600   | `#B95336` | Role badge, info text, logo stamp. |
| 700   | `#9C4029` | Pressed accent.                    |

### Warm Ink

| Token     | Hex       | Use                                   |
| --------- | --------- | ------------------------------------- |
| `ink-50`  | `#F5F5F3` | Sticky table header, empty icon tile. |
| `ink-100` | `#ECECE9` | Hover surface, neutral chip.          |
| `ink-200` | `#DDDDD8` | Borders, toggle off.                  |
| `ink-300` | `#C8C7C1` | Weak borders/placeholders.            |
| `ink-400` | `#96938D` | Captions, chevrons.                   |
| `ink-500` | `#6B6862` | Secondary text.                       |
| `ink-600` | `#4D4A45` | Strong neutral.                       |
| `ink-700` | `#373531` | Form/body strong.                     |
| `ink-800` | `#272522` | Ink hover.                            |
| `ink-900` | `#1C1B19` | Headings, sidebar base.               |

### Paper

| Token           | Hex       | Use                      |
| --------------- | --------- | ------------------------ |
| `paper.DEFAULT` | `#F7F7F5` | App canvas.              |
| `paper.card`    | `#FFFFFF` | Cards/modals/drawers.    |
| `paper.input`   | `#F3F3F0` | Inputs/search/soft rows. |
| `paper.hover`   | `#F1F1EE` | Hover/footer bands.      |

### Semantic

- `accent.success #16C97A` stays green for approved/success.
- `accent.warning #F1B416`, `accent.amber #F4B73A`, `accent.danger #E5484D`, `accent.purple #8B5CF6`.

## Typography

- Manrope for UI.
- JetBrains Mono for key caps and dense/tabular numbers.
- `.num` applies tabular numbers.

| Role          | Size / line | Weight         |
| ------------- | ----------- | -------------- |
| Page title    | 22 / 28     | 700            |
| Section title | 14 / 20     | 700            |
| KPI value     | 26 / 28     | 700            |
| Table header  | 11 / 14     | 700 uppercase  |
| Table body    | 14 / 20     | 500-800        |
| Form label    | 12 / 16     | 600            |
| Helper        | 12 / 18     | 500            |
| Chip          | 12 / 12     | 600            |
| Sidebar label | 14 / 18     | 600/700 active |
| Key cap       | 11 / 12     | 600 mono       |

Money formatting rule: full KZT only (`1 842 300 ₸`), never abbreviated.

## Layout

| Element              | Size                         |
| -------------------- | ---------------------------- |
| Sidebar expanded     | 260px                        |
| Sidebar collapsed    | 72px                         |
| Sidebar nav row      | 40px high                    |
| Topbar               | 64px                         |
| Content padding      | 24px horizontal, 40px bottom |
| Default card padding | 20px                         |
| Compact card padding | 16px                         |
| Modal width          | 460-620px                    |
| Drawer width         | 480-560px                    |
| Root min width       | 1280px                       |

Use `gap-4`/`gap-5`, not per-card custom margins.

## Radius

- 4px: kbd, heatmap dots;
- 6px: chips;
- 8px: buttons/inputs;
- 10px: compact cards/status tiles;
- 12px: default cards;
- 16px: modals/drawers;
- full: toggles/avatars/pills.

## Core Components

### Sidebar

Solid warm graphite background. Active row uses coral inset border and a subtle neutral fill. Collapsed sidebar
has logo expand target plus floating chevron tab.

### Topbar

White sticky surface, breadcrumb left, fake search/command palette center, period/bell/history/role pill
right.

### Buttons

- `btn-primary`: coral;
- `btn-ink`: strong dark;
- `btn-outline`: white + ink border;
- `btn-ghost`: transparent hover;
- `btn-danger`: red semantic.

### Inputs

38px high, radius 8, white surface, ink border, coral focus ring.

### Tables

Sticky header, separated rows, hover surface, selected row with coral left inset. Status columns use chips.
Numeric columns use `.num`.

### SummaryBar

Use instead of four KPI cards on dense operational pages.

### Modal/Drawer/Toast

White surfaces, warm scrim, soft elevated shadow. Drawer slides from right.

### Command Palette

Top-aligned, `Cmd/Ctrl+K`, filters sections plus selected entities.

## Page Pattern

```tsx
<div className="flex flex-col gap-4">
  <PageHeader title="..." subtitle="..." actions={...} />
  <SummaryBar metrics={...} />
  <div className="grid grid-cols-N gap-4">
    <SectionCard title="...">...</SectionCard>
  </div>
</div>
```

Header actions should be limited to a primary action and one secondary/more menu.

## Anti-Patterns

- Do not fill large admin surfaces with coral.
- Do not put KPI walls on every page.
- Do not add raw hex colors to feature components.
- Do not abbreviate money.
- Do not add mobile layouts to admin pages.
- Do not add separate image URL proxy logic; use the existing `proxyMedia`/backend media proxy.
