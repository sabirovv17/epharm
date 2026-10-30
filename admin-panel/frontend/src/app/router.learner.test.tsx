import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { AppRouter } from './router'
import { useUiStore } from './store'

const learnerTokens = { accessToken: 'router-learner-only', refreshToken: 'router-refresh-only' }
const overview = {
  total: 0,
  inProgress: 0,
  completed: 0,
  overdue: 0,
  assignments: [],
  certificates: [],
  notifications: [],
}
const feed = {
  page: 0,
  size: 20,
  total: 1,
  currency: 'KZT',
  items: [
    {
      campaign: {
        id: 'router-campaign',
        title: 'Группа из личного API',
        dateStart: null,
        dateEnd: null,
      },
      type: 'crosssell',
      trigger: { kind: 'acc_mnn', value: 'router-mnn', label: 'Тестовая МНН' },
      triggerProducts: [],
      offers: [
        {
          ruleId: 'router-rule',
          bonusKzt: 0,
          script: '',
          advantages: [],
          product: {
            id: 'router-product',
            name: 'Предложение из API',
            brand: '',
            mnn: '',
            volume: '',
            barcodes: [],
            ipartId: null,
            catalogPriceKzt: 0,
            catalogUpdatedAt: '2026-10-21T00:00:00Z',
          },
        },
      ],
    },
  ],
}
let unexpected: string[]

function LocationProbe() {
  return <output data-testid="current-learner-path">{useLocation().pathname}</output>
}

function renderRouter(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRouter />
      <LocationProbe />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  useUiStore.setState({ authedUser: null, tokens: null, language: 'ru' })
  unexpected = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input)
    const headers = new Headers(init?.headers)
    const authorized = headers.get('Authorization') === `Bearer ${learnerTokens.accessToken}`
    const publicRequest = path === '/api/mobile/promotions'
    const body = publicRequest
      ? []
      : path === '/api/mobile/auth/me'
        ? {
            id: 'router-learner',
            name: 'Фармацевт',
            balance: 31415,
            phone: '+77000000000',
            pharmacyName: null,
            city: '',
            status: 'active',
          }
        : path === '/api/mobile/training'
          ? overview
          : path === '/api/mobile/me/balance'
            ? { balanceKzt: 31415, currency: 'KZT' }
            : path.startsWith('/api/mobile/me/recommendations?')
              ? feed
              : path === '/api/mobile/merch/tasks/visits/branches'
                ? []
                : undefined
    if (
      (!authorized && !publicRequest) ||
      body === undefined ||
      (init?.method && init.method !== 'GET')
    ) {
      unexpected.push(`${init?.method ?? 'GET'} ${path}`)
      throw new Error(`Denied test request: ${path}`)
    }
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
})

afterEach(() => {
  expect(unexpected, 'The real router must use only mocked learner API requests').toEqual([])
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  document.body.classList.remove('learner-portal')
})

describe('real AppRouter learner personal data routes', () => {
  it('opens recommendations directly using only the persisted learner session', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(learnerTokens))
    renderRouter('/learn/recommendations')
    expect(
      await screen.findByRole('heading', { name: 'Предложение из API', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('current-learner-path')).toHaveTextContent('/learn/recommendations')
    expect(screen.queryByRole('textbox', { name: 'Email', exact: true })).not.toBeInTheDocument()
    expect(useUiStore.getState().authedUser).toBeNull()
  })

  it('navigates from the personal balance through the real recommendations route', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(learnerTokens))
    renderRouter('/learn')
    expect((await screen.findAllByText(/31\s*415\s*₸/))[0]).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Рекомендации', exact: true }))
    expect(
      await screen.findByRole('heading', { name: 'Предложение из API', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('current-learner-path')).toHaveTextContent('/learn/recommendations')
  })

  it('keeps a signed-out direct learner URL on the pharmacist IIN form', async () => {
    renderRouter('/learn/recommendations')
    expect(
      await screen.findByRole('heading', { name: 'Вход для фармацевта', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'ИИН', exact: true })).toBeInTheDocument()
    expect(screen.getByTestId('current-learner-path')).toHaveTextContent('/learn/recommendations')
    expect(screen.queryByRole('textbox', { name: 'Email', exact: true })).not.toBeInTheDocument()
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })
  it('opens the separate monthly visit from the authenticated learner home without administrator credentials', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(learnerTokens))
    renderRouter('/learn')
    await userEvent
      .setup()
      .click(await screen.findByRole('button', { name: 'Фотоотчёт аптеки', exact: true }))
    expect(
      await screen.findByRole('heading', { name: 'Месячный визит', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('current-learner-path')).toHaveTextContent('/learn/merch/visits')
    expect(useUiStore.getState().authedUser).toBeNull()
    const call = vi
      .mocked(globalThis.fetch)
      .mock.calls.find(([path]) => path === '/api/mobile/merch/tasks/visits/branches')!
    expect(new Headers(call[1]?.headers).get('Authorization')).toBe(
      `Bearer ${learnerTokens.accessToken}`,
    )
  })
})
