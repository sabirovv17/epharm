import { Component, type ErrorInfo, type ReactNode } from 'react'
import * as Sentry from '@sentry/react'

interface Props {
  children: ReactNode
  title: string
}

interface State {
  failed: boolean
  retry: number
}

/** Keeps one optional workspace failure from replacing the entire authenticated application. */
export class SectionErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, retry: 0 }

  static getDerivedStateFromError(): Partial<State> {
    return { failed: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    Sentry.captureException(error, {
      contexts: { react: { componentStack: info.componentStack ?? '' } },
      tags: { boundary: 'section' },
    })
  }

  render() {
    if (this.state.failed) {
      return (
        <div role="alert" className="card space-y-3 p-6">
          <h2 className="text-xl font-bold">{this.props.title}</h2>
          <p className="text-sm text-ink-500">
            Остальные разделы продолжают работать. Повторите открытие этого раздела.
          </p>
          <button
            type="button"
            className="btn btn-outline btn-md"
            onClick={() => this.setState((state) => ({ failed: false, retry: state.retry + 1 }))}
          >
            Повторить
          </button>
        </div>
      )
    }
    return <div key={this.state.retry}>{this.props.children}</div>
  }
}
