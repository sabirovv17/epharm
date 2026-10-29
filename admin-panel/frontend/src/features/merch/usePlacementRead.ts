import { useEffect, useState } from 'react'

/** An old request can neither replace a new selector's data nor retain a denied projection. */
export function usePlacementRead<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{
    load: typeof load
    attempt: number
    data?: T
    error?: unknown
  } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    load(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setState({ load, attempt, data })
      },
      (error) => {
        if (!controller.signal.aborted) setState({ load, attempt, error })
      },
    )
    return () => controller.abort()
  }, [load, attempt])
  const current = state?.load === load && state.attempt === attempt ? state : null
  return {
    data: current?.data,
    error: current?.error,
    pending: !current,
    reload: () => setAttempt((value) => value + 1),
  }
}
