import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import PlacementReferencePanel from './PlacementReferencePanel'
import type { ReferencePhotoApi, ReferencePhotoState } from './placement-reference-api'

const state: ReferencePhotoState = {
  placementId: 'owned-plan',
  itemId: 'owned-item',
  revision: 4,
  canManage: true,
  photo: null,
  history: [],
}
function fixture(value = state) {
  return {
    view: vi.fn().mockResolvedValue(value),
    upload: vi.fn().mockRejectedValue(new Error('Версия изменилась')),
    archive: vi.fn(),
    file: vi.fn(),
  } satisfies ReferencePhotoApi
}
beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('private placement reference photo', () => {
  it('uses server capability and keeps approved or unauthorized positions read-only', async () => {
    const api = fixture({ ...state, canManage: false })
    render(
      <PlacementReferencePanel
        api={api}
        planId="owned-plan"
        itemId="owned-item"
        onChanged={vi.fn()}
      />,
    )
    await screen.findByText('Изменение фотографии для этой позиции недоступно.')
    expect(screen.queryByLabelText('Фото заказчика JPG/PNG/WebP')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Загрузить фотографию' })).not.toBeInTheDocument()
    expect(api.upload).not.toHaveBeenCalled()
  })

  it('retains the file and immutable request ID on failed CAS retry without persisting bytes or a private URL', async () => {
    const api = fixture()
    const user = userEvent.setup()
    render(
      <PlacementReferencePanel
        api={api}
        planId="owned-plan"
        itemId="owned-item"
        onChanged={vi.fn()}
      />,
    )
    const input = await screen.findByLabelText('Фото заказчика JPG/PNG/WebP')
    expect(input).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp')
    const file = new File(['synthetic-image-content'], 'synthetic-reference.webp', {
      type: 'image/webp',
    })
    await user.upload(input, file)
    await user.click(screen.getByRole('button', { name: 'Загрузить фотографию' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Версия изменилась')
    await user.click(screen.getByRole('button', { name: 'Загрузить фотографию' }))
    await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(2))
    expect(api.upload.mock.calls[0].slice(0, 5)).toEqual(api.upload.mock.calls[1].slice(0, 5))
    expect(api.upload.mock.calls[0][2]).toBe(4)
    expect(api.upload.mock.calls[0][4]).toBe(file)
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(
      /synthetic-image|synthetic-reference|blob:|placement-reference/,
    )
  })

  it('does not create a private image URL from a download which completes after the panel unmounts', async () => {
    const photo = {
      id: 'photo-id',
      mimeType: 'image/png',
      byteSize: 10,
      fileName: 'synthetic.png',
      createdAt: '2026-10-29T00:00:00Z',
      archivedAt: null,
    }
    const api = fixture({ ...state, photo, history: [photo] })
    let finish!: (value: Blob) => void
    api.file.mockImplementation(
      () =>
        new Promise<Blob>((resolve) => {
          finish = resolve
        }),
    )
    const create = vi.fn()
    vi.stubGlobal(
      'URL',
      Object.assign(class extends URL {}, { createObjectURL: create, revokeObjectURL: vi.fn() }),
    )
    const user = userEvent.setup()
    const view = render(
      <PlacementReferencePanel
        api={api}
        planId="owned-plan"
        itemId="owned-item"
        onChanged={vi.fn()}
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Открыть фотографию' }))
    view.unmount()
    finish(new Blob(['synthetic'], { type: 'image/png' }))
    await Promise.resolve()
    expect(create).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
