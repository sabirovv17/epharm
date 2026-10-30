import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { ToastHost } from '@/ui'
import { api } from '@/lib/api'
import type { CoursePackagePreview } from '@/lib/queries/lms'
import { ImportCourseModal } from './LMSPage'

const server = setupServer()
// Capture browser FormData before MSW's Node/jsdom conversion drops File names and bytes.
// The real query hooks and HTTP client still run; MSW controls only server responses.
const outgoing: { url: string; data: FormData; timeout?: number }[] = []
let captureInterceptor: number
beforeAll(() => {
  captureInterceptor = api.interceptors.request.use((config) => {
    outgoing.push({ url: config.url ?? '', data: config.data as FormData, timeout: config.timeout })
    return config
  })
  server.listen({ onUnhandledRequest: 'error' })
})
beforeEach(() => {
  outgoing.length = 0
})
afterEach(() => server.resetHandlers())
afterAll(() => {
  server.close()
  api.interceptors.request.eject(captureInterceptor)
})

const preview: CoursePackagePreview = {
  digest: 'a'.repeat(64),
  title: 'Учебный пакет',
  lessons: [{ code: 'lesson-1', title: 'Основы', type: 'text', questions: 0 }],
  files: 1,
  expandedBytes: 128,
  createsProgram: true,
  partner: 'Учебный партнёр',
  existingCourseId: null,
}
const course = {
  id: 'course-imported',
  title: preview.title,
  description: '',
  status: 'draft',
  category: '',
  lessons: 1,
  durationMin: 5,
  enrolled: 0,
  completed: 0,
  bonus: 0,
  lessonItems: [],
  createdAt: '2026-10-30T10:00:00Z',
  updatedAt: '2026-10-30T10:00:00Z',
}
const previewUrl = '*/api/admin/lms/courses/import/package/preview'
const importUrl = '*/api/admin/lms/courses/import/package'
const workbook = (name = 'course.xlsx') =>
  new File(['synthetic workbook'], name, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })

function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

function renderImport() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onClose = vi.fn()
  const onImported = vi.fn()
  render(
    <QueryClientProvider client={client}>
      <ToastHost>
        <ImportCourseModal open onClose={onClose} onImported={onImported} />
      </ToastHost>
    </QueryClientProvider>,
  )
  return { onClose, onImported }
}

describe('course package preview and explicit import', () => {
  it('locks selection during preview and creates only after explicit confirmation with its digest', async () => {
    let releasePreview!: () => void
    const pendingPreview = new Promise<void>((resolve) => {
      releasePreview = resolve
    })
    const commits: FormData[] = []
    let previewFilename = ''
    server.use(
      http.post(previewUrl, async () => {
        previewFilename = (outgoing.at(-1)!.data.get('file') as File).name
        await pendingPreview
        return HttpResponse.json(preview)
      }),
      http.post(importUrl, () => {
        commits.push(outgoing.at(-1)!.data)
        return HttpResponse.json({ course, programId: 'program-created', reused: false })
      }),
    )
    const { onClose, onImported } = renderImport()
    const input = screen.getByLabelText('Файл курса')
    expect(screen.getByRole('button', { name: 'Проверить файл' })).toBeDisabled()
    await userEvent.upload(input, workbook())
    await userEvent.click(screen.getByRole('button', { name: 'Проверить файл' }))
    await waitFor(() => expect(previewFilename).toBe('course.xlsx'))
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Проверяем и загружаем…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeDisabled()
    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    expect(commits).toHaveLength(0)
    releasePreview()
    expect(
      await screen.findByRole('region', { name: 'Предварительный просмотр курса' }),
    ).toHaveTextContent('Основы')
    expect(
      screen.getByText(/Ничего не публикуется и не назначается автоматически/),
    ).toBeInTheDocument()
    expect(commits).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: 'Создать черновик' }))
    await waitFor(() => expect(onImported).toHaveBeenCalledWith(course))
    expect(commits).toHaveLength(1)
    expect(
      outgoing
        .filter((request) => request.url.includes('/import/package'))
        .map((request) => request.timeout),
    ).toEqual([120_000, 120_000])
    expect(commits[0].get('digest')).toBe(preview.digest)
    const committedFile = commits[0].get('file') as File
    expect(committedFile.name).toBe('course.xlsx')
    expect(await readFile(committedFile)).toBe('synthetic workbook')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('shows a validation error without creating a draft or exposing a confirm action', async () => {
    const commit = vi.fn(() => HttpResponse.json({ course, programId: null, reused: false }))
    server.use(
      http.post(previewUrl, () =>
        HttpResponse.json({ message: 'Вопрос не содержит правильного ответа' }, { status: 400 }),
      ),
      http.post(importUrl, commit),
    )
    const { onImported } = renderImport()
    await userEvent.upload(screen.getByLabelText('Файл курса'), workbook())
    await userEvent.click(screen.getByRole('button', { name: 'Проверить файл' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Вопрос не содержит правильного ответа',
    )
    expect(
      screen.queryByRole('region', { name: 'Предварительный просмотр курса' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Создать черновик' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Проверить файл' })).toBeEnabled()
    expect(commit).not.toHaveBeenCalled()
    expect(onImported).not.toHaveBeenCalled()
  })

  it('discards the old preview on file change and commits only the new file and digest', async () => {
    const committed: FormData[] = []
    server.use(
      http.post(previewUrl, () => {
        const second = (outgoing.at(-1)!.data.get('file') as File).name === 'second.xlsx'
        return HttpResponse.json({
          ...preview,
          title: second ? 'Второй курс' : preview.title,
          digest: second ? 'b'.repeat(64) : preview.digest,
        })
      }),
      http.post(importUrl, () => {
        committed.push(outgoing.at(-1)!.data)
        return HttpResponse.json({ course, programId: null, reused: false })
      }),
    )
    const { onImported } = renderImport()
    const input = screen.getByLabelText('Файл курса')
    await userEvent.upload(input, workbook())
    await userEvent.click(screen.getByRole('button', { name: 'Проверить файл' }))
    await screen.findByRole('region', { name: 'Предварительный просмотр курса' })
    await userEvent.upload(input, workbook('second.xlsx'))
    expect(
      screen.queryByRole('region', { name: 'Предварительный просмотр курса' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Создать черновик' })).not.toBeInTheDocument()
    expect(committed).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: 'Проверить файл' }))
    expect(await screen.findByRole('heading', { name: 'Второй курс' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Создать черновик' }))
    await waitFor(() => expect(onImported).toHaveBeenCalledOnce())
    expect(committed).toHaveLength(1)
    expect(committed[0].get('digest')).toBe('b'.repeat(64))
    const committedFile = committed[0].get('file') as File
    expect(committedFile.name).toBe('second.xlsx')
    expect(await readFile(committedFile)).toBe('synthetic workbook')
  })

  it('opens the existing course after duplicate preview with explicit reuse feedback', async () => {
    const commit = vi.fn(() => HttpResponse.json({ course, programId: null, reused: true }))
    server.use(
      http.post(previewUrl, () => HttpResponse.json({ ...preview, existingCourseId: course.id })),
      http.post(importUrl, commit),
    )
    const { onImported } = renderImport()
    await userEvent.upload(screen.getByLabelText('Файл курса'), workbook())
    await userEvent.click(screen.getByRole('button', { name: 'Проверить файл' }))
    await screen.findByText('Этот пакет уже загружен. Повторный курс создан не будет.')
    expect(screen.queryByText(/Также будет создана программа/)).not.toBeInTheDocument()
    expect(commit).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Создать черновик' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Открыть существующий курс' }))
    await waitFor(() => expect(onImported).toHaveBeenCalledWith(course))
    expect(commit).toHaveBeenCalledOnce()
    expect(
      screen.getByText('Этот пакет уже импортирован — открыт существующий курс'),
    ).toBeInTheDocument()
  })
})
