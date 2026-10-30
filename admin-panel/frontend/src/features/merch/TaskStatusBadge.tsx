import { taskStatusLabel } from './task-labels'

/** Semantic state is always written out; color is only a secondary cue. */
export default function TaskStatusBadge({ status }: { status: string }) {
  const tone = ['approved', 'completed'].includes(status)
    ? 'bg-accent-success/10 text-recommendation-green-dark'
    : ['overdue', 'rejected', 'blocked'].includes(status)
      ? 'bg-surface-danger text-surface-danger-strong'
      : ['revision_required', 'submitted'].includes(status)
        ? 'bg-surface-warning text-surface-warning-strong'
        : 'bg-paper-input text-ink-600'
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${tone}`}
    >
      <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
      {taskStatusLabel(status)}
    </span>
  )
}
