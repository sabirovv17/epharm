import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { InstructionDownload, TaskStockFields } from './TaskStockFields'
import type { TaskGateway, TaskItem } from './tasks-api'
function fixture() {
  const item = {
    id: 'item',
    title: 'Synthetic',
    execution: {
      requiresStock: true,
      products: [{ key: '0', name: 'Synthetic product' }],
      stock: null,
      trainingMaterialId: null,
      requiresVideo: false,
      referenceImage: null,
    },
    currentAttempt: { number: 2 },
  } as TaskItem
  const gateway = {
    realm: 'admin',
    saveStock: vi.fn().mockResolvedValue({}),
    instruction: vi.fn(),
  } as unknown as TaskGateway
  return { item, gateway, saved: vi.fn() }
}
describe('task stock evidence', () => {
  it('requires a shortage reason and sends exact current attempt with integer quantities', async () => {
    const { item, gateway, saved } = fixture()
    render(<TaskStockFields item={item} gateway={gateway} editable onSaved={saved} />)
    fireEvent.change(screen.getByLabelText('Упаковок: Synthetic product'), {
      target: { value: '0' },
    })
    const submit = screen.getByRole('button', { name: 'Сохранить количество упаковок' })
    expect(submit).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Причина нехватки упаковок'), {
      target: { value: 'Поставка ожидается' },
    })
    fireEvent.click(submit)
    await waitFor(() => expect(saved).toHaveBeenCalledOnce())
    expect(gateway.saveStock).toHaveBeenCalledWith('item', {
      expectedAttempt: 2,
      counts: { '0': 0 },
      shortageReason: 'Поставка ожидается',
    })
  })
  it('rejects fractional and excessive stock before issuing a mutation', () => {
    const { item, gateway, saved } = fixture()
    render(<TaskStockFields item={item} gateway={gateway} editable onSaved={saved} />)
    const field = screen.getByLabelText('Упаковок: Synthetic product')
    for (const value of ['1.5', '100001', '-1']) {
      fireEvent.change(field, { target: { value } })
      expect(screen.getByRole('button', { name: 'Сохранить количество упаковок' })).toBeDisabled()
    }
    expect(gateway.saveStock).not.toHaveBeenCalled()
  })
  it('keeps preserved source stock read only rather than claiming a new count', () => {
    const { item, gateway, saved } = fixture()
    item.execution!.stock = { counts: { '0': 7 }, shortageReason: '', source: 'preserved_source' }
    render(<TaskStockFields item={item} gateway={gateway} editable={false} onSaved={saved} />)
    expect(screen.getByLabelText('Упаковок: Synthetic product')).toBeDisabled()
    expect(screen.getByText(/Исторические значения/)).toBeVisible()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
  it('does not download a late protected PDF after leaving the task', async () => {
    const { item, gateway } = fixture()
    let resolve!: (b: Blob) => void
    let signal!: AbortSignal
    vi.mocked(gateway.instruction!).mockImplementation((_id, s) => {
      signal = s
      return new Promise((r) => {
        resolve = r
      })
    })
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic')
    const view = render(<InstructionDownload item={item} gateway={gateway} />)
    fireEvent.click(screen.getByRole('button', { name: 'Скачать PDF-инструкцию' }))
    view.unmount()
    expect(signal.aborted).toBe(true)
    resolve(new Blob(['%PDF-1.4'], { type: 'application/pdf' }))
    await Promise.resolve()
    expect(create).not.toHaveBeenCalled()
    create.mockRestore()
  })
})
