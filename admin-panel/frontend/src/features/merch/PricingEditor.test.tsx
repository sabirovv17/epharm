import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import PricingEditor from './PricingEditor'
import type { Tariff, TariffGateway, TariffIndex } from './tariffs-api'

const original: Tariff = {
  category: 'standard',
  updatedAt: '2026-10-29T10:00:00.123456Z',
  canEdit: true,
  values: {
    label: 'Исходный тариф',
    multiplier: '1.000',
    facingMonthPrice: '3000',
    promotionMonthPrice: '10000',
    trainingEventPrice: '20000',
    motivationPersonPrice: '1000',
    active: true,
  },
}
function gateway(editable = true) {
  return {
    load: vi
      .fn()
      .mockResolvedValue({ items: [{ ...original, canEdit: editable }], canEdit: editable }),
    save: vi.fn(),
  } satisfies TariffGateway
}
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('HQ supplier tariff editor', () => {
  it('uses server capabilities for read only views and does not invent write access', async () => {
    const api = gateway(false)
    render(<PricingEditor gateway={api} />)
    const form = await screen.findByRole('form', { name: 'Тариф standard' })
    expect(within(form).getByLabelText('Название категории')).toBeDisabled()
    expect(within(form).getByRole('button', { name: 'Сохранить тариф' })).toBeDisabled()
    expect(api.save).not.toHaveBeenCalled()
  })

  it('sends exact money strings and the reviewed timestamp and displays the server result', async () => {
    const api = gateway()
    const user = userEvent.setup()
    api.save.mockImplementation(async (_category, _expected, values) => ({
      ...original,
      values,
      updatedAt: '2026-10-29T11:00:00Z',
    }))
    render(<PricingEditor gateway={api} />)
    const amount = await screen.findByLabelText('Фейсинг в месяц, ₸')
    await user.clear(amount)
    await user.type(amount, '9223372036854775807')
    await user.click(screen.getByRole('button', { name: 'Сохранить тариф' }))
    expect(api.save).toHaveBeenCalledWith('standard', original.updatedAt, {
      ...original.values,
      facingMonthPrice: '9223372036854775807',
    })
    expect(await screen.findByText('Тариф сохранён')).toBeInTheDocument()
    expect(screen.getByLabelText('Фейсинг в месяц, ₸')).toHaveValue('9223372036854775807')
    expect(screen.getByRole('button', { name: 'Сохранить тариф' })).toBeDisabled()
  })

  it('keeps failed CAS input in memory and requires a fresh fetch before another write', async () => {
    const api = gateway()
    const user = userEvent.setup()
    api.save.mockRejectedValue({ status: 409, message: 'Тариф изменился' })
    render(<PricingEditor gateway={api} />)
    const label = await screen.findByLabelText('Название категории')
    await user.clear(label)
    await user.type(label, 'Несохранённая версия')
    await user.click(screen.getByRole('button', { name: 'Сохранить тариф' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Загрузите актуальные тарифы')
    expect(label).toHaveValue('Несохранённая версия')
    expect(label).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Сохранить тариф' })).toBeDisabled()
    expect(localStorage.getItem('tariff-draft')).toBeNull()
    api.load.mockResolvedValue({ items: [{ ...original, canEdit: false }], canEdit: false })
    await user.click(screen.getByRole('button', { name: 'Загрузить актуальные тарифы' }))
    expect(await screen.findByLabelText('Название категории')).toHaveValue('Исходный тариф')
    expect(screen.getByLabelText('Название категории')).toBeDisabled()
    expect(api.save).toHaveBeenCalledTimes(1)
  })

  it('replaces normalized input with the saved server values rather than leaving a phantom dirty form', async () => {
    const api = gateway()
    const user = userEvent.setup()
    api.save.mockResolvedValue({
      ...original,
      values: { ...original.values, facingMonthPrice: '4' },
      updatedAt: '2026-10-29T11:00:00Z',
    })
    render(<PricingEditor gateway={api} />)
    const amount = await screen.findByLabelText('Фейсинг в месяц, ₸')
    await user.clear(amount)
    await user.type(amount, '0004')
    await user.click(screen.getByRole('button', { name: 'Сохранить тариф' }))
    expect(await screen.findByText('Тариф сохранён')).toBeInTheDocument()
    expect(amount).toHaveValue('4')
    expect(screen.getByRole('button', { name: 'Сохранить тариф' })).toBeDisabled()
  })

  it('removes financial rows after a refused fresh request and has no fallback to old pricing', async () => {
    const api = gateway()
    const user = userEvent.setup()
    render(<PricingEditor gateway={api} />)
    await screen.findByLabelText('Название категории')
    api.load.mockRejectedValue({ status: 403, message: 'Право отозвано' })
    await user.click(screen.getByRole('button', { name: 'Загрузить актуальные тарифы' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByLabelText('Фейсинг в месяц, ₸')).not.toBeInTheDocument()
    expect(api.save).not.toHaveBeenCalled()
  })

  it('ignores late fetch results after the editor is closed', async () => {
    const api = gateway()
    let resolve!: (value: TariffIndex) => void
    api.load.mockReturnValue(
      new Promise<TariffIndex>((done) => {
        resolve = done
      }),
    )
    const mounted = render(<PricingEditor gateway={api} />)
    await waitFor(() => expect(api.load).toHaveBeenCalledOnce())
    const signal = api.load.mock.calls[0][0]
    mounted.unmount()
    resolve({ items: [original], canEdit: true })
    expect(signal.aborted).toBe(true)
    expect(screen.queryByText('Исходный тариф')).not.toBeInTheDocument()
  })
})
