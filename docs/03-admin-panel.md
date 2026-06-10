# Веб-админка (React + Vite)

**Путь:** `admin-panel/frontend/` · **Стек:** React 19 + TypeScript + Vite + Tailwind 3 +
React Router v6 + Zustand 5 + TanStack Query 5 + axios + lucide-react.

HQ-консоль для штаба: управление правилами рекомендаций, аптеками, фармацевтами, выплатами,
сверкой чеков, экранами, обучением. Собирается в статику, отдаётся nginx, который проксирует
`/api/*` на backend.

## Разделы (`src/features/`)

| Раздел           | Папка          | Что делает                                                                                                   |
| ---------------- | -------------- | ------------------------------------------------------------------------------------------------------------ |
| Дашборд          | `dashboard/`   | Обзор HQ: KPI + топ-листы + блоки (lift, heatmap)                                                            |
| **Rules Engine** | `rules/`       | Создание/управление правилами замены и допродажи (JSONB-карточка). Список + live-билдер правила              |
| Промо            | `promo/`       | Кампании брендов. Список + страница-редактор                                                                 |
| **Сверка**       | `reconcile/`   | Очередь модерации чеков (pending/approved/rejected) + drawer с фото и позициями. Approve/Reject → начисление |
| Экраны           | `screens/`     | Загрузка медиа в MinIO, сборка плейлистов, назначение на аптеку (per-screen targeting)                       |
| Финансы          | `finance/`     | Батчи выплат: генерация → согласование → выплата                                                             |
| Аптеки           | `pharmacies/`  | Сети + аптеки, табы по группам (pilot/control/rolled), детальная страница                                    |
| Фармацевты       | `pharmacists/` | Реестр, блокировка/разблокировка, табы по статусу                                                            |
| Lift             | `lift/`        | Аналитика pilot vs control по сетям                                                                          |
| LMS              | `lms/`         | Каталог курсов, CRUD, табы published/draft                                                                   |
| AI-Exam          | `ai-exam/`     | Банк вопросов для пост-курсового диалога                                                                     |
| Витрина          | `storefront/`  | Read-only каталог Medusa (то же, что видит мобилка), серверный поиск + пагинация                             |
| Настройки        | `settings/`    | Таймзона, язык, logout (без бэкенд-состояния)                                                                |

## Роутинг (`src/app/router.tsx`)

React Router v6, lazy-loaded страницы, Suspense fallback.

```
/login                      LoginPage (public, без AppShell)
/dashboard                  /rules (главная по умолчанию)
/promo  /promo/:id          /pharmacies  /pharmacies/:id
/screens  /reconcile  /ai-exam  /finance  /lift  /lms  /settings  /storefront
*                           NotFoundPage
```

- Гард `RequireAuth` на всех маршрутах кроме `/login` — читает `authedUser` из Zustand.
- `SECTION_ROUTES` — общая карта путей для Sidebar и стора.

## Состояние и данные

**Zustand-стор** (`src/app/store.ts`, `useUiStore()`):

- `authedUser` — JWT + email + роль; `tokens` — access/refresh + сроки
- `period` — выбранный месяц/год (single source of truth для фильтров)
- `language` — `ru`/`kk` (в localStorage)
- UI-флаги: `sidebarCollapsed`, `commandPaletteOpen`, `roleSwitcherOpen`, `contractModalOpen`
- `init()` гидратирует auth синхронно (фикс гонки `Cmd+R` разлогинивает)

**API-клиент** (`src/lib/api.ts`, axios-синглтон):

- Base URL = `VITE_API_BASE_URL` (в проде пусто → относительные `/api/...`, проксирует nginx)
- Request-interceptor: `Authorization: Bearer <access>`
- Response-interceptor: на 401 → refresh через `/api/admin/auth/refresh` (дедуп параллельных) → ретрай или force-logout

**TanStack Query v5** (`src/lib/queries/*`):

- Иерархические ключи, инвалидация кэша на мутациях
- Персист кэша в localStorage (`src/app/queryPersist.ts`), очистка на logout

**Типы** (`src/lib/api-types.ts`): `UserDto`, `AuthTokens`, `RuleDto`, `PromoDto`, `PharmacyDto`,
`ReceiptDto`, `PayoutBatchDto`, `CourseDto`, `ExamQuestionDto`, `RuleCardDto` (богатая карточка-сравнение) и др.

## UI-kit и каркас

**UI-компоненты** (`src/ui/*.tsx`): `Button`, `Input`, `Select`, `Toggle`, `Field`, `Metric`,
`ProgressBar`, `Sparkline`, `StatusChip`, `Avatar`, `SectionCard`, `PageHeader`, `Modal`,
`Drawer`, `Tabs`, `Empty`, `ComingSoonBanner`, `SearchInput`, `ToastHost`; иконки — `src/ui/icons.tsx` (Lucide).

**Каркас** (`src/layout/*.tsx`): `Sidebar` (13 пунктов, collapsible, contract-widget),
`Topbar` (роль-пилюля + RoleSwitcher, PeriodPicker, переключатель языка, logout),
`CommandPalette` (`Cmd+K`), `ContractModal`, `Logo`, `PeriodPicker`.

**i18n** (`src/i18n/`): лёгкий хук `useT()` поверх Zustand-языка, словарь `dict.ts` (ru/kk),
интерполяция `{name}`. Покрыты nav, заголовки, кнопки, ошибки, все 13 разделов.

**ErrorBoundary** (`src/app/ErrorBoundary.tsx`) — class-компонент с inline-fallback (работает,
даже если CSS не загрузился).

## Сборка и деплой

- **Vite** (`vite.config.ts`): React-плагин, alias `@`→`src/`, Vitest (jsdom).
- **Tailwind** (`tailwind.config.ts`): дизайн-токены из `design-tokens-admin.md` (палитра brand/ink/paper/accent, Manrope + JetBrains Mono, тени, радиусы).
- **Docker** (`Dockerfile`): multi-stage (Node build → nginx alpine). Build-arg `VITE_API_BASE_URL`.
- **nginx** (`nginx.conf`): проксирует `/api/*` → `backend:8080`, SPA-fallback на `index.html`,
  security-заголовки (X-Frame-Options, CSP, X-Content-Type-Options), gzip, кэш `/assets/*` на год,
  `listen [::]:80` (dual-stack — чинит healthcheck по IPv6).

## Тесты

- **Vitest** + Testing Library + jsdom, MSW-хендлеры (`src/test/setup.ts`).
- **Playwright** E2E (`e2e/*.spec.ts`): auth, rules, promo, reconcile, смена языка и др.

```bash
cd admin-panel/frontend
npm install
npm run dev        # localhost:5173 (через Vite proxy на backend)
npm test           # Vitest
npm run build      # dist/
```
