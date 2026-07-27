# ── Admin frontend (React + Vite) — сборка статики + раздача через nginx ─────
# nginx отдаёт SPA и проксирует /api/* на backend → единый origin, без CORS-боли.
# Контекст сборки: admin-panel/frontend (см. docker-compose.prod.yml).

FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Пусто = относительные запросы /api/... → их проксирует nginx на backend.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=10s \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
