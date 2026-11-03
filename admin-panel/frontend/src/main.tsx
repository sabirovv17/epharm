import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import * as Sentry from '@sentry/react'
import './index.css'
import { App } from './app/App'
import { captureMerchClaimNavigation } from './features/merch/claimNavigation'
import { captureStaffEntryNavigation } from './features/merch/staffEntryNavigation'
import { privateMonitoringOptions } from './lib/monitoring'

captureMerchClaimNavigation()
captureStaffEntryNavigation()

const sentryDsn = import.meta.env.VITE_SENTRY_DSN?.trim()
if (sentryDsn) {
  Sentry.init(
    privateMonitoringOptions({
      dsn: sentryDsn,
      releaseId: import.meta.env.VITE_RELEASE_ID || 'dev',
      environment: import.meta.env.VITE_APP_ENVIRONMENT || 'development',
    }),
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
