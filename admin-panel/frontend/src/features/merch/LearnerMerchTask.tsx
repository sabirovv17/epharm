import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import MerchTaskDetail from './MerchTaskDetail'
import type { MerchTask, TaskAttempt, TaskEvidence, TaskGateway } from './tasks-api'
import NativeTaskExecutor from './NativeTaskExecutor'
import { taskCapabilityToken } from './taskNavigation'

export default function LearnerMerchTask({
  id,
  request,
  form,
  photo,
}: {
  id: string
  request: <T>(path: string, init?: RequestInit) => Promise<T>
  form: <T>(path: string, body: FormData) => Promise<T>
  photo: (path: string, signal: AbortSignal) => Promise<Blob>
}) {
  const [needsVerification, setNeedsVerification] = useState(false)
  const [verified, setVerified] = useState(0)
  const onVerified = useCallback(() => {
    setNeedsVerification(false)
    setVerified((value) => value + 1)
  }, [])
  const gateway = useMemo<TaskGateway>(() => {
    const base = '/api/mobile/merch/tasks'
    return {
      realm: 'pharmacist',
      comments: (itemId, offset, signal) =>
        request(`${base}/items/${encodeURIComponent(itemId)}/comments?limit=20&offset=${offset}`, {
          signal,
        }),
      comment: (itemId, requestId, body) =>
        request(`${base}/items/${encodeURIComponent(itemId)}/comments`, {
          method: 'POST',
          body: JSON.stringify({ requestId, body }),
        }),
      block: (itemId, expectedItemVersion, reason) =>
        request(`${base}/items/${encodeURIComponent(itemId)}/block`, {
          method: 'POST',
          body: JSON.stringify({ expectedItemVersion, reason }),
        }),
      get: async (taskId, signal) => {
        try {
          return await request<MerchTask>(`${base}/${encodeURIComponent(taskId)}`, { signal })
        } catch (error) {
          if ((error as { code?: string })?.code === 'OTP_NOT_VERIFIED') setNeedsVerification(true)
          throw error
        }
      },
      start: (itemId) =>
        request<TaskAttempt>(`${base}/items/${encodeURIComponent(itemId)}/attempts`, {
          method: 'POST',
        }),
      upload: (itemId, uploadId, file, capture) => {
        const body = new FormData()
        body.append('file', file)
        if (capture)
          body.append('capture', new Blob([JSON.stringify(capture)], { type: 'application/json' }))
        return form<TaskEvidence>(
          `${base}/items/${encodeURIComponent(itemId)}/evidence?clientUploadId=${encodeURIComponent(uploadId)}`,
          body,
        )
      },
      saveStock: (itemId, body) =>
        request(`${base}/items/${encodeURIComponent(itemId)}/stock`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      reference: (itemId, signal) =>
        photo(`${base}/items/${encodeURIComponent(itemId)}/reference`, signal),
      instruction: (itemId, signal) =>
        photo(`${base}/items/${encodeURIComponent(itemId)}/instruction`, signal),
      submit: (itemId, expectedAttempt, completedChecklistIds, comment) =>
        request<TaskAttempt>(`${base}/items/${encodeURIComponent(itemId)}/submit`, {
          method: 'POST',
          body: JSON.stringify({ expectedAttempt, completedChecklistIds, comment }),
        }),
      evidence: (evidenceId, signal) =>
        photo(`${base}/evidence/${encodeURIComponent(evidenceId)}`, signal),
    }
  }, [request, form, photo])
  return (
    <div className="space-y-4">
      <div>
        <Link to="/learn" className="btn btn-md btn-outline">
          В мой кабинет
        </Link>
      </div>
      {needsVerification ? (
        <NativeTaskExecutor
          id={id}
          token={taskCapabilityToken()}
          request={request}
          onVerified={onVerified}
        />
      ) : (
        <MerchTaskDetail key={verified} id={id} gateway={gateway} canExecute canReview={false} />
      )}
    </div>
  )
}
