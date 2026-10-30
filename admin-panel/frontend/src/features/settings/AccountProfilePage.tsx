import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import { api } from '@/lib/api'
import type { UserDto } from '@/lib/api-types'
import { roleLabel } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { Button, Field, Input, PageHeader, SectionCard, Select } from '@/ui'
import { PasswordChangeForm } from './PasswordChangeForm'

/** Own identity and password only; this route never fetches account management or other users. */
export default function AccountProfilePage() {
  const t = useT()
  const navigate = useNavigate()
  const userId = useUiStore((s) => s.authedUser?.id)
  const sessionEpoch = useUiStore((s) => s.querySessionEpoch)
  const language = useUiStore((s) => s.language)
  const setLanguage = useUiStore((s) => s.setLanguage)
  const logout = useUiStore((s) => s.logout)
  const profile = useQuery({
    // Excluded from persistent query storage, and isolated across accounts/sessions.
    queryKey: ['admin-access-self-profile', userId, sessionEpoch],
    queryFn: async ({ signal }) => {
      const { data } = await api.get<UserDto>('/api/admin/auth/me', { signal })
      if (data.id !== userId) throw new Error('Account changed')
      return data
    },
    enabled: !!userId,
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
      <PageHeader title={t('account.title')} subtitle={t('account.subtitle')} />
      <SectionCard title={t('settings.profile')} subtitle={t('account.readOnly')}>
        {profile.isError ? (
          <div role="alert" className="flex flex-col items-start gap-3 text-sm text-ink-700">
            <p>{t('account.loadError')}</p>
            <Button onClick={() => void profile.refetch()} disabled={profile.isFetching}>
              {t('account.reload')}
            </Button>
          </div>
        ) : profile.isPending ? (
          <p role="status" className="text-sm text-ink-500">
            {t('account.loading')}
          </p>
        ) : profile.data ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label={t('settings.name')}>
              <Input value={profile.data.name} disabled />
            </Field>
            <Field label={t('settings.email')}>
              <Input value={profile.data.email} disabled />
            </Field>
            <Field label={t('settings.role')}>
              <Input value={roleLabel(profile.data.role)} disabled />
            </Field>
            <Field label={t('settings.company')}>
              <Input value={profile.data.company ?? ''} placeholder="—" disabled />
            </Field>
          </div>
        ) : null}
      </SectionCard>
      <SectionCard title={t('settings.localization')}>
        <Field label={t('settings.language')}>
          <Select
            value={language}
            onChange={(value) => setLanguage(value as 'ru' | 'kk')}
            options={[
              { value: 'ru', label: 'Русский' },
              { value: 'kk', label: 'Қазақша' },
            ]}
          />
        </Field>
      </SectionCard>
      <SectionCard title={t('settings.security')}>
        <div className="flex flex-col items-start gap-4">
          {profile.isSuccess && userId && (
            <PasswordChangeForm
              key={`${userId}:${sessionEpoch}`}
              userId={userId}
              sessionEpoch={sessionEpoch}
            />
          )}
          <Button
            variant="danger"
            onClick={() => {
              if (!window.confirm(t('settings.logoutConfirm'))) return
              logout()
              navigate('/login', { replace: true })
            }}
          >
            {t('settings.logoutBtn')}
          </Button>
        </div>
      </SectionCard>
    </div>
  )
}
