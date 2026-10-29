import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useUiStore } from '@/app/store'
import type { UserDto } from '@/lib/api-types'
import {
  clearCommercialDrafts,
  draftBinding,
  draftJSON,
  importDraftJSON,
  recoverCommercialDraft,
  storeCommercialDraft,
} from './commercial-draft'
import type {
  CommercialGateway,
  CommercialPlan,
  CommercialPlanData,
} from './producer-commercial-api'

const plan: CommercialPlanData = {
  title: 'Synthetic local draft',
  startMonth: '2027-01-01',
  endMonth: '2027-01-01',
  budgetLimit: '9007199254740993',
  status: 'draft',
  notes: '',
  planningMode: 'shelves',
  productBriefs: [],
  shelfSelection: [],
  extraActivities: [],
  items: [],
}
const original: CommercialPlan = {
  id: 'saved-plan',
  producerId: 'producer-a',
  revision: 3,
  plan,
  requestIds: [],
  monthlyRequests: [],
  copiedFrom: null,
  estimatedTotal: null,
  estimateState: 'hidden',
  canEdit: true,
  canSubmit: false,
  canExport: false,
  updatedAt: '',
  paymentStatus: 'not_connected',
}
const user = (id: string) => ({ id, name: 'Synthetic operator', role: 'HQ_HEAD' }) as UserDto
const gateway = (value = original) =>
  ({ realm: 'admin', view: vi.fn().mockResolvedValue(value) }) as unknown as CommercialGateway
const signal = () => new AbortController().signal
beforeEach(() => {
  sessionStorage.clear()
  useUiStore.setState({ authedUser: user('account-a'), querySessionEpoch: 1 })
})
afterEach(() => {
  useUiStore.setState({ authedUser: null, querySessionEpoch: 0 })
  clearCommercialDrafts('admin')
  vi.restoreAllMocks()
})

describe('session bound commercial draft recovery', () => {
  it('preserves exact money and only plan fields after a fresh owner capability and CAS check', async () => {
    const binding = draftBinding('admin', 'producer-a')!
    const api = gateway()
    const dirty = { ...plan, title: 'Unsaved synthetic change' }
    await storeCommercialDraft(binding, original, dirty, api, signal())
    expect(
      await recoverCommercialDraft(binding, original.id, original.revision, api, signal()),
    ).toEqual(dirty)
    expect(api.view).toHaveBeenCalledTimes(2)
    const contents = Object.entries(sessionStorage)
      .filter(([k]) => k.includes('saved-plan'))
      .map(([, v]) => v)
      .join('')
    expect(contents).toContain('9007199254740993')
    expect(contents).not.toMatch(
      /canEdit|Authorization|accessToken|refreshToken|phone|source_payload/,
    )
    await expect(
      storeCommercialDraft(binding, original, dirty, { ...api, realm: 'producer' }, signal()),
    ).rejects.toThrow('Кабинет изменился')
  })

  it.each(['revision', 'permission', 'owner'] as const)(
    'refuses and removes recovery after a fresh %s change',
    async (reason) => {
      const binding = draftBinding('admin', 'producer-a')!
      const api = gateway()
      await storeCommercialDraft(binding, original, plan, api, signal())
      vi.mocked(api.view).mockResolvedValue(
        reason === 'revision'
          ? { ...original, revision: 4 }
          : reason === 'permission'
            ? { ...original, canEdit: false }
            : { ...original, producerId: 'producer-b' },
      )
      await expect(
        recoverCommercialDraft(binding, original.id, original.revision, api, signal()),
      ).rejects.toThrow('не восстановлен')
      expect(Object.keys(sessionStorage).some((k) => k.includes('saved-plan'))).toBe(false)
    },
  )

  it('clears all native snapshots on account switch or logout and rejects a delayed old-session save', async () => {
    const binding = draftBinding('admin', 'producer-a')!
    const api = gateway()
    await storeCommercialDraft(binding, original, plan, api, signal())
    useUiStore.setState({ authedUser: user('account-b'), querySessionEpoch: 2 })
    expect(
      Object.keys(sessionStorage).some((k) => k.startsWith('epharm.commercial.draft.v1.admin.')),
    ).toBe(false)
    await expect(
      recoverCommercialDraft(binding, original.id, original.revision, api, signal()),
    ).rejects.toThrow('Сессия изменилась')
    const next = draftBinding('admin', 'producer-a')!
    let finish!: (value: CommercialPlan) => void
    vi.mocked(api.view).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const pending = storeCommercialDraft(next, original, plan, api, signal())
    const rejected = expect(pending).rejects.toThrow('Сессия изменилась')
    useUiStore.setState({ authedUser: null, querySessionEpoch: 3 })
    finish(original)
    await rejected
    expect(Object.keys(sessionStorage).some((k) => k.includes('saved-plan'))).toBe(false)
  })

  it('evicts a previously stored snapshot when the fresh server request denies access', async () => {
    const binding = draftBinding('admin', 'producer-a')!
    const api = gateway()
    await storeCommercialDraft(binding, original, plan, api, signal())
    vi.mocked(api.view).mockRejectedValue({ response: { status: 403 } })
    await expect(
      recoverCommercialDraft(binding, original.id, original.revision, api, signal()),
    ).rejects.toMatchObject({ response: { status: 403 } })
    expect(Object.keys(sessionStorage).some((k) => k.includes('saved-plan'))).toBe(false)
  })

  it('previews explicit JSON without adopting authority and rejects credentials URLs private paths and imprecise numeric money', () => {
    const imported = importDraftJSON(
      JSON.stringify({
        ...plan,
        producerId: 'forged-owner',
        canEdit: true,
        requestIds: ['forged-request'],
      }),
    )
    expect(imported).toEqual(plan)
    for (const value of [
      { ...plan, resetToken: 'synthetic-secret' },
      { ...plan, notes: 'https://private.example.test/file' },
      { ...plan, notes: '/private/host/photo' },
      { ...plan, budgetLimit: 9007199254740992 },
    ])
      expect(() => draftJSON(value)).toThrow('без паролей')
    expect(() => importDraftJSON('invalid')).toThrow('Нужен JSON')
    expect(() => importDraftJSON('null')).toThrow('Нужен JSON')
    expect(() => importDraftJSON(JSON.stringify({ ...plan, productBriefs: [null] }))).toThrow(
      'Нужен JSON',
    )
    expect(() =>
      importDraftJSON(
        JSON.stringify({
          ...plan,
          shelfSelection: [{ shelfId: 'id', products: null, placementMonths: [] }],
        }),
      ),
    ).toThrow('Нужен JSON')
  })
})
