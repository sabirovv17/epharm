import * as Sentry from '@sentry/react'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ERROR_TYPES = new Set([
  'Error',
  'TypeError',
  'ReferenceError',
  'RangeError',
  'SyntaxError',
  'URIError',
  'EvalError',
  'AggregateError',
  'AbortError',
  'NetworkError',
  'SecurityError',
  'UnhandledRejection',
])
const ERROR_INTEGRATIONS = new Set([
  'InboundFilters',
  'FunctionToString',
  'BrowserApiErrors',
  'GlobalHandlers',
  'LinkedErrors',
  'Dedupe',
])
type PrivateEvent = Sentry.Event & { event_id: string }

function assetUrl(value: unknown, origin: string): string | undefined {
  if (typeof value !== 'string') return
  try {
    const url = new URL(value, origin)
    if (
      url.origin === origin &&
      /^\/assets\/[A-Za-z0-9_$.-]+-[A-Za-z0-9_-]{8,}\.js$/.test(url.pathname) &&
      !/[0-9]{12}/.test(url.pathname)
    ) {
      return `${origin}${url.pathname}`
    }
  } catch {
    /* Untrusted stack locations must not prevent reporting a valid frame. */
  }
}

function stackFrame(frame: Sentry.StackFrame, origin: string): Sentry.StackFrame | undefined {
  if (!frame || typeof frame !== 'object') return
  const filename = assetUrl(frame.filename, origin) || assetUrl(frame.abs_path, origin)
  if (!filename) return
  const position = (value: unknown) =>
    typeof value === 'number' && Number.isInteger(value) && value >= 0 && value < 100_000_000
      ? value
      : undefined
  return {
    filename,
    abs_path: filename,
    lineno: position(frame.lineno),
    colno: position(frame.colno),
    in_app: frame.in_app !== false,
    ...(typeof frame.debug_id === 'string' && UUID.test(frame.debug_id)
      ? { debug_id: frame.debug_id }
      : {}),
  }
}

// Preserve positions/debug IDs for source mapping, never free text, component props or frame locals.
function errorEvent(
  payload: unknown,
  origin: string,
  release: string,
  environment: string,
): PrivateEvent | null {
  if (!payload || typeof payload !== 'object') return null
  const event = payload as Sentry.Event
  if (
    event.type !== undefined ||
    !Array.isArray(event.exception?.values) ||
    typeof event.event_id !== 'string' ||
    !/^[0-9a-f]{32}$/.test(event.event_id)
  )
    return null
  const values = event.exception.values
    .slice(0, 16)
    .filter((value) => value && typeof value === 'object')
    .map((value) => ({
      type: ERROR_TYPES.has(value.type ?? '') ? value.type : 'Error',
      value: 'Frontend exception; details omitted for privacy',
      mechanism: { type: 'generic', handled: value.mechanism?.handled !== false },
      stacktrace: {
        frames: (Array.isArray(value.stacktrace?.frames) ? value.stacktrace.frames : [])
          .slice(-100)
          .map((frame) => stackFrame(frame, origin))
          .filter((frame): frame is Sentry.StackFrame => !!frame),
      },
    }))
  if (!values.length) return null
  const images = event.debug_meta?.images
    ?.filter(
      (image) =>
        image?.type === 'sourcemap' &&
        UUID.test(image.debug_id) &&
        !!assetUrl(image.code_file, origin),
    )
    .map((image) => ({
      type: 'sourcemap' as const,
      debug_id: image.debug_id,
      code_file: assetUrl(image.code_file, origin)!,
    }))
  return {
    event_id: event.event_id,
    timestamp:
      typeof event.timestamp === 'number' &&
      Number.isFinite(event.timestamp) &&
      event.timestamp >= 0 &&
      event.timestamp < 100_000_000_000
        ? event.timestamp
        : undefined,
    platform: 'javascript',
    level: 'error',
    release,
    environment,
    exception: { values },
    ...(images?.length ? { debug_meta: { images } } : {}),
  }
}

export function privateMonitoringOptions({
  dsn,
  releaseId,
  environment,
}: {
  dsn: string
  releaseId: string
  environment: string
}): Sentry.BrowserOptions {
  const origin = window.location.origin
  const release = `epharm-admin@${/^(dev|v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)$/.test(releaseId) ? releaseId : 'unknown'}`
  const safeEnvironment = ['production', 'staging', 'development', 'test'].includes(environment)
    ? environment
    : 'unknown'
  return {
    dsn,
    release,
    environment: safeEnvironment,
    sendDefaultPii: false,
    defaultIntegrations: Sentry.getDefaultIntegrations({}).filter((integration) =>
      ERROR_INTEGRATIONS.has(integration.name),
    ),
    beforeBreadcrumb: () => null,
    tracesSampleRate: 0,
    enableLogs: false,
    enableMetrics: false,
    sendClientReports: false,
    transport(options) {
      const transport = Sentry.makeFetchTransport(
        {
          ...options,
          headers: undefined,
          fetchOptions: { credentials: 'omit', referrerPolicy: 'no-referrer' },
        },
        globalThis.fetch.bind(globalThis),
      )
      return {
        send(envelope) {
          const events: PrivateEvent[] = []
          for (const [header, payload] of envelope[1]) {
            if (header.type !== 'event') continue
            try {
              const event = errorEvent(payload, origin, release, safeEnvironment)
              if (event) events.push(event)
            } catch {
              /* Malformed SDK/custom payloads fail closed at the final transport boundary. */
            }
          }
          // Sessions, attachments, replay, transactions and logs also bypass beforeSend; drop them here.
          if (!events.length) return Promise.resolve({ statusCode: 200 })
          return transport.send([
            {
              event_id: events[0].event_id,
              sent_at: new Date().toISOString(),
              sdk: { name: 'sentry.javascript.react', version: Sentry.SDK_VERSION },
            },
            events.map((event) => [{ type: 'event' }, event]),
          ])
        },
        flush: (timeout) => transport.flush(timeout),
      }
    },
  }
}
