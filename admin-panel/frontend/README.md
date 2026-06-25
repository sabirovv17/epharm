# Epharm Admin Frontend

React/Vite production admin console.

## Stack

- React 19;
- Vite 8;
- TypeScript 6;
- Tailwind 3;
- TanStack Query;
- Zustand;
- axios;
- lucide-react;
- Vitest / Testing Library / Playwright.

## Commands

```bash
npm install
npm run dev
npm run lint
npm test
npm run build
npm run test:e2e
```

Dev server:

```text
http://localhost:5173
```

The Vite dev server proxies `/api` to local backend when configured; production build uses relative
`/api` and nginx/Caddy routing.

## Structure

```text
src/
├── app/          # router, store, shell, auth guard
├── layout/       # sidebar/topbar/command palette/modals
├── ui/           # shared components and icons
├── lib/          # api client, types, query helpers
├── features/     # dashboard, promo, rules, screens, etc.
├── i18n/         # ru/kk dictionary and hook
└── test/         # test setup/MSW helpers
```

## Design

Use `../design-tokens-admin.md` and Tailwind tokens. Do not introduce raw colors or unrelated UI
systems.

## Data

Use TanStack Query for server state and the shared axios client in `src/lib/api.ts`. DTOs belong in
`src/lib/api-types.ts` and must mirror backend DTOs.
