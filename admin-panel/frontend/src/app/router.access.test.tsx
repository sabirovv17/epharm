import { afterEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { USERS } from '@/mocks/fixtures'
import { useUiStore } from './store'
import { SectionRoute } from './SectionRoute'

afterEach(() => {
  useUiStore.setState({ authedUser: null, tokens: null })
})

describe('защита прямых URL по роли', () => {
  it('перенаправляет руководителя обучения с общего раздела в LMS', async () => {
    useUiStore.setState({ authedUser: USERS.lms })
    render(
      <MemoryRouter initialEntries={['/rules']}>
        <Routes>
          <Route
            path="/rules"
            element={
              <SectionRoute section="rules">
                <div>RulesPage</div>
              </SectionRoute>
            }
          />
          <Route path="/lms" element={<div>LearningPage</div>} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('LearningPage')).toBeInTheDocument()
    expect(screen.queryByText('RulesPage')).not.toBeInTheDocument()
  })

  it('разрешает основной роли открыть общий раздел', () => {
    useUiStore.setState({ authedUser: USERS.damir })
    render(
      <MemoryRouter initialEntries={['/rules']}>
        <Routes>
          <Route
            path="/rules"
            element={
              <SectionRoute section="rules">
                <div>RulesPage</div>
              </SectionRoute>
            }
          />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('RulesPage')).toBeInTheDocument()
  })

  it('разрешает HQ_HEAD открыть учебный раздел по прямому URL', () => {
    useUiStore.setState({ authedUser: USERS.bauyrzhan })
    render(
      <MemoryRouter initialEntries={['/lms']}>
        <Routes>
          <Route
            path="/lms"
            element={
              <SectionRoute section="lms">
                <div>LearningReadOnlyPage</div>
              </SectionRoute>
            }
          />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('LearningReadOnlyPage')).toBeInTheDocument()
  })
})
