# Admin UI polish — visual QA

Date: 2026-09-15

## Scope

Visual-only refinement of the shared HQ and Learning admin shell. Navigation, routes, permissions,
content hierarchy and business workflows were intentionally preserved.

## Reference direction

Refero was used to inspect current enterprise administration patterns. The implemented direction
combines a solid dark navigation surface, bright neutral workspace, compact controls, thin dividers,
flat bordered cards and one restrained terracotta action accent. Decorative gradients, neon colors,
large pill controls and floating card shadows were excluded.

## Screens reviewed at 1600 × 1000

- HQ: dashboard, Rules Engine, pharmacy network;
- Learning: dashboard, online courses, assignments.

## Verification criteria

- shared shell and table styling is consistent across both workspaces;
- sidebar has no decorative background gradient;
- core cards use a 12px radius and restrained one-pixel visual edge;
- controls use an 8px radius;
- primary action color `#B95336` has a 4.82:1 contrast ratio against white;
- Learning navigation and routes remain operational;
- lint, unit, production build and Playwright regression suites pass.
