import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { accessApi, accessFailure } from './access-api'

vi.mock('@/lib/api', () => ({ api: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.get).mockResolvedValue({ data: [] })
  vi.mocked(api.post).mockResolvedValue({ data: { accountId: 'demo-account', grants: [] } })
  vi.mocked(api.put).mockResolvedValue({
    data: { accountId: 'demo-account', grants: [], version: 8 },
  })
})

describe('Access API transport', () => {
  it('uses existing admin auth transport and abort signals for all five reads', async () => {
    const signal = new AbortController().signal
    await accessApi.me(signal)
    await accessApi.accounts(signal)
    await accessApi.people(signal)
    await accessApi.catalog(signal)
    await accessApi.profile('opaque/id', signal)
    expect(vi.mocked(api.get).mock.calls).toEqual([
      ['/api/admin/access/me', { signal }],
      ['/api/admin/access/accounts', { signal }],
      ['/api/admin/access/people', { signal }],
      ['/api/admin/access/catalog', { signal }],
      ['/api/admin/access/accounts/opaque%2Fid', { signal }],
    ])
  })
  it('passes verified identity, exact grants and CAS version only in write bodies', async () => {
    const request = {
      email: 'demo@example.test',
      name: 'Demo',
      password: 'SyntheticOnly-1234',
      currentPassword: 'SyntheticProof-1234',
      verifiedPersonId: 'demo-person',
      role: 'HQ_HEAD' as const,
      jobTitle: 'Demo',
    }
    await accessApi.create(request)
    const grants = [
      {
        module: 'pharmacists',
        subsection: 'registry',
        action: 'view',
        allPharmacies: false,
        pharmacyIds: ['opaque-pharmacy-01'],
      },
    ]
    await accessApi.replace('demo-account', 7, grants, 'SyntheticProof-1234')
    expect(api.post).toHaveBeenCalledWith('/api/admin/access/accounts', request)
    expect(api.put).toHaveBeenCalledWith('/api/admin/access/accounts/demo-account/grants', {
      expectedVersion: 7,
      grants,
      currentPassword: 'SyntheticProof-1234',
    })
  })
  it('sends status changes with a fresh proof, exact version and abort signal', async () => {
    const signal = new AbortController().signal
    await accessApi.changeStatus('opaque/id', 7, 'BLOCKED', 'SyntheticProof-1234', signal)
    expect(api.put).toHaveBeenCalledWith(
      '/api/admin/access/accounts/opaque%2Fid/status',
      {
        expectedVersion: 7,
        status: 'BLOCKED',
        currentPassword: 'SyntheticProof-1234',
      },
      { signal },
    )
  })
  it('distinguishes email conflict from stale permission version and preserves field errors', () => {
    const response = {
      status: 409,
      data: {
        code: 'CONFLICT',
        message: 'Email уже используется',
        fields: { email: 'Email уже используется' },
      },
      statusText: '',
      headers: {},
      config: {} as never,
    }
    const error = new AxiosError('Synthetic error', undefined, undefined, undefined, response)
    expect(accessFailure(error).message).toBe('Email уже используется')
    expect(accessFailure(error).fields).toEqual({ email: 'Email уже используется' })
    expect(accessFailure(error, true).message).toContain('Данные уже изменились')
    expect(accessFailure(new Error('Do not expose low-level details')).message).not.toContain(
      'low-level',
    )
  })
})
