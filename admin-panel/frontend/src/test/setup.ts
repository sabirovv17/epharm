// Vitest setup — расширения matchers для @testing-library/react.
// Подключается через vite.config.ts → test.setupFiles.
//
// НЕ импортируем здесь stores / контексты — это вызывает реальные модули типа @/lib/api
// до того, как vi.mock в тестах успеет применить мок. Тесты сами resetят state.
import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

afterEach(() => {
  cleanup()
})
