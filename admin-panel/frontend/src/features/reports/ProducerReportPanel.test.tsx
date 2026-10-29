import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ProducerReportPanel from './ProducerReportPanel'
import CampaignProducerBinding from './CampaignProducerBinding'
import CreateReportProducer from './CreateReportProducer'
import { producerReportApi } from './producerReports'
import { downloadReport } from './ledgerReports'
vi.mock('./producerReports', () => ({
  producerReportApi: {
    index: vi.fn(),
    createProducer: vi.fn(),
    binding: vi.fn(),
    bind: vi.fn(),
    retract: vi.fn(),
  },
}))
vi.mock('./ledgerReports', () => ({ downloadReport: vi.fn(), saveReport: vi.fn() }))
const producers = [
  {
    id: 'producer-demo',
    name: 'Демо Компания',
    active: true,
    profileComplete: true,
    activeBindingCount: 0,
  },
]
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(producerReportApi.index).mockResolvedValue({
    producers,
    unboundCampaigns: [{ id: 'campaign-demo', title: 'Название бренда не подтверждает компанию' }],
    notice: 'Только подтверждённые связи',
  })
  vi.mocked(producerReportApi.binding).mockResolvedValue({
    promoId: 'campaign-demo',
    revision: 3,
    bindings: [],
  })
  vi.mocked(downloadReport).mockResolvedValue(new Blob(['synthetic-only']))
})
describe('producer report and immutable campaign provenance', () => {
  it('creates a business producer without a login, keeps one request id across an uncertain retry and never auto-links a campaign', async () => {
    vi.mocked(producerReportApi.createProducer)
      .mockRejectedValueOnce(new Error('Соединение прервано'))
      .mockResolvedValueOnce({ id: 'created-demo', name: 'Демо Компания', profileComplete: true })
    const created = vi.fn()
    const user = userEvent.setup()
    render(<CreateReportProducer onCreated={created} />)
    await user.click(screen.getByRole('button', { name: 'Добавить производителя для акций' }))
    await user.type(screen.getByLabelText('Название производителя для акций'), 'Демо Компания')
    await user.type(screen.getByLabelText('Основание нового производителя'), 'DEMO-company-check')
    await user.click(screen.getByRole('checkbox', { name: /Я проверил организацию/ }))
    await user.click(screen.getByRole('button', { name: 'Добавить производителя', exact: true }))
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Название производителя для акций')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Повторить тот же запрос' }))
    await waitFor(() => expect(created).toHaveBeenCalledExactlyOnceWith('created-demo'))
    const calls = vi.mocked(producerReportApi.createProducer).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[1][0]).toEqual(calls[0][0])
    expect(calls[0][0]).toEqual({
      requestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      name: 'Демо Компания',
      reference: 'DEMO-company-check',
    })
    expect(producerReportApi.bind).not.toHaveBeenCalled()
    expect(
      JSON.stringify([Object.values(localStorage), Object.values(sessionStorage)]),
    ).not.toContain('DEMO-company-check')
  })
  it('does not fetch a private producer index without server capabilities', () => {
    render(<ProducerReportPanel canExport={false} canManageBindings={false} campaignId="" />)
    expect(producerReportApi.index).not.toHaveBeenCalled()
  })
  it('requires an explicit producer choice; shows unbound diagnostics and downloads exact dates without guessing from a brand', async () => {
    const user = userEvent.setup()
    render(<ProducerReportPanel canExport canManageBindings={false} campaignId="" />)
    const select = await screen.findByRole('combobox', { name: 'Производитель отчёта' })
    expect(select).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Excel: производитель' })).toBeDisabled()
    await user.selectOptions(select, 'producer-demo')
    await user.clear(screen.getByLabelText('Начало сводного периода'))
    await user.type(screen.getByLabelText('Начало сводного периода'), '2026-10-01')
    await user.clear(screen.getByLabelText('Окончание сводного периода'))
    await user.type(screen.getByLabelText('Окончание сводного периода'), '2026-10-29')
    await user.click(screen.getByRole('button', { name: 'Excel: производитель' }))
    await waitFor(() =>
      expect(downloadReport).toHaveBeenCalledExactlyOnceWith(
        '/api/admin/reports/producers/producer-demo/xlsx',
        { from: '2026-10-01', to: '2026-10-29' },
        expect.any(AbortSignal),
      ),
    )
    expect(screen.getByText('Без подтверждённого производителя: 1')).toBeInTheDocument()
    expect(producerReportApi.bind).not.toHaveBeenCalled()
  })
  it('adds a binding only with explicit period, reference, acknowledgement and exact revision', async () => {
    vi.mocked(producerReportApi.bind).mockResolvedValue({
      promoId: 'campaign-demo',
      revision: 4,
      bindings: [],
    })
    const user = userEvent.setup()
    render(
      <CampaignProducerBinding
        campaignId="campaign-demo"
        producers={producers}
        onChanged={vi.fn()}
      />,
    )
    await user.click(
      await screen.findByRole('button', { name: 'Подтвердить связь с производителем' }),
    )
    expect(screen.getByRole('button', { name: 'Подтвердить изменение связи' })).toBeDisabled()
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Производитель связи кампании' }),
      'producer-demo',
    )
    await user.type(screen.getByLabelText('Начало связи кампании'), '2026-09-01')
    await user.type(screen.getByLabelText('Окончание связи кампании'), '2026-09-30')
    await user.type(screen.getByLabelText('Основание связи кампании'), 'DEMO-reference')
    await user.click(screen.getByRole('checkbox', { name: /Я проверил производителя/ }))
    await user.click(screen.getByRole('button', { name: 'Подтвердить изменение связи' }))
    await waitFor(() =>
      expect(producerReportApi.bind).toHaveBeenCalledExactlyOnceWith('campaign-demo', {
        expectedRevision: 3,
        producerId: 'producer-demo',
        wholeHistory: false,
        from: '2026-09-01',
        to: '2026-09-30',
        reference: 'DEMO-reference',
      }),
    )
    expect(producerReportApi.retract).not.toHaveBeenCalled()
  })
  it('retracts a specific saved binding with a reason and CAS instead of deleting history', async () => {
    vi.mocked(producerReportApi.binding).mockResolvedValue({
      promoId: 'campaign-demo',
      revision: 8,
      bindings: [
        {
          id: 'binding-demo',
          producerId: 'producer-demo',
          producerName: 'Демо Компания',
          revision: 2,
          wholeHistory: true,
          from: null,
          to: null,
          verifiedAt: '2026-10-29T00:00:00Z',
          retractionRevision: null,
          retractedAt: null,
        },
      ],
    })
    vi.mocked(producerReportApi.retract).mockRejectedValue(new Error('Данные изменились'))
    const user = userEvent.setup()
    render(
      <CampaignProducerBinding
        campaignId="campaign-demo"
        producers={producers}
        onChanged={vi.fn()}
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Отозвать связь' }))
    await user.type(screen.getByLabelText('Основание связи кампании'), 'Исправление основания')
    await user.click(screen.getByRole('checkbox', { name: /Я проверил производителя/ }))
    await user.click(screen.getByRole('button', { name: 'Подтвердить изменение связи' }))
    await waitFor(() =>
      expect(producerReportApi.retract).toHaveBeenCalledExactlyOnceWith('campaign-demo', {
        expectedRevision: 8,
        bindingId: 'binding-demo',
        reference: 'Исправление основания',
      }),
    )
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: 'Отозвать связь' })).toBeDisabled()
    expect(producerReportApi.bind).not.toHaveBeenCalled()
  })
})
