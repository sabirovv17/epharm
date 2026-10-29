import { useEffect, useRef, useState } from 'react'
import { Button } from '@/ui'
import { describeError } from '@/lib/describeError'
import { downloadReport, saveReport } from './ledgerReports'

export default function ReportDownloadButton({
  allowed,
  path,
  params,
  filename,
  label,
  invalid,
  note,
}: {
  allowed: boolean
  path: string
  params: Record<string, string | undefined>
  filename: string
  label: string
  invalid?: string
  note?: string
}) {
  const key = JSON.stringify([path, params])
  const request = useRef<AbortController | null>(null)
  const [state, setState] = useState<{
    key: string
    pending?: boolean
    failure?: string
    done?: boolean
  } | null>(null)
  const current = state?.key === key ? state : null
  useEffect(() => () => request.current?.abort(), [key])
  const download = async () => {
    if (!allowed || invalid || current?.pending) return
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setState({ key, pending: true })
    try {
      const blob = await downloadReport(path, params, controller.signal)
      if (!controller.signal.aborted) {
        saveReport(blob, filename)
        setState({ key, done: true })
      }
    } catch (error) {
      if (!controller.signal.aborted) setState({ key, failure: describeError(error) })
    }
  }
  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        type="button"
        variant="outline"
        disabled={!allowed || !!invalid || current?.pending}
        title={!allowed ? 'Нет права на эту выгрузку' : invalid}
        onClick={download}
      >
        {current?.pending ? 'Формируем Excel…' : label}
      </Button>
      {note && <p className="max-w-xs text-[11px] text-ink-500">{note}</p>}
      {current?.failure && (
        <p role="alert" className="max-w-md text-[12px] text-accent-danger">
          {current.failure}
        </p>
      )}
      {current?.done && (
        <p role="status" className="text-[12px] text-ink-500">
          Excel передан браузеру для скачивания.
        </p>
      )}
    </div>
  )
}
