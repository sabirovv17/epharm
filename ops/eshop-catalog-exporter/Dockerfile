FROM node:24.21.0-bookworm-slim

ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY catalog.mjs server.mjs healthcheck.mjs ./
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD node /app/healthcheck.mjs
CMD ["node", "/app/server.mjs"]
