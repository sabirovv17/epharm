import { describe, expect, it, vi } from 'vitest'
import { commercialGateway, planMonths, type CommercialPlanData } from './producer-commercial-api'
import { ProducerClient, type ProducerSession } from './producer-client'

const plan: CommercialPlanData = {
  title: 'Синтетический план',
  startMonth: '2027-01-01',
  endMonth: '2027-02-01',
  budgetLimit: 100000,
  status: 'draft',
  notes: null,
  planningMode: 'products',
  productBriefs: [],
  shelfSelection: [],
  extraActivities: [],
  items: [],
}
const session: ProducerSession = {
  accessToken: 'synthetic-producer-access',
  refreshToken: 'synthetic-producer-refresh',
  tokenType: 'Bearer',
  expiresIn: 600,
  sessionExpiresAt: new Date(Date.now() + 600000).toISOString(),
  customer: { id: 'producer-a', name: 'Synthetic company', phoneMasked: '+7 (***) ***-**-00' },
}
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })

describe('commercial planning gateway', () => {
  it('takes external ownership only from the producer realm, including CSV and plan retry payloads', async () => {
    const request = vi.fn().mockResolvedValue({})
    const gateway = commercialGateway('producer', request, vi.fn())
    await gateway.save('original-plan', 'forged-producer', 4, plan)
    await gateway.preview('branch_code;shelf_code;month\nSYNTH;S1;2027-01', 'forged-producer')
    await gateway.submit('original-plan', 5)
    expect(request).toHaveBeenNthCalledWith(1, '/api/producer/commercial/plans/original-plan', {
      method: 'PUT',
      body: JSON.stringify({ expectedRevision: 4, plan }),
    })
    expect(request).toHaveBeenNthCalledWith(
      2,
      '/api/producer/commercial/plans/import-preview',
      expect.objectContaining({
        body: JSON.stringify({ csv: 'branch_code;shelf_code;month\nSYNTH;S1;2027-01' }),
      }),
    )
    expect(request).toHaveBeenNthCalledWith(
      3,
      '/api/producer/commercial/plans/original-plan/submit',
      expect.objectContaining({ body: JSON.stringify({ expectedRevision: 5 }) }),
    )
    expect(JSON.stringify(request.mock.calls)).not.toContain('forged-producer')
  })

  it('keeps an internal customer selector explicit and sends CAS without adding a real-payment action', async () => {
    const request = vi.fn().mockResolvedValue({})
    const gateway = commercialGateway('admin', request, vi.fn())
    await gateway.save('plan-id', 'customer-id', 2, plan)
    await gateway.copy('plan-id', 3, 'new-copy-id', '2027-03-01')
    expect(request).toHaveBeenNthCalledWith(
      1,
      '/api/admin/merch/commercial/plans/plan-id?producerId=customer-id',
      expect.objectContaining({ method: 'PUT' }),
    )
    expect(request).toHaveBeenNthCalledWith(
      2,
      '/api/admin/merch/commercial/plans/plan-id/copy',
      expect.objectContaining({
        body: JSON.stringify({
          expectedRevision: 3,
          newId: 'new-copy-id',
          startMonth: '2027-03-01',
        }),
      }),
    )
    expect(JSON.stringify(request.mock.calls)).not.toMatch(/pay|charge|reward|wallet/)
  })

  it('keeps all twelve months and rejects invalid or longer periods instead of truncating them', () => {
    expect(planMonths('2027-01-01', '2027-12-01')).toHaveLength(12)
    expect(planMonths('2027-01-01', '2027-01-01')).toEqual(['2027-01-01'])
    for (const [start, end] of [
      ['2027-01-01', '2028-01-01'],
      ['2027-13-01', '2028-01-01'],
      ['2027-01-01', '2026-12-01'],
      ['0001-01-01', '0100-01-01'],
    ])
      expect(planMonths(start, end)).toEqual([])
  })
})

describe('producer XLSX session boundary', () => {
  function storage() {
    const values = new Map([['epharm.producer.session.v1', JSON.stringify(session)]])
    return {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => {
        values.set(key, value)
      },
      removeItem: (key: string) => {
        values.delete(key)
      },
    }
  }

  it('uses only its producer bearer, no cookies, and accepts only an actual Excel response', async () => {
    const transport = vi
      .fn()
      .mockResolvedValue(
        new Response(new Blob(['synthetic workbook']), {
          headers: {
            'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          },
        }),
      )
    const client = new ProducerClient(transport, storage(), '')
    const controller = new AbortController()
    await expect(
      client.download('/api/producer/commercial/plans/id/media-kit.xlsx', controller.signal),
    ).resolves.toBeInstanceOf(Blob)
    const init = transport.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).get('Authorization')).toBe(`Bearer ${session.accessToken}`)
    expect(init).toMatchObject({
      credentials: 'omit',
      cache: 'no-store',
      signal: controller.signal,
    })
    transport.mockResolvedValue(json({ not: 'an excel file' }))
    await expect(
      client.download('/api/producer/commercial/plans/id/media-kit.xlsx', controller.signal),
    ).rejects.toThrow('Сервер не вернул Excel')
  })

  it('discards a late workbook after logout and cannot request an administrator download', async () => {
    let complete!: (value: Response) => void
    const deferred = new Promise<Response>((resolve) => {
      complete = resolve
    })
    const transport = vi
      .fn()
      .mockImplementation((path: string) =>
        path.endsWith('/logout') ? Promise.resolve(new Response(null, { status: 204 })) : deferred,
      )
    const client = new ProducerClient(transport, storage(), '')
    const download = client.download(
      '/api/producer/commercial/plans/id/media-kit.xlsx',
      new AbortController().signal,
    )
    const denied = expect(download).rejects.toThrow('Producer session changed')
    await client.logout()
    complete(
      new Response(new Blob(['synthetic late bytes']), {
        headers: {
          'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        },
      }),
    )
    await denied
    const other = new ProducerClient(transport, storage(), '')
    await expect(
      other.download(
        '/api/admin/merch/commercial/plans/id/media-kit.xlsx',
        new AbortController().signal,
      ),
    ).rejects.toThrow('Invalid producer API path')
  })

  it('keeps multipart boundaries browser-owned and validates PDF MIME and actual magic bytes in the same producer realm', async () => {
    const transport = vi.fn().mockResolvedValue(json({ id: 'synthetic-instruction' }))
    const client = new ProducerClient(transport, storage(), '')
    const form = new FormData()
    form.set(
      'metadata',
      JSON.stringify({ title: 'Synthetic instruction', planIds: ['owned-plan'] }),
    )
    form.set('file', new Blob(['%PDF-1.7 synthetic'], { type: 'application/pdf' }), 'synthetic.pdf')
    await client.upload('/api/producer/instructions', form)
    const init = transport.mock.calls[0][1] as RequestInit
    expect(init.body).toBe(form)
    expect(new Headers(init.headers).has('Content-Type')).toBe(false)
    expect(new Headers(init.headers).get('Authorization')).toBe(`Bearer ${session.accessToken}`)
    expect(init.credentials).toBe('omit')
    transport.mockResolvedValue(
      new Response('%PDF-1.7 synthetic', { headers: { 'content-type': 'application/pdf' } }),
    )
    await expect(
      client.downloadPdf('/api/producer/instructions/id/pdf', new AbortController().signal),
    ).resolves.toBeInstanceOf(Blob)
    transport.mockResolvedValue(
      new Response('<html>not PDF</html>', { headers: { 'content-type': 'application/pdf' } }),
    )
    await expect(
      client.downloadPdf('/api/producer/instructions/id/pdf', new AbortController().signal),
    ).rejects.toThrow('действительный PDF')
    transport.mockResolvedValue(json({ not: 'pdf' }))
    await expect(
      client.downloadPdf('/api/producer/instructions/id/pdf', new AbortController().signal),
    ).rejects.toThrow('Сервер не вернул PDF')
  })
})
