import { expect, it, vi } from 'vitest'
import { producerInstructionApi, type InstructionProducerClient } from './task-instructions-api'

it('producer multipart adapter removes body ownership and uses its dedicated authenticated client', async () => {
  const client = {
    request: vi.fn(),
    upload: vi.fn().mockResolvedValue({}),
    downloadPdf: vi.fn(),
    downloadVideo: vi.fn(),
  } as unknown as InstructionProducerClient
  const gateway = producerInstructionApi(client)
  const controller = new AbortController()
  await gateway.upload(
    {
      id: 'opaque-id',
      producerId: 'spoofed-owner',
      planIds: ['selected-plan'],
      title: 'Instruction',
      instructions: 'Record video',
      dueAt: '2099-01-01T00:00:00Z',
    },
    new File(['%PDF-test'], 'test.pdf', { type: 'application/pdf' }),
    controller.signal,
  )
  expect(client.upload).toHaveBeenCalledWith(
    '/api/producer/instructions',
    expect.any(FormData),
    controller.signal,
  )
  const form = vi.mocked(client.upload).mock.calls[0][1]
  const metadata = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = reject
    reader.readAsText(form.get('metadata') as Blob)
  })
  expect(JSON.parse(metadata)).not.toHaveProperty('producerId')
  expect(JSON.parse(metadata).planIds).toEqual(['selected-plan'])
})
it('producer result files keep both opaque resource IDs inside the own-producer realm', async () => {
  const client = {
    request: vi.fn(),
    upload: vi.fn(),
    downloadPdf: vi.fn(),
    downloadVideo: vi.fn().mockResolvedValue(new Blob(['test'])),
  } as unknown as InstructionProducerClient
  const signal = new AbortController().signal
  await producerInstructionApi(client).video('material/a', 'evidence?b', signal)
  expect(client.downloadVideo).toHaveBeenCalledWith(
    '/api/producer/instructions/material%2Fa/results/evidence%3Fb/file',
    signal,
  )
  expect(client.request).not.toHaveBeenCalled()
})
