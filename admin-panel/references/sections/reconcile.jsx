// Сверка чеков — receipt review queue.

function ReconcileSection() {
  const [tab, setTab] = React.useState('pending');
  const [selected, setSelected] = React.useState(AD.RECONCILE_QUEUE[0]);
  const toast = useToast();
  const items = AD.RECONCILE_QUEUE.filter(r => tab==='all' || r.status===tab);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Сверка чеков" subtitle="Очередь чеков на ручную проверку · OCR + AI скоринг" actions={<>
        <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
        <Button variant="ink" size="md" leading={<IconRefresh size={14}/>}>Запросить новые</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Ожидают проверки" value="24" accent="amber" icon={<IconReconcile size={18}/>}/>
        <Metric label="Подозрительных" value="6" sub="низкий OCR" accent="ink" icon={<IconAlert size={18}/>}/>
        <Metric label="Одобрено сегодня" value="142" accent="green" icon={<IconCheck size={18}/>} delta={+8.2}/>
        <Metric label="Среднее время" value="2.4 мин" sub="на чек" accent="blue" icon={<IconClock size={18}/>}/>
      </div>

      <div className="grid gap-4" style={{gridTemplateColumns:'minmax(0,1fr) 480px'}}>
        <SectionCard padded={false}>
          <div className="px-5 pt-3">
            <Tabs value={tab} onChange={setTab} items={[
              {value:'pending', label:'На проверке', count: AD.RECONCILE_QUEUE.filter(r=>r.status==='pending').length},
              {value:'flagged', label:'Флаги',       count: AD.RECONCILE_QUEUE.filter(r=>r.status==='flagged').length},
              {value:'approved',label:'Одобрены',    count: AD.RECONCILE_QUEUE.filter(r=>r.status==='approved').length},
              {value:'rejected',label:'Отклонены',   count: AD.RECONCILE_QUEUE.filter(r=>r.status==='rejected').length},
            ]}/>
          </div>
          <div className="max-h-[640px] overflow-auto scrollbar-thin">
            <table className="tbl">
              <thead><tr><th>Загружен</th><th>Фармацевт / аптека</th><th>Товары</th><th>Сумма</th><th>OCR</th><th>Статус</th></tr></thead>
              <tbody>
                {items.map(r => (
                  <tr key={r.id} className={selected?.id===r.id?'selected':''} onClick={()=>setSelected(r)}>
                    <td className="num text-[12px]">{r.uploaded}</td>
                    <td>
                      <div className="font-extrabold">{r.pharmacist}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{r.pharmacy}</div>
                    </td>
                    <td>
                      <div className="text-[12px] font-bold truncate max-w-[260px]">{r.products[0]}</div>
                      {r.products.length>1 && <div className="text-[11px] text-ink-500">+ ещё {r.products.length-1}</div>}
                    </td>
                    <td className="num font-extrabold">{AD.fmtKzt(r.total)}</td>
                    <td>
                      <div className="flex items-center gap-2">
                        <div className="w-12"><ProgressBar value={r.score*100} max={100} color={r.score>0.8?'#16C97A':r.score>0.5?'#F4B73A':'#E5484D'}/></div>
                        <span className="text-[12px] font-bold num">{Math.round(r.score*100)}%</span>
                      </div>
                    </td>
                    <td>
                      <StatusChip status={r.status==='pending'?'pending':r.status==='flagged'?'rejected':r.status==='approved'?'approved':'rejected'}/>
                      {r.flag === 'low_ocr' && <div className="text-[11px] text-accent-danger font-bold mt-0.5">Низкий OCR</div>}
                      {r.flag === 'duplicate' && <div className="text-[11px] text-accent-danger font-bold mt-0.5">Дубликат</div>}
                      {r.flag === 'no_brand' && <div className="text-[11px] text-accent-danger font-bold mt-0.5">Нет бренда</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>

        <SectionCard title="Просмотр чека" subtitle={selected?.uploaded || ''} padded={false}
          action={selected?.status==='pending' && <>
            <Button variant="outline" size="sm" onClick={()=>{ toast.push('Чек отклонён'); }}>Отклонить</Button>
            <Button variant="primary" size="sm" leading={<IconCheck size={14}/>} onClick={()=>{ toast.push('Чек одобрен — фармацевт получит начисление'); }}>Одобрить</Button>
          </>}>
          {selected && (
            <div className="p-5 flex flex-col gap-4">
              {/* Receipt mock */}
              <div className="mx-auto w-full max-w-[260px] bg-white border hairline rounded-lg shadow-card p-4 font-mono text-[11px] text-ink-700"
                style={{clipPath:'polygon(0 0,100% 0,100% calc(100% - 8px),96% 100%,92% calc(100% - 6px),88% 100%,84% calc(100% - 6px),80% 100%,76% calc(100% - 6px),72% 100%,68% calc(100% - 6px),64% 100%,60% calc(100% - 6px),56% 100%,52% calc(100% - 6px),48% 100%,44% calc(100% - 6px),40% 100%,36% calc(100% - 6px),32% 100%,28% calc(100% - 6px),24% 100%,20% calc(100% - 6px),16% 100%,12% calc(100% - 6px),8% 100%,4% calc(100% - 6px),0 100%)'}}>
                <div className="text-center font-extrabold text-ink-900 text-[12px] mb-1">{selected.pharmacy.toUpperCase()}</div>
                <div className="text-center text-[10px] text-ink-500 mb-2">БИН 040540006331 · {selected.uploaded}</div>
                <div className="border-t border-dashed border-ink-300 pt-2 space-y-1.5">
                  {selected.products.map((p, i) => (
                    <div key={i} className="flex justify-between gap-2">
                      <span className="truncate">{p}</span>
                      <span className="num">{AD.fmt(Math.round(selected.total/selected.products.length))} ₸</span>
                    </div>
                  ))}
                </div>
                <div className="border-t border-dashed border-ink-300 mt-2 pt-2 flex justify-between font-extrabold text-ink-900">
                  <span>ИТОГО</span>
                  <span className="num">{AD.fmtKzt(selected.total)}</span>
                </div>
                <div className="text-center text-[10px] text-ink-400 mt-2">фискал. № 8412-7892-…</div>
              </div>

              {/* Match details */}
              <div className="card-soft p-3">
                <div className="text-[12px] font-bold uppercase text-ink-400 mb-2">AI совпадение</div>
                <div className="flex flex-col gap-2">
                  {selected.products.map((p,i) => (
                    <div key={i} className="flex items-center gap-2">
                      <span className="w-6 h-6 rounded-md bg-brand-green-100 text-brand-green-700 flex items-center justify-center flex-none"><IconCheck size={12}/></span>
                      <div className="flex-1 text-[12px] font-bold">{p}</div>
                      <span className="text-[11px] font-bold num text-ink-500">98%</span>
                    </div>
                  ))}
                </div>
              </div>

              {selected.flag && (
                <div className="card-soft p-3 border-accent-danger/30 bg-red-50">
                  <div className="text-[12px] font-extrabold text-accent-danger mb-1 flex items-center gap-1.5"><IconAlert size={14}/>Флаг автоматики</div>
                  <p className="text-[12px] text-ink-700 font-semibold">{
                    selected.flag === 'low_ocr' ? 'OCR-движок распознал менее 50% позиций. Сверьте вручную.' :
                    selected.flag === 'duplicate' ? 'Похожий чек уже был загружен 8 мая в 12:14. Проверьте — возможно, повторная отправка.' :
                    'В чеке не распознан ни один из брендов программы.'
                  }</p>
                </div>
              )}
            </div>
          )}
        </SectionCard>
      </div>
    </div>
  );
}

Object.assign(window, { ReconcileSection });
