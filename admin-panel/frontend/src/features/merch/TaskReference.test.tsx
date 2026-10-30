import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
})
import TaskReference from './TaskReference'
import type { TaskGateway, TaskItem } from './tasks-api'
const item = {
  id: 'item-demo',
  title: 'Полка AA',
  execution: { clientReferencePhotoId: 'frozen-ref' },
} as TaskItem
it('does not invent an image for a task without a fixed reference', () => {
  const reference = vi.fn()
  render(
    <TaskReference
      item={{ ...item, execution: { ...item.execution!, clientReferencePhotoId: null } }}
      gateway={{ reference } as unknown as TaskGateway}
    />,
  )
  expect(screen.queryByRole('button', { name: 'Посмотреть эталон' })).not.toBeInTheDocument()
  expect(reference).not.toHaveBeenCalled()
})
it.each(['image/png', 'image/webp'])(
  'requests the item-scoped private %s image and revokes its Blob when the task unmounts',
  async (mime) => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic-reference')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const reference = vi.fn().mockResolvedValue(new Blob(['synthetic-image'], { type: mime }))
    const view = render(
      <TaskReference item={item} gateway={{ reference } as unknown as TaskGateway} />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Посмотреть эталон' }))
    expect(await screen.findByRole('img', { name: 'Эталон для Полка AA' })).toHaveAttribute(
      'src',
      'blob:synthetic-reference',
    )
    expect(reference).toHaveBeenCalledWith('item-demo', expect.any(AbortSignal))
    expect(create).toHaveBeenCalledTimes(1)
    view.unmount()
    expect(revoke).toHaveBeenCalledWith('blob:synthetic-reference')
  },
)
it('rejects an HTML fallback instead of displaying a reference URL', async () => {
  const create = vi.spyOn(URL, 'createObjectURL')
  const reference = vi.fn().mockResolvedValue(new Blob(['<html/>'], { type: 'text/html' }))
  render(<TaskReference item={item} gateway={{ reference } as unknown as TaskGateway} />)
  await userEvent.click(screen.getByRole('button', { name: 'Посмотреть эталон' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Сервис не вернул фотографию эталона')
  expect(create).not.toHaveBeenCalled()
  expect(screen.queryByRole('img')).not.toBeInTheDocument()
})
it('ignores a late private image after leaving the task even if transport ignores abort', async () => {
  let finish!: (value: Blob) => void
  const create = vi.spyOn(URL, 'createObjectURL')
  const reference = vi.fn().mockReturnValue(
    new Promise<Blob>((resolve) => {
      finish = resolve
    }),
  )
  const view = render(
    <TaskReference item={item} gateway={{ reference } as unknown as TaskGateway} />,
  )
  await userEvent.click(screen.getByRole('button', { name: 'Посмотреть эталон' }))
  view.unmount()
  finish(new Blob(['late'], { type: 'image/png' }))
  await waitFor(() => expect(reference.mock.calls[0][1].aborted).toBe(true))
  expect(create).not.toHaveBeenCalled()
})
