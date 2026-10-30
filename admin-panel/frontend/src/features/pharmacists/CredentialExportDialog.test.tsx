import { beforeEach, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { credentialExportApi, credentialWorkbookMime } from './credential-export-api'
import { saveReport } from '@/features/reports/ledgerReports'
import CredentialExportDialog from './CredentialExportDialog'

vi.mock('./credential-export-api', () => ({
  credentialExportApi: { download: vi.fn() },
  credentialWorkbookMime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
}))
vi.mock('@/features/reports/ledgerReports', () => ({ saveReport: vi.fn() }))
const people = [{ id: 'synthetic-staff', name: 'Synthetic employee' }]
const password = '  Synthetic admin password  '
beforeEach(() => vi.resetAllMocks())
async function fill() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Цель выгрузки'), 'Контроль доступа')
  await user.type(screen.getByLabelText('Текущий пароль администратора'), password)
  return user
}

it('requires a purpose and password, clears the field on submission, then saves only the completed workbook', async () => {
  let finish!: (value: Blob) => void
  vi.mocked(credentialExportApi.download).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const close = vi.fn()
  render(<CredentialExportDialog people={people} onClose={close} />)
  expect(screen.getByRole('button', { name: 'Подтвердить и скачать' })).toBeDisabled()
  const user = await fill()
  await user.click(screen.getByRole('button', { name: 'Подтвердить и скачать' }))
  expect(screen.getByLabelText('Текущий пароль администратора')).toHaveValue('')
  expect(credentialExportApi.download).toHaveBeenCalledWith(
    ['synthetic-staff'],
    'Контроль доступа',
    password,
    expect.any(AbortSignal),
  )
  const blob = new Blob(['PK-synthetic'], { type: credentialWorkbookMime })
  await act(async () => finish(blob))
  expect(saveReport).toHaveBeenCalledWith(blob, 'pharmacist-credentials.xlsx')
  expect(close).toHaveBeenCalledTimes(1)
})

it('cancels the request and ignores a late workbook after closing', async () => {
  let finish!: (value: Blob) => void
  vi.mocked(credentialExportApi.download).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const close = vi.fn()
  render(<CredentialExportDialog people={people} onClose={close} />)
  const user = await fill()
  await user.click(screen.getByRole('button', { name: 'Подтвердить и скачать' }))
  await user.click(screen.getByRole('button', { name: 'Отмена' }))
  expect(vi.mocked(credentialExportApi.download).mock.calls[0][3].aborted).toBe(true)
  await act(async () => finish(new Blob(['PK-synthetic'], { type: credentialWorkbookMime })))
  expect(saveReport).not.toHaveBeenCalled()
  expect(close).toHaveBeenCalledTimes(1)
})

it('aborts on account/page unmount and requires new confirmation after a failure', async () => {
  vi.mocked(credentialExportApi.download).mockRejectedValue(
    new Error('Проверьте текущий пароль администратора.'),
  )
  const view = render(<CredentialExportDialog people={people} onClose={vi.fn()} />)
  const user = await fill()
  await user.click(screen.getByRole('button', { name: 'Подтвердить и скачать' }))
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent('Проверьте текущий пароль'),
  )
  expect(screen.getByLabelText('Текущий пароль администратора')).toHaveValue('')
  expect(screen.getByRole('button', { name: 'Подтвердить и скачать' })).toBeDisabled()
  view.unmount()
  expect(vi.mocked(credentialExportApi.download).mock.calls[0][3].aborted).toBe(true)
  expect(saveReport).not.toHaveBeenCalled()
})
