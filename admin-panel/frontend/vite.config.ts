/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'
import { createRequire } from 'node:module'

const sentryCliVersion: string = createRequire(import.meta.url)('@sentry/cli/package.json').version

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    // Injection changes bytes after bundling: keep old cached URLs and injector versions apart.
    { name: 'sentry-debug-id-version', augmentChunkHash: () => `sentry-cli@${sentryCliVersion}` },
  ],
  build: {
    // The release pipeline uploads this exact build, then removes private maps
    // before packaging nginx. Building never needs an upload token.
    sourcemap: 'hidden',
  },
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
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
