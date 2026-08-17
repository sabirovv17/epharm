import { afterEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { USERS } from '@/mocks/fixtures'
import NotFoundPage from './NotFoundPage'
import { useUiStore } from './store'

afterEach(() => {
  useUiStore.setState({ authedUser: null, tokens: null })
})

describe('NotFoundPage', () => {
  it('для основной роли показывает ссылку в рабочий раздел', () => {
    useUiStore.setState({ authedUser: USERS.damir })
    render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    )
    expect(screen.getByTestId('not-found')).toBeInTheDocument()
    expect(screen.getByText('404')).toBeInTheDocument()
    expect(screen.getByText(/Страница не найдена/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /На главную/i })).toHaveAttribute('href', '/rules')
  })

  it('для руководителя обучения показывает ссылку в LMS', () => {
    useUiStore.setState({ authedUser: USERS.lms })
    render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: /На главную/i })).toHaveAttribute('href', '/lms')
  })
})
