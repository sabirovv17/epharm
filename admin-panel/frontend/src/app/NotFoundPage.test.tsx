import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import NotFoundPage from './NotFoundPage'

describe('NotFoundPage', () => {
  it('показывает 404 + ссылку на главную (вместо тихого редиректа)', () => {
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
})
