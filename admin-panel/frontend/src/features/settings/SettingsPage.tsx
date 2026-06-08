// Settings — конфигурация системы. В отличие от других секций, тут нет «данных» —
// тут поля настроек, которые работают и в пустой системе.

import { useState } from 'react'
import {
  Button,
  Field,
  Input,
  PageHeader,
  SectionCard,
  Select,
  Toggle,
  type SelectOption,
} from '@/ui'
import { IconLogout, IconShield } from '@/ui/icons'
import { useUiStore } from '@/app/store'
import { roleLabel } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { useNavigate } from 'react-router-dom'

const TZ_OPTIONS: SelectOption[] = [
  { value: 'Asia/Almaty', label: 'Алматы (UTC+5)' },
  { value: 'Asia/Aqtobe', label: 'Актобе (UTC+5)' },
  { value: 'Asia/Astana', label: 'Астана (UTC+5)' },
]

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

  const [tz, setTz] = useState('Asia/Almaty')
  const [emailNotifications, setEmailNotifications] = useState(true)
  const [twoFactor, setTwoFactor] = useState(false)
  const [autoApprove, setAutoApprove] = useState(true)
  const [autoApproveThreshold, setAutoApproveThreshold] = useState('0.95')

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.settings.title')} subtitle={t('page.settings.subtitle')} />

      <SectionCard title={t('settings.profile')} subtitle={t('settings.profileSub')}>
        <div className="grid grid-cols-2 gap-4">
          <Field label={t('settings.name')}>
            <Input defaultValue={authedUser?.name ?? ''} disabled />
          </Field>
          <Field label={t('settings.role')}>
            <Input defaultValue={authedUser ? roleLabel(authedUser.role) : ''} disabled />
          </Field>
          <Field label={t('settings.company')}>
            <Input defaultValue={authedUser?.company ?? ''} disabled />
          </Field>
          <Field label={t('settings.email')}>
            <Input type="email" defaultValue={authedUser?.email ?? ''} placeholder="—" disabled />
          </Field>
        </div>
      </SectionCard>

      <SectionCard title={t('settings.localization')} subtitle={t('settings.localizationSub')}>
        <div className="grid grid-cols-2 gap-4">
          <Field label={t('settings.timezone')}>
            <Select value={tz} onChange={setTz} options={TZ_OPTIONS} />
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
          <Toggle on={twoFactor} onChange={setTwoFactor} label={t('settings.twoFa')} />
          <Toggle
            on={emailNotifications}
            onChange={setEmailNotifications}
            label={t('settings.emailNotif')}
          />
          <div className="hairline border-t pt-3">
            <Button variant="danger" leading={<IconLogout size={14} />} onClick={handleLogout}>
              {t('settings.logoutBtn')}
            </Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title={t('settings.autoTitle')} subtitle={t('settings.autoSub')}>
        <div className="flex flex-col gap-4">
          <Toggle on={autoApprove} onChange={setAutoApprove} label={t('settings.autoToggle')} />
          <Field label={t('settings.autoThreshold')} hint={t('settings.autoHint')}>
            <Input
              type="number"
              value={autoApproveThreshold}
              onChange={(e) => setAutoApproveThreshold(e.target.value)}
              min="0"
              max="1"
              step="0.01"
              disabled={!autoApprove}
            />
          </Field>
        </div>
      </SectionCard>

      <div className="text-[12px] text-ink-400">
        <IconShield size={12} className="mr-1 inline-block" /> {t('settings.note')}
      </div>
    </div>
  )
}
