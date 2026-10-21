/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { sentryVitePlugin } from '@sentry/vite-plugin'
import path from 'node:path'

// https://vite.dev/config/
const releaseId = process.env.VITE_RELEASE_ID || 'dev'
const devApiProxyTarget = process.env.VITE_DEV_API_PROXY_TARGET || 'https://epharm.inkar.kz'
const sentryUploadEnabled = Boolean(
  process.env.SENTRY_AUTH_TOKEN && process.env.SENTRY_ORG && process.env.SENTRY_PROJECT,
)

export default defineConfig({
  plugins: [
    react(),
    ...(sentryUploadEnabled
      ? [
          sentryVitePlugin({
            authToken: process.env.SENTRY_AUTH_TOKEN,
            org: process.env.SENTRY_ORG,
            project: process.env.SENTRY_PROJECT,
            release: { name: `epharm-admin@${releaseId}` },
            sourcemaps: { filesToDeleteAfterUpload: ['./dist/**/*.map'] },
          }),
        ]
      : []),
  ],
  build: {
    // Hidden source maps make browser stack traces useful. They are uploaded only
    // when all Sentry build credentials exist and never shipped by the runtime image.
    sourcemap: 'hidden',
  },
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    proxy: {
      // Learner portal uses same-origin /api URLs in production. Keep the same
      // contract during local demos and forward only API traffic to ePharm.
      '/api': {
        target: devApiProxyTarget,
        changeOrigin: true,
        secure: true,
        headers: {
          // Spring CORS correctly rejects arbitrary browser origins. The browser
          // talks same-origin to Vite, so the server-side proxy supplies the
          // canonical ePharm origin on the upstream request.
          Origin: devApiProxyTarget,
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
