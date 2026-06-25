# Admin Console Design Tokens

Current source of truth for the HQ web admin design.

Implementation:

- `admin-panel/frontend/tailwind.config.ts`;
- `admin-panel/frontend/src/index.css`;
- `admin-panel/frontend/src/ui/*`;
- this document.

Historical JSX references in `admin-panel/references/` are visual context only.

## Principle

Admin is a dense desktop work surface. The canvas is quiet cream/white; coral is an accent for active
states and primary actions, not a large background fill.

Class names still use `brand-green-*` and `brand-blue-*` for compatibility, but both ramps are coral.

## Palette

### Brand Coral (`brand-green-*`)

| Token | Hex       | Use                                                  |
| ----- | --------- | ---------------------------------------------------- |
| 50    | `#FBF3EE` | Selected rows, soft surfaces.                        |
| 100   | `#F8E7DD` | Soft chips and tints.                                |
| 200   | `#F0C8B4` | Borders/rings.                                       |
| 300   | `#E4A485` | Reserved illustration tint.                          |
| 400   | `#E0916B` | Light accent/heatmap.                                |
| 500   | `#DB7F57` | Mid accent.                                          |
| 600   | `#D97757` | Primary CTA, active tab, sidebar accent, chart line. |
| 700   | `#BE5A38` | Active label, hover/pressed CTA, strong tint text.   |
| 800   | `#9A4427` | Deep outline/accent.                                 |

### Coral Accent (`brand-blue-*`)

| Token | Hex       | Use                                |
| ----- | --------- | ---------------------------------- |
| 100   | `#F8E7DD` | Info chip/tile background.         |
| 200   | `#F0C8B4` | Disabled/subtle accent.            |
| 600   | `#BE5A38` | Role badge, info text, logo stamp. |
| 700   | `#A8472A` | Pressed accent.                    |

### Warm Ink

| Token     | Hex       | Use                                   |
| --------- | --------- | ------------------------------------- |
| `ink-50`  | `#F6F3EE` | Sticky table header, empty icon tile. |
| `ink-100` | `#EFEAE2` | Hover surface, neutral chip.          |
| `ink-200` | `#E2DCD2` | Borders, toggle off.                  |
| `ink-300` | `#D4CCC0` | Weak borders/placeholders.            |
| `ink-400` | `#9D9388` | Captions, chevrons.                   |
| `ink-500` | `#6F665B` | Secondary text.                       |
| `ink-600` | `#514A40` | Strong neutral.                       |
| `ink-700` | `#423B32` | Form/body strong.                     |
| `ink-800` | `#2E2820` | Ink hover.                            |
| `ink-900` | `#221C16` | Headings, sidebar base.               |

### Paper

| Token           | Hex       | Use                      |
| --------------- | --------- | ------------------------ |
| `paper.DEFAULT` | `#FAF7F2` | App canvas.              |
| `paper.card`    | `#FFFFFF` | Cards/modals/drawers.    |
| `paper.input`   | `#F3EEE7` | Inputs/search/soft rows. |
| `paper.hover`   | `#F5F1EA` | Hover/footer bands.      |

### Semantic

- `accent.success #16C97A` stays green for approved/success.
- `accent.warning #F1B416`, `accent.amber #F4B73A`, `accent.danger #E5484D`, `accent.purple #8B5CF6`.

## Typography

- Manrope for UI.
- JetBrains Mono for key caps and dense/tabular numbers.
- `.num` applies tabular numbers.

| Role          | Size / line | Weight         |
| ------------- | ----------- | -------------- |
| Page title    | 24 / 30     | 800            |
| Section title | 15 / 22     | 800            |
| KPI value     | 28 / 28     | 800            |
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
- 10px: buttons/inputs;
- 12px: compact cards/status tiles;
- 16px: default cards;
- 20px: modals/drawers;
- full: toggles/avatars/pills.

## Core Components

### Sidebar

Warm dark radial background. Active row uses coral inset border and subtle coral fill. Collapsed sidebar
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

38px high, radius 10, white surface, ink border, coral focus ring.

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
