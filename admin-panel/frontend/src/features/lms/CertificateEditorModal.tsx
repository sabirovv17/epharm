import { useMemo, useState, type ChangeEvent } from 'react'
import { Check, Monitor, QrCode, RefreshCw, Trash2 } from 'lucide-react'
import type { TrainingProgramDto } from '@/lib/api-types'
import {
  useUpdateTrainingProgram,
  useUploadTrainingCertificateAsset,
} from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { Button, Field, Input, Modal, Select, useToast } from '@/ui'
import { IconUpload } from '@/ui/icons'

interface CertificateEditorModalProps {
  open: boolean
  onClose: () => void
  programs: TrainingProgramDto[]
}

const SAMPLE_PARTICIPANT = 'Грущак Василий Григорьевич'

interface CertificateDraft {
  programId: string
  epharmLogoUrl: string | null
  partnerLogoUrl: string | null
  signerName: string
  validityMonths: string
}

function draftFromProgram(program: TrainingProgramDto): CertificateDraft {
  return {
    programId: program.id,
    epharmLogoUrl: program.certificateEpharmLogoUrl,
    partnerLogoUrl: program.certificatePartnerLogoUrl,
    signerName: program.certificateSignerName || 'Руководитель учебного центра',
    validityMonths: String(program.certificateValidityMonths || 36),
  }
}

export function CertificateEditorModal({ open, onClose, programs }: CertificateEditorModalProps) {
  const toast = useToast()
  const updateProgram = useUpdateTrainingProgram()
  const uploadAsset = useUploadTrainingCertificateAsset()
  const initialProgramId = programs.find((program) => program.status === 'published')?.id ?? programs[0]?.id ?? ''
  const [programId, setProgramId] = useState(initialProgramId)
  const program = useMemo(
    () => programs.find((item) => item.id === programId) ?? programs[0] ?? null,
    [programId, programs],
  )
  const [draft, setDraft] = useState<CertificateDraft | null>(null)
  const currentDraft = program
    ? draft?.programId === program.id ? draft : draftFromProgram(program)
    : null
  const epharmLogoUrl = currentDraft?.epharmLogoUrl ?? null
  const partnerLogoUrl = currentDraft?.partnerLogoUrl ?? null
  const signerName = currentDraft?.signerName ?? ''
  const validityMonths = currentDraft?.validityMonths ?? '36'
  const updateDraft = (change: Partial<CertificateDraft>) => {
    if (!program) return
    setDraft((previous) => ({
      ...(previous?.programId === program.id ? previous : draftFromProgram(program)),
      ...change,
    }))
  }

  const partnerName = program?.manufacturer.trim() || program?.brand.trim() || 'Компания-партнёр'
  const pending = updateProgram.isPending || uploadAsset.isPending

  const upload = (file: File, onSuccess: (url: string) => void) => {
    uploadAsset.mutate(file, {
      onSuccess: ({ assetUrl }) => onSuccess(assetUrl),
      onError: (error) => toast.push(describeError(error)),
    })
  }

  const save = () => {
    if (!program) return
    if (!signerName.trim()) {
      toast.push('Укажите подписанта сертификата')
      return
    }
    const parsedValidityMonths = Number(validityMonths)
    if (!Number.isInteger(parsedValidityMonths) || parsedValidityMonths < 1 || parsedValidityMonths > 120) {
      toast.push('Укажите срок действия от 1 до 120 месяцев')
      return
    }
    updateProgram.mutate(
      {
        id: program.id,
        patch: {
          certificateEpharmLogoUrl: epharmLogoUrl,
          clearCertificateEpharmLogoUrl: !epharmLogoUrl,
          certificatePartnerLogoUrl: partnerLogoUrl,
          clearCertificatePartnerLogoUrl: !partnerLogoUrl,
          certificateSignerName: signerName.trim(),
          certificateValidityMonths: parsedValidityMonths,
        },
      },
      {
        onSuccess: () => {
          toast.push('Шаблон сертификата сохранён')
          onClose()
        },
        onError: (error) => toast.push(describeError(error)),
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Редактор сертификата"
      subtitle="Современная лента · данные участника и курса подставляются автоматически"
      width={1180}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Отмена</Button>
          <Button leading={<Check size={15} />} disabled={!program || pending} onClick={save}>
            {updateProgram.isPending ? 'Сохраняем…' : 'Сохранить шаблон'}
          </Button>
        </>
      }
    >
      {!program ? (
        <div className="rounded-xl border border-dashed border-ink-200 px-5 py-10 text-center text-sm text-ink-500">
          Сначала создайте программу обучения — сертификат настраивается для каждой программы отдельно.
        </div>
      ) : (
        <div className="grid gap-5 xl:grid-cols-[330px_minmax(0,1fr)]">
          <div className="space-y-4">
            <Field label="Программа">
              <Select
                value={program.id}
                onChange={setProgramId}
                options={programs.map((item) => ({ value: item.id, label: item.name }))}
                ariaLabel="Программа сертификата"
              />
            </Field>

            <div className="grid grid-cols-2 gap-3">
              <LogoUpload
                label="Лого ePharm"
                url={epharmLogoUrl}
                busy={uploadAsset.isPending}
                onUpload={(file) => upload(file, (url) => updateDraft({ epharmLogoUrl: url }))}
                onClear={() => updateDraft({ epharmLogoUrl: null })}
              />
              <LogoUpload
                label="Лого партнёра"
                url={partnerLogoUrl}
                busy={uploadAsset.isPending}
                onUpload={(file) => upload(file, (url) => updateDraft({ partnerLogoUrl: url }))}
                onClear={() => updateDraft({ partnerLogoUrl: null })}
              />
            </div>

            <Field label="Подписант">
              <Input value={signerName} maxLength={255} onChange={(event) => updateDraft({ signerName: event.target.value })} />
            </Field>
            <Field label="Срок действия" hint="От 1 до 120 месяцев">
              <Input
                type="number"
                min={1}
                max={120}
                value={validityMonths}
                onChange={(event) => updateDraft({ validityMonths: event.target.value })}
              />
            </Field>

            <div className="rounded-xl border border-brand-green-100 bg-brand-green-50/70 p-4">
              <div className="mb-3 flex items-center gap-2 text-xs font-extrabold uppercase tracking-[0.08em] text-brand-green-800">
                <RefreshCw size={14} /> Автоподстановка
              </div>
              <AutoRow label="ФИО участника" value="Из назначения" />
              <AutoRow label="Название курса" value={program.name} />
              <AutoRow label="Компания-партнёр" value={partnerName} />
              <AutoRow label="Обложка курса" value={program.coverUrl ? 'Подключена' : 'Не загружена'} />
            </div>
          </div>

          <div>
            <div className="mb-2 flex items-center justify-between gap-3">
              <div>
                <div className="text-[13px] font-bold text-ink-800">Предпросмотр A4</div>
                <div className="text-[11px] text-ink-400">Итоговый PDF сохранит этот состав и QR-проверку</div>
              </div>
              <span className="chip chip-green">Выбранный вариант</span>
            </div>
            <CertificatePreview
              program={program}
              partnerName={partnerName}
              epharmLogoUrl={epharmLogoUrl}
              partnerLogoUrl={partnerLogoUrl}
              signerName={signerName}
              validityMonths={Number(validityMonths) || 36}
            />
          </div>
        </div>
      )}
    </Modal>
  )
}

const previewProgram: TrainingProgramDto = {
  id: 'certificate-preview-program',
  name: 'Безопасный отпуск лекарственных средств',
  shortDescription: 'Демонстрационная программа',
  description: '',
  coverUrl: null,
  certificateEpharmLogoUrl: null,
  certificatePartnerLogoUrl: null,
  certificateSignerName: 'Руководитель учебного центра',
  certificateValidityMonths: 36,
  certificateTemplate: 'modern_ribbon',
  category: 'Фармацевтическое консультирование',
  manufacturer: 'INKAR',
  brand: '',
  product: '',
  language: 'ru',
  managerId: null,
  managerName: null,
  allowedFormats: ['online'],
  startsAt: null,
  endsAt: null,
  normativeDays: 14,
  tags: [],
  status: 'published',
  version: 1,
  versionId: 'certificate-preview-version',
  onlineCourseId: null,
  passingScore: 80,
  maxAttempts: 3,
  completionBonus: 0,
  stages: [],
  assignments: 1,
  createdAt: '2026-09-29T08:00:00Z',
  updatedAt: '2026-09-29T08:00:00Z',
}

export function CertificateEditorPreviewPage() {
  return (
    <main className="min-h-screen bg-paper-canvas p-8">
      <CertificateEditorModal open onClose={() => undefined} programs={[previewProgram]} />
    </main>
  )
}

function LogoUpload({
  label,
  url,
  busy,
  onUpload,
  onClear,
}: {
  label: string
  url: string | null
  busy: boolean
  onUpload: (file: File) => void
  onClear: () => void
}) {
  const onChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (file) onUpload(file)
    event.target.value = ''
  }
  return (
    <div className="rounded-xl border border-ink-150 bg-paper-input p-3">
      <div className="mb-2 text-[11px] font-bold text-ink-700">{label}</div>
      <div className="flex h-20 items-center justify-center overflow-hidden rounded-lg border border-dashed border-ink-200 bg-white p-2">
        {url ? (
          <img src={url} alt={label} className="max-h-full max-w-full object-contain" />
        ) : (
          <span className="text-center text-[11px] text-ink-400">PNG или JPG<br />до 5 МБ</span>
        )}
      </div>
      <div className="mt-2 flex items-center gap-1">
        <label className="btn btn-sm btn-outline min-w-0 flex-1 cursor-pointer px-2">
          <IconUpload size={14} />
          <span className="truncate">{busy ? 'Загрузка…' : 'Загрузить'}</span>
          <input
            className="sr-only"
            type="file"
            accept="image/png,image/jpeg"
            aria-label={`Загрузить ${label}`}
            disabled={busy}
            onChange={onChange}
          />
        </label>
        {url && (
          <button type="button" className="btn btn-sm btn-ghost btn-icon" aria-label={`Удалить ${label}`} onClick={onClear}>
            <Trash2 size={14} />
          </button>
        )}
      </div>
    </div>
  )
}

function AutoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-3 border-t border-brand-green-100 py-2 first:border-t-0 first:pt-0 last:pb-0">
      <span className="text-[11px] text-ink-500">{label}</span>
      <span className="max-w-[155px] text-right text-[11px] font-bold text-ink-800">{value}</span>
    </div>
  )
}

function CertificatePreview({
  program,
  partnerName,
  epharmLogoUrl,
  partnerLogoUrl,
  signerName,
  validityMonths,
}: {
  program: TrainingProgramDto
  partnerName: string
  epharmLogoUrl: string | null
  partnerLogoUrl: string | null
  signerName: string
  validityMonths: number
}) {
  return (
    <div
      className="relative mx-auto w-full max-w-[640px] overflow-hidden rounded-[14px] border border-[#dae6e5] bg-[#f5f9f8] p-[1.35%] shadow-[0_18px_55px_rgba(8,57,62,0.13)] [container-type:inline-size]"
      style={{ aspectRatio: '1.414 / 1' }}
      data-testid="certificate-preview"
    >
      <div className="absolute inset-[1.35%] overflow-hidden rounded-[10px] bg-white" />
      <div className="absolute inset-x-[2.65%] top-[4%] grid h-[23%] grid-cols-[1fr_2.2fr_1fr] items-center rounded-[8px] bg-[#044b51] px-[2.5%] text-white">
        {program.coverUrl && (
          <img src={program.coverUrl} alt="Обложка курса в шапке сертификата" className="absolute inset-0 h-full w-full rounded-[8px] object-cover opacity-[0.16]" />
        )}
        <div className="relative z-10 flex h-full items-center border-r border-[#5bc4be] pr-[10%]">
          {epharmLogoUrl ? (
            <img src={epharmLogoUrl} alt="Логотип ePharm в сертификате" className="max-h-[62%] max-w-full object-contain" />
          ) : (
            <div>
              <div className="text-[clamp(13px,3.4cqw,27px)] font-black tracking-[-0.055em]">ePharm</div>
              <div className="mt-0.5 text-[clamp(4px,.9cqw,7px)] font-extrabold uppercase tracking-[0.13em] text-[#bce8e4]">обучение для фармацевтов</div>
            </div>
          )}
        </div>
        <div className="relative z-10 px-[5%] text-center">
          <div className="text-[clamp(20px,5.7cqw,49px)] font-black leading-none tracking-[-0.055em]">СЕРТИФИКАТ</div>
          <div className="mt-[4%] text-[clamp(4px,1.1cqw,8px)] font-extrabold uppercase tracking-[0.31em] text-[#bce8e4]">знания сегодня — здоровье завтра</div>
        </div>
        <div className="relative z-10 flex h-full items-center justify-center border-l border-[#5bc4be] pl-[10%] text-center">
          {partnerLogoUrl ? (
            <img src={partnerLogoUrl} alt="Логотип партнёра в сертификате" className="max-h-[62%] max-w-full object-contain" />
          ) : (
            <div>
              <div className="line-clamp-2 text-[clamp(9px,2.4cqw,20px)] font-black uppercase leading-tight">{partnerName}</div>
              <div className="mt-1 text-[clamp(4px,.9cqw,7px)] font-extrabold uppercase tracking-[0.14em] text-[#bce8e4]">компания-партнёр</div>
            </div>
          )}
        </div>
      </div>

      <div className="absolute bottom-[12%] left-[4.2%] right-[27%] top-[30%] overflow-hidden">
        <div className="relative z-10">
          <div className="text-[clamp(5px,1.4cqw,12px)] font-medium text-[#657a8e]">Настоящим подтверждается, что</div>
          <div className="mt-[2%] whitespace-nowrap text-[clamp(13px,3.5cqw,34px)] font-black tracking-[-0.05em] text-[#0a2d34]">{SAMPLE_PARTICIPANT}</div>
          <div className="mt-[2.5%] text-[clamp(5px,1.4cqw,12px)] font-medium text-[#657a8e]">завершил программу обучения</div>
          <div className="mt-[1.5%] max-w-[91%] border-l-[5px] border-[#009a8f] pl-[2.2%]">
            <div className="line-clamp-2 text-[clamp(11px,3cqw,27px)] font-black leading-[1.08] tracking-[-0.04em] text-[#009a8f]">{program.name}</div>
          </div>
        </div>
        <div className="absolute bottom-[6%] left-0 z-10">
          <div className="inline-flex items-center gap-1.5 rounded-full bg-[#dcf7f3] px-2 py-1 text-[clamp(4px,1.1cqw,9px)] font-extrabold uppercase text-[#044b51]">
            <Monitor className="h-[1.2em] w-[1.2em]" /> Онлайн-формат
          </div>
          <div className="mt-[12%] text-[clamp(5px,1.25cqw,10px)] text-[#657a8e]">Результат</div>
          <div className="text-[clamp(15px,3.8cqw,36px)] font-black leading-none text-[#009a8f]">100%</div>
        </div>
        <div className="absolute bottom-[-7%] right-[4%] select-none text-[clamp(52px,14cqw,145px)] font-black leading-none text-[#bceee9]/60">100%</div>
      </div>

      <div className="absolute bottom-[12%] right-[3.1%] top-[30%] w-[23%] border-l border-[#c6dada] pl-[3%]">
        <div className="text-center text-[clamp(6px,1.8cqw,15px)] font-black text-[#0a2d34]">Проверить сертификат</div>
        <div className="mx-auto mt-[9%] flex aspect-square w-[70%] items-center justify-center text-[#0a2d34]">
          <QrCode className="h-full w-full" strokeWidth={1.7} aria-hidden="true" />
        </div>
        <div className="mt-[7%] text-[clamp(4px,1.05cqw,9px)] leading-snug text-[#657a8e]">QR-код появится на выданном сертификате</div>
      </div>

      <div className="absolute inset-x-[2.65%] bottom-[4%] grid h-[7.5%] grid-cols-[1.1fr_1fr_2fr_1.25fr] items-center rounded-[6px] bg-[#dcf7f3] px-[2.6%] text-[clamp(4px,1.05cqw,9px)] font-bold text-[#294753]">
        <div>№ EPH-2026-000154</div>
        <div className="border-l border-[#7fbab6] pl-[8%]">Выдан 29.09.2026</div>
        <div className="truncate border-l border-[#7fbab6] pl-[6%]">Подписант: {signerName}</div>
        <div className="border-l border-[#7fbab6] pl-[8%]">Срок: {validityMonths} мес.</div>
      </div>
    </div>
  )
}
