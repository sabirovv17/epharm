// Settings — конфигурация системы. В отличие от других секций, тут нет «данных» —
// тут поля настроек, которые работают и в пустой системе.

import { Button, Field, Input, PageHeader, SectionCard, Select, type SelectOption } from '@/ui'
import { IconLogout, IconShield } from '@/ui/icons'
import { useUiStore } from '@/app/store'
import { roleLabel } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { useNavigate } from 'react-router-dom'
import AccessManagement from './AccessManagement'

export default function SettingsPage() {
  const navigate = useNavigate()
  const t = useT()
  const logout = useUiStore((s) => s.logout)
  const authedUser = useUiStore((s) => s.authedUser)
  const language = useUiStore((s) => s.language)
  const setLanguage = useUiStore((s) => s.setLanguage)

  const LANG_OPTIONS: SelectOption[] = [
    { value: 'ru', label: t('settings.lang.ru') },
    { value: 'kk', label: t('settings.lang.kk') },
  ]

  const handleLogout = () => {
    if (!confirm(t('settings.logoutConfirm'))) return
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.settings.title')} subtitle={t('page.settings.subtitle')} />

      <SectionCard title={t('settings.profile')} subtitle={t('settings.profileSub')}>
        <div className="grid grid-cols-2 gap-4">
          <Field label={t('settings.name')}>
            <Input value={authedUser?.name ?? ''} disabled />
          </Field>
          <Field label={t('settings.role')}>
            <Input value={authedUser ? roleLabel(authedUser.role) : ''} disabled />
          </Field>
          <Field label={t('settings.company')}>
            <Input value={authedUser?.company ?? ''} disabled />
          </Field>
          <Field label={t('settings.email')}>
            <Input type="email" value={authedUser?.email ?? ''} placeholder="—" disabled />
          </Field>
        </div>
      </SectionCard>

      {(authedUser?.role === 'HQ_HEAD' || authedUser?.role === 'SYSTEM_ADMIN') && (
        <AccessManagement key={authedUser.id} />
      )}

      <SectionCard title={t('settings.localization')} subtitle={t('settings.localizationSub')}>
        <div className="grid grid-cols-2 gap-4">
          <Field label={t('settings.timezone')} hint={t('settings.timezoneUnavailable')}>
            <Input value="" placeholder={t('settings.notConfiguredHere')} disabled />
          </Field>
          <Field label={t('settings.language')}>
            <Select
              value={language}
              onChange={(v) => setLanguage(v as 'ru' | 'kk')}
              options={LANG_OPTIONS}
            />
          </Field>
        </div>
      </SectionCard>

      <SectionCard title={t('settings.security')} subtitle={t('settings.securitySub')}>
        <div className="flex flex-col gap-4">
          <p className="text-sm text-ink-500">{t('settings.securityUnavailable')}</p>
          <div className="grid grid-cols-2 gap-4">
            <Field label={t('settings.twoFa')}>
              <Input value="" placeholder={t('settings.notConfiguredHere')} disabled />
            </Field>
            <Field label={t('settings.emailNotif')}>
              <Input value="" placeholder={t('settings.notConfiguredHere')} disabled />
            </Field>
          </div>
          <div className="hairline border-t pt-3">
            <Button variant="danger" leading={<IconLogout size={14} />} onClick={handleLogout}>
              {t('settings.logoutBtn')}
            </Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title={t('settings.autoTitle')} subtitle={t('settings.autoSub')}>
        <div className="flex flex-col gap-4">
          <p className="text-sm text-ink-500">{t('settings.automationUnavailable')}</p>
          <Field label={t('settings.autoThreshold')}>
            <Input value="" placeholder={t('settings.notConfiguredHere')} disabled />
          </Field>
        </div>
      </SectionCard>

      <div className="text-[12px] text-ink-400">
        <IconShield size={12} className="mr-1 inline-block" /> {t('settings.note')}
      </div>
    </div>
  )
}
