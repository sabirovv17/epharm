import ReportDownloadButton from '@/features/reports/ReportDownloadButton'

export default function TrainingLearningExport({
  mode,
  allowed,
  nameSearch = '',
  filter = {},
}: {
  mode: 'assignments' | 'results'
  allowed: boolean
  nameSearch?: string
  filter?: { format?: string; status?: string; programId?: string }
}) {
  return (
    <div className="flex flex-col gap-1">
      <ReportDownloadButton
        allowed={allowed}
        path={`/api/admin/training/exports/${mode}.xlsx`}
        params={filter}
        filename={`training-${mode}.xlsx`}
        label={mode === 'assignments' ? 'Excel: назначения' : 'Excel: результаты'}
        invalid={
          nameSearch.trim()
            ? 'Очистите поиск по имени: Excel не поддерживает этот фильтр'
            : undefined
        }
        note={
          nameSearch.trim()
            ? 'Поиск таблицы не поддерживается выгрузкой. Очистите его перед скачиванием.'
            : mode === 'results'
              ? 'Все записанные результаты в вашей области, включая историю попыток; без скрытого периода и ключей ответов.'
              : 'Все страницы назначений по выбранным формату, статусу и программе.'
        }
      />
      {!allowed && <p className="text-[11px] text-ink-500">Нет права на эту выгрузку Excel</p>}
    </div>
  )
}
