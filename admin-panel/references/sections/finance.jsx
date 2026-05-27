// Finance / payouts — batch approval flow.

function FinanceSection() {
  const [selectedBatch, setSelectedBatch] = React.useState(AD.PAYOUT_BATCHES[0]);
  const [selectedItems, setSelectedItems] = React.useState({});
  const [confirmApprove, setConfirmApprove] = React.useState(false);
  const toast = useToast();

  const items = AD.PAYOUT_ITEMS;
  const checkedCount = Object.values(selectedItems).filter(Boolean).length;
  const allChecked = checkedCount === items.length;
  const checkedSum = items.filter(it => selectedItems[it.id]).reduce((a,it) => a+it.amount, 0);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Финансы / выплаты" subtitle="Утверждение начислений фармацевтам · сверка с банком" actions={<>
        <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Реестр</Button>
        <Button variant="outline" size="md" leading={<IconHistory size={14}/>}>История</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="К выплате сейчас" value="4 180 400 ₸" sub="312 фармацевтов" accent="amber" icon={<IconFinance size={18}/>}/>
        <Metric label="Выплачено за май" value="1 842 300 ₸" sub="62% бюджета" accent="green" icon={<IconCheck size={18}/>}/>
        <Metric label="Средняя выплата" value="13 400 ₸" accent="blue" icon={<IconUsers size={18}/>}/>
        <Metric label="Аномалий" value="3" sub="требуют проверки" accent="ink" icon={<IconAlert size={18}/>}/>
      </div>

      <div className="grid gap-4" style={{gridTemplateColumns:'320px minmax(0,1fr)'}}>
        <SectionCard title="Партии выплат" padded={false}>
          <ul className="divide-hairline">
            {AD.PAYOUT_BATCHES.map(b => (
              <li key={b.id} onClick={()=>{ setSelectedBatch(b); setSelectedItems({}); }}
                className={`px-4 py-3 cursor-pointer ${selectedBatch.id===b.id?'bg-brand-green-50/60':'hover:bg-paper-hover'}`}
                style={selectedBatch.id===b.id?{boxShadow:'inset 3px 0 0 #16C97A'}:{}}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-[13px] font-extrabold">{b.period}</span>
                  <StatusChip status={b.status==='pending'?'pending':'approved'}/>
                </div>
                <div className="text-[14px] font-extrabold text-ink-900 num">{AD.fmtKzt(b.amount)}</div>
                <div className="text-[11px] text-ink-500 font-semibold">{b.pharmacists} фармацевтов</div>
              </li>
            ))}
          </ul>
        </SectionCard>

        <SectionCard title={`Партия · ${selectedBatch.period}`} subtitle={`${selectedBatch.pharmacists} получателей · ${AD.fmtKzt(selectedBatch.amount)}`} padded={false}
          action={selectedBatch.status==='pending' ? <>
            <Button variant="outline" size="sm" disabled={checkedCount===0}>Отклонить ({checkedCount})</Button>
            <Button variant="primary" size="sm" disabled={checkedCount===0} leading={<IconCheck size={14}/>} onClick={()=>setConfirmApprove(true)}>Утвердить выплаты {checkedCount?`(${checkedCount})`:''}</Button>
          </> : <span className="chip chip-green">Утверждено {selectedBatch.approvedAt}</span>}>

          <div className="px-5 py-2 border-b hairline bg-paper-hover flex items-center justify-between">
            <label className="flex items-center gap-2 text-[12px] font-bold text-ink-700">
              <input type="checkbox" checked={allChecked} onChange={e=>{
                const v = e.target.checked; const map={}; items.forEach(it=>map[it.id]=v); setSelectedItems(map);
              }}/>
              <span>Выбрать всех</span>
            </label>
            {checkedCount > 0 && <span className="text-[12px] font-extrabold text-brand-green-700 num">Выбрано: {checkedCount} · {AD.fmtKzt(checkedSum)}</span>}
          </div>

          <div className="max-h-[480px] overflow-auto scrollbar-thin">
            <table className="tbl">
              <thead><tr><th></th><th>Фармацевт</th><th>Аптека</th><th>Чеков</th><th>Правил</th><th>Сумма</th><th>Флаг</th></tr></thead>
              <tbody>
                {items.map(it => (
                  <tr key={it.id} className={selectedItems[it.id]?'selected':''}>
                    <td>
                      <input type="checkbox" checked={!!selectedItems[it.id]} onChange={e=>setSelectedItems(s=>({...s, [it.id]:e.target.checked}))}/>
                    </td>
                    <td><div className="flex items-center gap-2"><Avatar name={it.pharmacist} size={26}/><span className="font-bold">{it.pharmacist}</span></div></td>
                    <td>{it.pharmacy} · {it.city}</td>
                    <td className="num">{it.receipts}</td>
                    <td className="num">{it.rules}</td>
                    <td className="num font-extrabold">{AD.fmtKzt(it.amount)}</td>
                    <td>{it.flag === 'anomaly' && <span className="chip chip-red"><IconAlert size={11}/>Аномалия</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      </div>

      <Modal open={confirmApprove} onClose={()=>setConfirmApprove(false)} title={`Утвердить выплаты на ${AD.fmtKzt(checkedSum)}?`} subtitle={`${checkedCount} фармацевтов. После подтверждения деньги уйдут в очередь банка.`}
        footer={<>
          <Button variant="ghost" onClick={()=>setConfirmApprove(false)}>Отмена</Button>
          <Button variant="primary" onClick={()=>{ setConfirmApprove(false); setSelectedItems({}); toast.push(`Утверждено ${checkedCount} выплат на ${AD.fmtKzt(checkedSum)}`); }} leading={<IconCheck size={14}/>}>Подтвердить</Button>
        </>}>
        <div className="flex flex-col gap-2 text-[13px] text-ink-700">
          <div className="card-soft p-3 flex items-center gap-3">
            <span className="w-9 h-9 rounded-lg bg-brand-green-100 text-brand-green-700 flex items-center justify-center"><IconShield size={16}/></span>
            <div>
              <div className="font-extrabold">Двухэтапное подтверждение</div>
              <div className="text-[12px] text-ink-500">После этого окна — SMS-код на телефон ответственного.</div>
            </div>
          </div>
          <div className="card-soft p-3 text-[12px] text-ink-500">
            Подписант: Айгерим Сарсенова · Category Lead · Inkar. Операция отразится в банке в течение 30 минут.
          </div>
        </div>
      </Modal>
    </div>
  );
}

Object.assign(window, { FinanceSection });
