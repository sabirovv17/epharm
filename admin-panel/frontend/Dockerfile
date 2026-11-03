# ── Admin frontend (React + Vite) — сборка статики + раздача через nginx ─────
# nginx отдаёт SPA и проксирует /api/* на backend → единый origin, без CORS-боли.
# Контекст сборки: admin-panel/frontend (см. docker-compose.prod.yml).

ARG NODE_VERSION=24.15.0

FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Пусто = относительные запросы /api/... → их проксирует nginx на backend.
ARG VITE_API_BASE_URL=""
ARG VITE_RELEASE_ID=dev
ARG VITE_RELEASE_COMMIT=unknown
ARG VITE_APP_ENVIRONMENT=production
ARG VITE_SENTRY_DSN=""
ARG VITE_SENTRY_TRACES_SAMPLE_RATE=0.05
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_RELEASE_ID=$VITE_RELEASE_ID \
    VITE_RELEASE_COMMIT=$VITE_RELEASE_COMMIT \
    VITE_APP_ENVIRONMENT=$VITE_APP_ENVIRONMENT \
    VITE_SENTRY_DSN=$VITE_SENTRY_DSN \
    VITE_SENTRY_TRACES_SAMPLE_RATE=$VITE_SENTRY_TRACES_SAMPLE_RATE
# SENTRY_AUTH_TOKEN is intentionally never accepted as a build argument: Docker
# build args and layers are not an appropriate secret transport. CI uploads
# maps from its one build before packaging the release-runtime target below.
RUN npm run build:observability
# A build without source-map credentials must not publish maps to nginx.
RUN find dist -type f -name '*.map' -delete

FROM nginx:1.28-alpine AS runtime-base
ARG VITE_RELEASE_ID=dev
ARG VITE_RELEASE_COMMIT=unknown
LABEL org.opencontainers.image.title="Epharm admin frontend" \
      org.opencontainers.image.version="$VITE_RELEASE_ID" \
      org.opencontainers.image.revision="$VITE_RELEASE_COMMIT"
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN rm -rf /usr/share/nginx/html && mkdir -p /usr/share/nginx/html \
    && printf '{"releaseId":"%s","commit":"%s"}\n' "$VITE_RELEASE_ID" "$VITE_RELEASE_COMMIT" \
    > /usr/share/nginx/html/release.json
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=10s \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1

# Trusted CI supplies only the already uploaded build, without private maps.
FROM runtime-base AS release-runtime
COPY dist /usr/share/nginx/html
RUN test -z "$(find /usr/share/nginx/html -type f -name '*.map' -print -quit)"

# Local/Compose builds remain self-contained; release CI uses release-runtime.
FROM runtime-base AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
