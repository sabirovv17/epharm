import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  message?: string
}

/**
 * Перехватывает ошибки рендера в дереве админки и показывает fallback с кнопкой
 * перезагрузки — вместо полностью белого `#root` без возможности восстановления.
 * Стили — инлайновые, чтобы fallback работал даже если упала загрузка CSS.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(error: unknown): State {
    return {
      hasError: true,
      message: error instanceof Error ? error.message : String(error),
    }
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    // TODO(P1): отправлять в Sentry. Пока — в консоль для диагностики.
    console.error('Admin UI crashed:', error, info.componentStack)
  }

  render(): ReactNode {
    if (!this.state.hasError) return this.props.children
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: '#F4F6FA',
          fontFamily: 'Manrope, system-ui, sans-serif',
          padding: 24,
        }}
      >
        <div
          style={{
            maxWidth: 440,
            background: '#fff',
            borderRadius: 16,
            padding: '28px 24px',
            boxShadow: '0 8px 32px rgba(15,20,36,0.12)',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: 22, fontWeight: 800, color: '#0F1424', marginBottom: 8 }}>
            Что-то пошло не так
          </div>
          <div style={{ fontSize: 14, color: '#5B6478', marginBottom: 20, lineHeight: 1.5 }}>
            Произошла ошибка в интерфейсе. Перезагрузите страницу — если повторяется, сообщите в
            поддержку.
          </div>
          <button
            type="button"
            onClick={() => window.location.reload()}
            style={{
              background: '#1FA971',
              color: '#fff',
              border: 'none',
              borderRadius: 10,
              padding: '12px 24px',
              fontSize: 15,
              fontWeight: 800,
              cursor: 'pointer',
            }}
          >
            Перезагрузить
          </button>
        </div>
      </div>
    )
  }
}
