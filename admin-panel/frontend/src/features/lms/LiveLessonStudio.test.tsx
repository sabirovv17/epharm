import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastHost } from '@/ui'
import type { CourseLessonDto, CourseLiveRoomDto } from '@/lib/api-types'
import { LiveLessonStudio } from './LiveLessonStudio'

const liveHooks = vi.hoisted(() => ({
  room: vi.fn(),
  postMessage: vi.fn(),
  vote: vi.fn(),
  react: vi.fn(),
  changeStatus: vi.fn(),
  moderate: vi.fn(),
}))

vi.mock('@/lib/queries/lms', () => ({
  useCourseLiveRoom: liveHooks.room,
  usePostCourseLiveMessage: () => ({ mutate: liveHooks.postMessage, isPending: false }),
  useVoteCourseLivePoll: () => ({ mutate: liveHooks.vote, isPending: false }),
  useReactToCourseLive: () => ({ mutate: liveHooks.react, isPending: false }),
  useChangeCourseLiveStatus: () => ({ mutate: liveHooks.changeStatus, isPending: false }),
  useModerateCourseLiveMessage: () => ({ mutate: liveHooks.moderate, isPending: false }),
}))

const lesson: CourseLessonDto = {
  id: 'lesson-live',
  title: 'Безопасная консультация: вопросы и ответы',
  description: 'Практический эфир',
  content: '',
  kind: 'live',
  videoUrl: null,
  externalUrl: 'https://www.youtube.com/watch?v=demo123',
  liveProvider: 'youtube',
  liveStatus: 'scheduled',
  liveStartAt: '2026-10-12T10:00:00Z',
  liveEndAt: null,
  liveChatEnabled: false,
  liveQaEnabled: true,
  liveReactionsEnabled: true,
  livePolls: [],
  required: true,
  minimumWatchPct: null,
  quizQuestions: [],
  quizPassingScore: 80,
  attachments: [],
  durationMin: 45,
  order: 1,
  createdAt: '2026-10-12T08:00:00Z',
  updatedAt: '2026-10-12T08:00:00Z',
}

const room: CourseLiveRoomDto = {
  lessonId: lesson.id,
  title: lesson.title,
  description: lesson.description,
  provider: 'youtube',
  status: 'scheduled',
  startsAt: lesson.liveStartAt,
  endsAt: null,
  streamUrl: lesson.externalUrl,
  chatEnabled: false,
  qaEnabled: true,
  reactionsEnabled: true,
  participantCount: 12,
  messages: [{
    id: 'message-1',
    authorName: 'Василий Грущак',
    authorType: 'pharmacist',
    kind: 'question',
    body: 'Можно ли показать пример?',
    pinned: false,
    mine: false,
    createdAt: '2026-10-12T09:30:00Z',
  }],
  polls: [{
    id: 'poll-1',
    prompt: 'Какой формат полезнее?',
    active: true,
    options: [
      { id: 'option-1', label: 'Разбор кейса', votes: 8, percent: 80 },
      { id: 'option-2', label: 'Лекция', votes: 2, percent: 20 },
    ],
    totalVotes: 10,
    myOptionId: null,
  }],
  reactions: { like: 3 },
  serverTime: '2026-10-12T09:45:00Z',
}

describe('LiveLessonStudio', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    liveHooks.room.mockReturnValue({ data: room, isError: false })
  })

  it('показывает эфир, Q&A, опросы и отправляет интерактивы в правильном формате', async () => {
    const user = userEvent.setup()
    render(
      <ToastHost>
        <LiveLessonStudio courseId="course-1" lesson={lesson} canManage onClose={vi.fn()} />
      </ToastHost>,
    )

    expect(screen.getByTestId('live-lesson-studio')).toHaveTextContent('12 участников чата')
    expect(screen.getByText('Какой формат полезнее?')).toBeInTheDocument()
    expect(screen.getByText('Можно ли показать пример?')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Комментарий' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Начать эфир' }))
    expect(liveHooks.changeStatus).toHaveBeenCalledWith('live', expect.any(Object))

    await user.type(screen.getByPlaceholderText('Задайте вопрос ведущему…'), 'Как оформить возврат?')
    await user.click(screen.getByRole('button', { name: 'Отправить сообщение' }))
    expect(liveHooks.postMessage).toHaveBeenCalledWith(
      { kind: 'question', body: 'Как оформить возврат?' },
      expect.any(Object),
    )

    await user.click(screen.getByRole('button', { name: /Разбор кейса/ }))
    expect(liveHooks.vote).toHaveBeenCalledWith({ pollId: 'poll-1', optionId: 'option-1' })

    await user.click(screen.getByTitle('Нравится'))
    expect(liveHooks.react).toHaveBeenCalledWith('like')

    await user.click(screen.getByRole('button', { name: 'Закрепить сообщение' }))
    expect(liveHooks.moderate).toHaveBeenCalledWith({ messageId: 'message-1', pinned: true })
  })

  it('не вставляет небезопасный адрес эфира в iframe или ссылку', () => {
    liveHooks.room.mockReturnValue({
      data: { ...room, status: 'live', streamUrl: 'javascript:alert(1)' },
      isError: false,
    })
    render(
      <ToastHost>
        <LiveLessonStudio courseId="course-1" lesson={lesson} canManage onClose={vi.fn()} />
      </ToastHost>,
    )

    expect(screen.queryByTitle(lesson.title)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Открыть эфир/ })).not.toBeInTheDocument()
  })

  it('открывает custom HTTPS эфир отдельной ссылкой, не встраивая origin админки', () => {
    liveHooks.room.mockReturnValue({
      data: { ...room, provider: 'custom', status: 'live', streamUrl: 'https://epharm.inkar.kz/live/demo' },
      isError: false,
    })
    render(
      <ToastHost>
        <LiveLessonStudio courseId="course-1" lesson={lesson} canManage onClose={vi.fn()} />
      </ToastHost>,
    )

    expect(screen.queryByTitle(lesson.title)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Открыть эфир/ })).toHaveAttribute('href', 'https://epharm.inkar.kz/live/demo')
  })

  it('встраивает эфир только с ожидаемого хоста выбранного провайдера', () => {
    liveHooks.room.mockReturnValue({ data: { ...room, status: 'live' }, isError: false })
    const view = render(
      <ToastHost>
        <LiveLessonStudio courseId="course-1" lesson={lesson} canManage onClose={vi.fn()} />
      </ToastHost>,
    )
    expect(screen.getByTitle(lesson.title)).toHaveAttribute('src', 'https://www.youtube.com/embed/demo123?autoplay=1&rel=0')

    liveHooks.room.mockReturnValue({
      data: { ...room, status: 'live', streamUrl: 'https://evil.youtube.com/watch?v=demo123' },
      isError: false,
    })
    view.rerender(
      <ToastHost>
        <LiveLessonStudio courseId="course-1" lesson={lesson} canManage onClose={vi.fn()} />
      </ToastHost>,
    )
    expect(screen.queryByTitle(lesson.title)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Открыть эфир/ })).toHaveAttribute('href', 'https://evil.youtube.com/watch?v=demo123')
  })
})
