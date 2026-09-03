This is a [Next.js](https://nextjs.org) project bootstrapped with [`create-next-app`](https://nextjs.org/docs/app/api-reference/cli/create-next-app).

## Витрина DARIHANA — контент-админка и эксплуатация

**Цены и поиск.** У Medusa-канала «Сайт» `calculated_price` пуст — реальные розничные цены живут per-аптека. Их отдаёт `GET /store/products/:id/pharmacies`; на витрине это `src/lib/medusa.ts` → `getPharmacyPrices`. Страница товара показывает «от X ₸» + блок «Цены в аптеках»; карточки листинга подтягивают мин-цену лениво через `/api/price/[id]` (хук `src/lib/price/usePrice.ts`, кэш + семафор на 5). Поиск серверный по всей базе: `/api/search?q=` → `searchMedusaProducts` (не клиентский фильтр по первым 100).

**Админка контента** (`/admin`): баннеры и видео-сторис, читают и сайт, и мобильное приложение через `GET /api/content`.

- `ADMIN_TOKEN` (**server-only**, без `NEXT_PUBLIC_`) — единственный ключ записи. Не задан → запись и загрузка запрещены (fail-closed). Вход в админку проверяется на сервере (`POST /api/admin/verify`), PIN в бандл не попадает.
- Загрузка медиа: `POST /api/upload` (заголовок `x-admin-token`) → `public/uploads/<uuid>.<ext>`. Лимиты: изображения 8 МБ, видео 60 МБ; rate-limit **30 файлов/мин на IP** (429 сверх лимита) — `src/lib/rateLimit.ts`.
- Чистка мусора: при сохранении контента (`writeContent`) файлы в `public/uploads`, на которые контент больше не ссылается и которые старше 1 ч, удаляются автоматически (`cleanupOrphans`).

**Карты аптек.** Страница `/pharmacies` и выбор самовывоза используют Яндекс Карты JS API 2.1. Задайте клиентский ключ в `NEXT_PUBLIC_YANDEX_MAPS_KEY` и разрешите в кабинете Яндекса домены витрины. Без ключа адреса и выбор аптеки продолжают работать списком.

**Хранилище контента** — `.data/content.json` на долгоживущем Node-хосте (`next start` / свой сервер). На serverless-ФС (Vercel) запись эфемерна и `public/uploads` недоступен на запись — там нужен KV/БД + объектное хранилище (S3/R2), а rate-limit/чистку — на общий стор (Redis), т.к. in-memory не делится между инстансами.

## Getting Started

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
# or
bun dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fonts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Vercel.

## Learn More

To learn more about Next.js, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.

You can check out [the Next.js GitHub repository](https://github.com/vercel/next.js) - your feedback and contributions are welcome!

## Deploy on Vercel

The easiest way to deploy your Next.js app is to use the [Vercel Platform](https://vercel.com/new?utm_medium=default-template&filter=next.js&utm_source=create-next-app&utm_campaign=create-next-app-readme) from the creators of Next.js.

Check out our [Next.js deployment documentation](https://nextjs.org/docs/app/building-your-application/deploying) for more details.
