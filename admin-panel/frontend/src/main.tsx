import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import * as Sentry from '@sentry/react'
import './index.css'
import { App } from './app/App'

const sentryDsn = import.meta.env.VITE_SENTRY_DSN?.trim()
if (sentryDsn) {
  const configuredSampleRate = Number(import.meta.env.VITE_SENTRY_TRACES_SAMPLE_RATE ?? '0.05')
  Sentry.init({
    dsn: sentryDsn,
    environment: import.meta.env.VITE_APP_ENVIRONMENT || 'development',
    release: `epharm-admin@${import.meta.env.VITE_RELEASE_ID || 'dev'}`,
    integrations: [Sentry.browserTracingIntegration()],
    tracesSampleRate: Number.isFinite(configuredSampleRate) ? configuredSampleRate : 0.05,
    sendDefaultPii: false,
  })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
