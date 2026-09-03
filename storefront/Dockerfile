# syntax=docker/dockerfile:1
# Многоступенчатая сборка Next.js 16 (standalone) для прода.

# ── deps: ставим зависимости ──
ARG NODE_VERSION=24.15.0

FROM node:${NODE_VERSION}-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# ── build: собираем .next/standalone ──
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
ARG NEXT_PUBLIC_YANDEX_MAPS_KEY=""
ENV NEXT_PUBLIC_YANDEX_MAPS_KEY=$NEXT_PUBLIC_YANDEX_MAPS_KEY
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ── runner: лёгкий рантайм ──
FROM node:${NODE_VERSION}-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S nodejs && adduser -S nextjs -G nodejs
# standalone-сервер + статика + public (с папкой загрузок)
COPY --chown=nextjs:nodejs --from=build /app/public ./public
COPY --chown=nextjs:nodejs --from=build /app/.next/standalone ./
COPY --chown=nextjs:nodejs --from=build /app/.next/static ./.next/static
COPY --chown=nextjs:nodejs --from=build /app/db ./db
COPY --chown=nextjs:nodejs --from=build /app/scripts ./scripts
RUN mkdir -p ./public/uploads && chown -R nextjs:nodejs ./public/uploads
USER nextjs
EXPOSE 3000
CMD ["node", "scripts/docker-entrypoint.mjs"]
