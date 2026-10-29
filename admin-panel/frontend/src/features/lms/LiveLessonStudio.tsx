import { useMemo, useState } from 'react'
import { trainingAllowed, type TrainingActions } from './training-actions'
import {
  CircleHelp,
  ExternalLink,
  MessageCircle,
  MoreHorizontal,
  Pin,
  Play,
  Radio,
  Send,
  Sparkles,
  Square,
  UsersRound,
} from 'lucide-react'
import type { CourseLessonDto, CourseLiveProvider } from '@/lib/api-types'
import {
  useChangeCourseLiveStatus,
  useCourseLiveRoom,
  useModerateCourseLiveMessage,
  usePostCourseLiveMessage,
  useReactToCourseLive,
  useVoteCourseLivePoll,
} from '@/lib/queries/lms'
import { Button, IconButton, Modal, useToast } from '@/ui'
import { describeError } from '@/lib/describeError'

interface LiveLessonStudioProps {
  courseId: string
  lesson: CourseLessonDto
  canManage: boolean
  actions?: TrainingActions
  onClose: () => void
}

const reactionOptions = [
  { id: 'like', label: 'Нравится', symbol: '👍' },
  { id: 'heart', label: 'Супер', symbol: '❤️' },
  { id: 'clap', label: 'Аплодисменты', symbol: '👏' },
  { id: 'wow', label: 'Интересно', symbol: '🤩' },
  { id: 'question', label: 'Есть вопрос', symbol: '❓' },
]

export function LiveLessonStudio({
  courseId,
  lesson,
  canManage,
  actions = null,
  onClose,
}: LiveLessonStudioProps) {
  const canPublish = trainingAllowed(actions, 'live.publish', canManage)
  const canModerate = trainingAllowed(actions, 'live.moderate', canManage)
  const canParticipate = trainingAllowed(actions, 'live.participate', true)
  const toast = useToast()
  const roomQuery = useCourseLiveRoom(courseId, lesson.id)
  const postMessage = usePostCourseLiveMessage(courseId, lesson.id)
  const vote = useVoteCourseLivePoll(courseId, lesson.id)
  const react = useReactToCourseLive(courseId, lesson.id)
  const changeStatus = useChangeCourseLiveStatus(courseId, lesson.id)
  const moderate = useModerateCourseLiveMessage(courseId, lesson.id)
  const [message, setMessage] = useState('')
  const [messageKind, setMessageKind] = useState<'comment' | 'question'>('comment')
  const room = roomQuery.data
  const effectiveMessageKind =
    room && !room.chatEnabled && room.qaEnabled ? 'question' : messageKind
  const streamUrl = safeLiveUrl(room?.streamUrl ?? null)
  const embedUrl = useMemo(() => toEmbedUrl(streamUrl, room?.provider), [streamUrl, room?.provider])

  const submitMessage = () => {
    if (!canParticipate) return
    const body = message.trim()
    if (!body) return
    postMessage.mutate(
      { kind: effectiveMessageKind, body },
      {
        onSuccess: () => setMessage(''),
        onError: (error) => toast.push(describeError(error)),
      },
    )
  }

  const setStatus = (status: 'scheduled' | 'live' | 'ended') => {
    changeStatus.mutate(status, {
      onSuccess: () =>
        toast.push(
          status === 'live'
            ? 'Эфир запущен'
            : status === 'ended'
              ? 'Эфир завершён'
              : 'Эфир возвращён в расписание',
        ),
      onError: (error) => toast.push(describeError(error)),
    })
  }

  return (
    <Modal
      open
      onClose={onClose}
      width={1180}
      title="Студия прямого эфира"
      subtitle={lesson.title}
      footer={
        <Button variant="ghost" onClick={onClose}>
          Закрыть студию
        </Button>
      }
    >
      {!room ? (
        <div className="grid min-h-[420px] place-items-center rounded-2xl bg-[#f3f8f7] text-[13px] text-ink-500">
          {roomQuery.isError ? 'Не удалось открыть студию эфира' : 'Подключаем студию…'}
        </div>
      ) : (
        <div
          className="overflow-hidden rounded-[22px] border border-[#d8e8e6] bg-[#eef5f4]"
          data-testid="live-lesson-studio"
        >
          <header className="flex flex-wrap items-center justify-between gap-3 border-b border-white/10 bg-[#063f43] px-5 py-4 text-white">
            <div className="flex min-w-0 items-center gap-3">
              <span
                className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${room.status === 'live' ? 'bg-red-500 text-white shadow-[0_0_0_6px_rgba(239,68,68,.14)]' : 'bg-white/10 text-[#8ce0d8]'}`}
              >
                <Radio size={20} />
              </span>
              <div className="min-w-0">
                <div className="flex items-center gap-2 text-[10px] font-extrabold uppercase tracking-[0.13em] text-white/55">
                  {room.status === 'live' && (
                    <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-red-400" />
                  )}
                  {room.status === 'live'
                    ? 'Сейчас в эфире'
                    : room.status === 'ended'
                      ? 'Эфир завершён'
                      : 'Запланированный эфир'}
                </div>
                <div className="mt-0.5 truncate text-[15px] font-extrabold">{room.title}</div>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-xl bg-black/15 px-3 py-2 text-[11px] font-bold text-white/75">
                <UsersRound size={14} /> {room.participantCount} участников чата
              </span>
              {canPublish && room.status !== 'live' && (
                <Button
                  size="sm"
                  leading={<Play size={14} />}
                  disabled={changeStatus.isPending}
                  onClick={() => setStatus('live')}
                >
                  Начать эфир
                </Button>
              )}
              {canPublish && room.status === 'live' && (
                <Button
                  size="sm"
                  variant="outline"
                  leading={<Square size={13} />}
                  disabled={changeStatus.isPending}
                  onClick={() => setStatus('ended')}
                >
                  Завершить
                </Button>
              )}
            </div>
          </header>

          <div className="grid min-h-[560px] xl:grid-cols-[minmax(0,1.65fr)_390px]">
            <main className="min-w-0 border-b border-[#d8e8e6] bg-[#f5f9f8] xl:border-b-0 xl:border-r">
              <div className="relative aspect-video overflow-hidden bg-[#041f22]">
                {room.status === 'scheduled' ? (
                  <LivePlaceholder
                    icon={<Radio size={31} />}
                    eyebrow="Эфир скоро начнётся"
                    title={formatSchedule(room.startsAt)}
                    body="Участники увидят трансляцию здесь автоматически после запуска."
                  />
                ) : room.status === 'ended' && !streamUrl ? (
                  <LivePlaceholder
                    icon={<Square size={26} />}
                    eyebrow="Эфир завершён"
                    title="Запись не добавлена"
                    body="Добавьте ссылку на запись в настройках урока."
                  />
                ) : embedUrl ? (
                  <iframe
                    title={room.title}
                    src={embedUrl}
                    sandbox="allow-scripts allow-same-origin allow-presentation allow-popups"
                    referrerPolicy="strict-origin-when-cross-origin"
                    className="h-full w-full border-0"
                    allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                    allowFullScreen
                  />
                ) : streamUrl ? (
                  <LivePlaceholder
                    icon={<ExternalLink size={27} />}
                    eyebrow="Внешняя трансляция"
                    title="Открывается на площадке провайдера"
                    body="Teams и Zoom могут запрещать встраивание в браузер."
                    action={
                      <a
                        className="btn btn-primary mt-4 inline-flex"
                        href={streamUrl}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Открыть эфир <ExternalLink size={14} />
                      </a>
                    }
                  />
                ) : (
                  <LivePlaceholder
                    icon={<Radio size={31} />}
                    eyebrow="Студия готова"
                    title="Добавьте ссылку на трансляцию"
                    body="Поддерживаются YouTube Live, Vimeo, Teams, Zoom и другие HTTPS-ссылки."
                  />
                )}
              </div>

              <div className="p-4 sm:p-5">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="min-w-0">
                    <h3 className="text-[17px] font-black text-ink-900">{room.title}</h3>
                    {room.description && (
                      <p className="mt-1 max-w-3xl text-[12px] leading-5 text-ink-500">
                        {room.description}
                      </p>
                    )}
                    <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] font-semibold text-ink-500">
                      <span>{providerLabel(room.provider)}</span>
                      <span>{formatSchedule(room.startsAt)}</span>
                    </div>
                  </div>
                  {room.reactionsEnabled && canParticipate && (
                    <div className="flex items-center gap-1 rounded-2xl border border-[#dce9e7] bg-white p-1.5 shadow-sm">
                      {reactionOptions.map((item) => (
                        <button
                          key={item.id}
                          type="button"
                          title={item.label}
                          className="relative grid h-9 min-w-9 place-items-center rounded-xl text-[17px] transition hover:-translate-y-0.5 hover:bg-[#e8f6f4]"
                          onClick={() => react.mutate(item.id)}
                        >
                          {item.symbol}
                          {(room.reactions[item.id] ?? 0) > 0 && (
                            <span className="absolute -right-1 -top-1 rounded-full bg-[#087b77] px-1 text-[8px] font-black text-white">
                              {room.reactions[item.id]}
                            </span>
                          )}
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                {room.polls.length > 0 && (
                  <section className="mt-5 rounded-2xl border border-[#d5e8e5] bg-white p-4">
                    <div className="mb-3 flex items-center gap-2 text-[12px] font-extrabold uppercase tracking-[0.08em] text-[#087b77]">
                      <Sparkles size={15} /> Интерактивы эфира
                    </div>
                    <div className="grid gap-3 lg:grid-cols-2">
                      {room.polls.map((poll) => (
                        <article key={poll.id} className="rounded-xl bg-[#f4f9f8] p-3.5">
                          <div className="flex items-start justify-between gap-3">
                            <div className="text-[13px] font-extrabold leading-5 text-ink-900">
                              {poll.prompt}
                            </div>
                            <span
                              className={`rounded-full px-2 py-1 text-[9px] font-extrabold uppercase ${poll.active ? 'bg-[#d9f3ef] text-[#087b77]' : 'bg-ink-100 text-ink-500'}`}
                            >
                              {poll.active ? 'Открыт' : 'Закрыт'}
                            </span>
                          </div>
                          <div className="mt-3 space-y-2">
                            {poll.options.map((option) => (
                              <button
                                key={option.id}
                                type="button"
                                disabled={!canParticipate || !poll.active || vote.isPending}
                                className={`relative flex w-full items-center justify-between overflow-hidden rounded-lg border px-3 py-2 text-left text-[11px] font-bold transition ${poll.myOptionId === option.id ? 'border-[#31b5ac] text-[#076a67]' : 'border-[#dce8e6] bg-white text-ink-700 hover:border-[#8ecfc9]'}`}
                                onClick={() =>
                                  vote.mutate({ pollId: poll.id, optionId: option.id })
                                }
                              >
                                <span
                                  className="absolute inset-y-0 left-0 bg-[#dff4f1]"
                                  style={{ width: `${option.percent}%` }}
                                />
                                <span className="relative">{option.label}</span>
                                <span className="relative num">{option.percent}%</span>
                              </button>
                            ))}
                          </div>
                          <div className="mt-2 text-[10px] text-ink-400">
                            Ответили: {poll.totalVotes}
                          </div>
                        </article>
                      ))}
                    </div>
                  </section>
                )}
              </div>
            </main>

            <aside className="flex min-h-[520px] flex-col bg-white">
              <div className="flex items-center justify-between border-b border-ink-100 px-4 py-3.5">
                <div>
                  <div className="text-[13px] font-extrabold text-ink-900">Обсуждение</div>
                  <div className="mt-0.5 text-[10px] text-ink-400">
                    Чат и вопросы обновляются автоматически
                  </div>
                </div>
                <MessageCircle size={18} className="text-[#15958f]" />
              </div>
              <div className="flex-1 space-y-2.5 overflow-y-auto p-3.5">
                {room.messages.map((entry) => (
                  <article
                    key={entry.id}
                    className={`group rounded-xl border p-3 ${entry.pinned ? 'border-[#f0bf72] bg-[#fffaf0]' : entry.mine ? 'border-[#bfe3df] bg-[#f2fbf9]' : 'border-ink-100 bg-white'}`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className="truncate text-[11px] font-extrabold text-ink-800">
                            {entry.authorName}
                          </span>
                          {entry.authorType === 'admin' && (
                            <span className="rounded bg-[#dff4f1] px-1.5 py-0.5 text-[8px] font-extrabold uppercase text-[#087b77]">
                              Ведущий
                            </span>
                          )}
                          {entry.kind === 'question' && (
                            <span className="inline-flex items-center gap-1 rounded bg-[#fff0dc] px-1.5 py-0.5 text-[8px] font-extrabold uppercase text-[#a86617]">
                              <CircleHelp size={9} /> Вопрос
                            </span>
                          )}
                          {entry.pinned && <Pin size={11} className="text-[#bd7a18]" />}
                        </div>
                        <p className="mt-1.5 whitespace-pre-wrap text-[11px] leading-5 text-ink-700">
                          {entry.body}
                        </p>
                      </div>
                      {canModerate && (
                        <div className="flex shrink-0 opacity-0 transition group-hover:opacity-100">
                          <IconButton
                            tip={entry.pinned ? 'Открепить' : 'Закрепить'}
                            aria-label={
                              entry.pinned ? 'Открепить сообщение' : 'Закрепить сообщение'
                            }
                            onClick={() =>
                              moderate.mutate({ messageId: entry.id, pinned: !entry.pinned })
                            }
                          >
                            <Pin size={13} />
                          </IconButton>
                          <IconButton
                            tip="Скрыть"
                            aria-label="Скрыть сообщение"
                            onClick={() => moderate.mutate({ messageId: entry.id, hidden: true })}
                          >
                            <MoreHorizontal size={14} />
                          </IconButton>
                        </div>
                      )}
                    </div>
                    <div className="mt-1.5 text-[9px] text-ink-400">
                      {new Date(entry.createdAt).toLocaleTimeString('ru-RU', {
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </div>
                  </article>
                ))}
                {room.messages.length === 0 && (
                  <div className="grid min-h-52 place-items-center rounded-2xl border border-dashed border-ink-200 p-5 text-center">
                    <div>
                      <MessageCircle className="mx-auto text-ink-300" size={25} />
                      <div className="mt-2 text-[12px] font-bold text-ink-600">
                        Обсуждение ещё не началось
                      </div>
                      <div className="mt-1 text-[10px] leading-4 text-ink-400">
                        Первое сообщение появится здесь.
                      </div>
                    </div>
                  </div>
                )}
              </div>
              {canParticipate && (room.chatEnabled || room.qaEnabled) && (
                <div className="border-t border-ink-100 p-3.5">
                  {room.chatEnabled && room.qaEnabled && (
                    <div className="mb-2 flex gap-1 rounded-lg bg-[#f0f5f4] p-1">
                      <button
                        type="button"
                        className={`flex-1 rounded-md px-2 py-1.5 text-[10px] font-extrabold ${messageKind === 'comment' ? 'bg-white text-[#087b77] shadow-sm' : 'text-ink-500'}`}
                        onClick={() => setMessageKind('comment')}
                      >
                        Комментарий
                      </button>
                      <button
                        type="button"
                        className={`flex-1 rounded-md px-2 py-1.5 text-[10px] font-extrabold ${messageKind === 'question' ? 'bg-white text-[#a86617] shadow-sm' : 'text-ink-500'}`}
                        onClick={() => setMessageKind('question')}
                      >
                        Вопрос ведущему
                      </button>
                    </div>
                  )}
                  <div className="flex items-end gap-2 rounded-xl border border-ink-200 bg-white p-2 focus-within:border-[#60bdb6]">
                    <textarea
                      className="min-h-16 flex-1 resize-none border-0 bg-transparent px-1 text-[12px] leading-5 outline-none"
                      maxLength={2000}
                      value={message}
                      placeholder={
                        effectiveMessageKind === 'question'
                          ? 'Задайте вопрос ведущему…'
                          : 'Напишите сообщение…'
                      }
                      onChange={(event) => setMessage(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' && !event.shiftKey) {
                          event.preventDefault()
                          submitMessage()
                        }
                      }}
                    />
                    <button
                      type="button"
                      aria-label="Отправить сообщение"
                      disabled={!message.trim() || postMessage.isPending}
                      className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-[#0d918a] text-white disabled:opacity-40"
                      onClick={submitMessage}
                    >
                      <Send size={15} />
                    </button>
                  </div>
                </div>
              )}
            </aside>
          </div>
        </div>
      )}
    </Modal>
  )
}

function LivePlaceholder({
  icon,
  eyebrow,
  title,
  body,
  action,
}: {
  icon: React.ReactNode
  eyebrow: string
  title: string
  body: string
  action?: React.ReactNode
}) {
  return (
    <div className="absolute inset-0 grid place-items-center bg-[radial-gradient(circle_at_50%_20%,#0c5559_0,#041f22_60%)] px-6 text-center text-white">
      <div className="max-w-lg">
        <span className="mx-auto grid h-16 w-16 place-items-center rounded-2xl border border-white/10 bg-white/10 text-[#8ce0d8] shadow-[0_15px_45px_rgba(0,0,0,.22)]">
          {icon}
        </span>
        <div className="mt-5 text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#8edbd5]">
          {eyebrow}
        </div>
        <div className="mt-2 text-[22px] font-black tracking-[-0.025em]">{title}</div>
        <p className="mx-auto mt-2 max-w-md text-[12px] leading-5 text-white/55">{body}</p>
        {action}
      </div>
    </div>
  )
}

function safeLiveUrl(raw: string | null): string | null {
  if (!raw) return null
  try {
    const url = new URL(raw)
    return url.protocol === 'https:' ? url.toString() : null
  } catch {
    return null
  }
}

function toEmbedUrl(raw: string | null, provider?: CourseLiveProvider): string | null {
  if (!raw || !provider) return null
  try {
    const url = new URL(raw)
    const host = url.hostname.toLowerCase()
    if (
      provider === 'youtube' &&
      ['youtube.com', 'www.youtube.com', 'm.youtube.com'].includes(host)
    ) {
      const id = url.searchParams.get('v') ?? url.pathname.split('/').filter(Boolean).at(-1)
      return id ? `https://www.youtube.com/embed/${encodeURIComponent(id)}?autoplay=1&rel=0` : null
    }
    if (provider === 'youtube' && host === 'youtu.be') {
      const id = url.pathname.split('/').filter(Boolean)[0]
      return id ? `https://www.youtube.com/embed/${encodeURIComponent(id)}?autoplay=1&rel=0` : null
    }
    if (provider === 'vimeo' && ['vimeo.com', 'www.vimeo.com', 'player.vimeo.com'].includes(host)) {
      const id = url.pathname.split('/').filter(Boolean).at(-1)
      return id ? `https://player.vimeo.com/video/${encodeURIComponent(id)}?autoplay=1` : null
    }
    return null
  } catch {
    return null
  }
}

function formatSchedule(value: string | null) {
  if (!value) return 'Время уточняется'
  return new Date(value).toLocaleString('ru-RU', { dateStyle: 'long', timeStyle: 'short' })
}

function providerLabel(provider: string) {
  return (
    (
      {
        youtube: 'YouTube Live',
        vimeo: 'Vimeo Live',
        teams: 'Microsoft Teams',
        zoom: 'Zoom',
        custom: 'Внешняя трансляция',
      } as Record<string, string>
    )[provider] ?? provider
  )
}
