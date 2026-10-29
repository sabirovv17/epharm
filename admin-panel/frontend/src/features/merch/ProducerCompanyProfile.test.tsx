import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ProducerCompanyProfile, {
  type CompanyFields,
  type CompanyProfile,
} from './ProducerCompanyProfile'
import { ProducerApiError, producerClient } from './producer-client'

const fields: CompanyFields = {
  companyName: null,
  legalName: null,
  bin: null,
  legalAddress: null,
  actualAddress: null,
  bankName: null,
  bic: null,
  iban: null,
  kbe: null,
  directorName: null,
  directorPosition: null,
  contactPosition: null,
  contactEmail: null,
  vatRegistered: false,
}
const profile: CompanyProfile = {
  id: 'own-demo-producer',
  revision: 3,
  profile: fields,
  profileComplete: false,
}
afterEach(() => vi.restoreAllMocks())
describe('own producer company profile', () => {
  it('supports explicitly clearing a field without substituting or retaining its old name', async () => {
    const request = vi.spyOn(producerClient, 'request').mockResolvedValue({
      ...profile,
      profile: { ...fields, companyName: 'Старое демо-название' },
      profileComplete: true,
    })
    const user = userEvent.setup()
    render(<ProducerCompanyProfile onClose={vi.fn()} onSaved={vi.fn()} />)
    await user.clear(await screen.findByLabelText('Название компании'))
    await user.click(screen.getByRole('button', { name: 'Сохранить профиль' }))
    await waitFor(() =>
      expect(request.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1),
    )
    const write = request.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(String(write[1]?.body)).profile.companyName).toBeNull()
  })
  it('uses full typed fields and the fetched revision, with no phone/password/tariff write', async () => {
    const request = vi.spyOn(producerClient, 'request').mockResolvedValue(profile)
    const saved = vi.fn()
    const user = userEvent.setup()
    render(<ProducerCompanyProfile onClose={vi.fn()} onSaved={saved} />)
    await user.type(await screen.findByLabelText('Название компании'), 'Демо-организация')
    await user.click(screen.getByRole('button', { name: 'Сохранить профиль' }))
    await waitFor(() => expect(saved).toHaveBeenCalledOnce())
    const write = request.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(write[0]).toBe('/api/producer/profile')
    expect(JSON.parse(String(write[1]?.body))).toEqual({
      expectedRevision: 3,
      profile: { ...fields, companyName: 'Демо-организация' },
    })
    expect(screen.queryByLabelText(/пароль|телефон входа/i)).not.toBeInTheDocument()
  })
  it('preserves unsaved values after a conflict and cannot silently overwrite the newer profile', async () => {
    const request = vi.spyOn(producerClient, 'request').mockImplementation(async (_path, init) => {
      if (init?.method === 'PUT') throw new ProducerApiError(409, 'Профиль уже изменился')
      return profile as never
    })
    const user = userEvent.setup()
    render(<ProducerCompanyProfile onClose={vi.fn()} onSaved={vi.fn()} />)
    await user.type(await screen.findByLabelText('Название компании'), 'Несохранённый черновик')
    await user.click(screen.getByRole('button', { name: 'Сохранить профиль' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Профиль уже изменился')
    expect(screen.getByLabelText('Название компании')).toHaveValue('Несохранённый черновик')
    expect(screen.getByRole('button', { name: 'Сохранить профиль' })).toBeDisabled()
    expect(request.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1)
  })
  it('validates optional banking metadata without claiming payment is connected', async () => {
    const request = vi.spyOn(producerClient, 'request').mockResolvedValue(profile)
    const user = userEvent.setup()
    render(<ProducerCompanyProfile onClose={vi.fn()} onSaved={vi.fn()} />)
    await user.type(await screen.findByLabelText('БИН'), '123')
    expect(screen.getByText('БИН: 12 цифр.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Сохранить профиль' })).toBeDisabled()
    expect(screen.getByText(/Оплата и банковские переводы.*не подключены/)).toBeInTheDocument()
    expect(request.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(0)
  })
})
