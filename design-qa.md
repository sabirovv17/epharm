# Design QA — training workspace sidebar

## Evidence

- Source visual truth: `/Users/amir/Desktop/Screenshot 2026-09-15 at 4.19.36 PM.png`
  (the user-provided attachment in this task).
- Browser-rendered implementation: `admin-panel/frontend/docs/reports/training-sidebar-navigation.png`.
- Viewport and CSS size: `1970 × 1315`; device scale factor: `1`.
- Source pixels: `1970 × 1315`; implementation pixels: `1970 × 1315`.
- Density normalization: none required; source and implementation use the same pixel dimensions.
- State: authenticated `TRAINING_MANAGER`, `/lms?tab=courses`, «Онлайн-курсы» active.
- Browser: project Playwright Chromium (`Desktop Chrome`).

## Findings

No actionable P0, P1, or P2 differences remain.

- Information architecture: the source's nine visible LMS tabs and the existing tenth settings tab
  are represented as first-class sidebar entries. The redundant generic «Обучение» entry and the
  horizontal tab strip are absent in the dedicated training workspace.
- Fonts and typography: existing Manrope tokens, weights, line heights, hierarchy, wrapping, and
  antialiasing are preserved. The long «Результаты и экзамены» label remains readable without
  clipping in the 280 px sidebar.
- Spacing and layout rhythm: grouped 40 px navigation rows, compact group headings, consistent icon
  alignment, and the existing page/card grid keep the denser sidebar scannable. All persistent
  controls fit above the fold at the target viewport.
- Colors and visual tokens: the warm dark sidebar, cream canvas, coral active rail/CTA, semantic
  status chips, radii, and elevation use the project's existing design tokens.
- Image quality and asset fidelity: the existing logo and project-native vector icon set are reused;
  no source artwork, logo, or non-standard visual asset was replaced by a placeholder.
- Copy and content: former tab labels are preserved; «Настройки обучения» is intentionally more
  explicit in the sidebar, and each selected section receives a concise contextual subtitle.

## Comparison evidence

- Full view: source and implementation were reviewed at identical `1970 × 1315` dimensions. The
  requested structural change is visible without changing the table density, top bar, CTA placement,
  canvas balance, or core page hierarchy.
- Focused region: a separate crop was not needed because the full-resolution comparison keeps both
  the complete sidebar labels/icons and the former source tab labels readable. The active
  «Онлайн-курсы» state, coral rail, section grouping, and absence of the generic item were checked
  directly.

## Interaction and runtime checks

- Login as training manager and default `/lms` landing.
- Visibility of all ten training destinations and absence of the generic «Обучение» button.
- Programs and online-course navigation, URL deep links, active-state synchronization, and browser
  Back navigation.
- Training-manager write controls, HQ read-only behavior, AI Exam access, and redirect away from
  unauthorized HQ routes.
- Browser console errors: `0`; uncaught page errors: `0`.

## Comparison history

- Pass 1: no P0/P1/P2 visual or interaction findings. No corrective visual iteration was required.

## Open questions

None.

## Follow-up polish

None required for this scope.

final result: passed
