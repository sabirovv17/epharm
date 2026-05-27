// Фармацевты — registry with profile drawer.

function PharmacistsSection() {
  const [q, setQ] = React.useState('');
  const [tier, setTier] = React.useState('all');
  const [status, setStatus] = React.useState('all');
  const [selected, setSelected] = React.useState(null);
  const [confirmBlock, setConfirmBlock] = React.useState(null);
  const toast = useToast();

  const items = AD.PHARMACISTS.filter(p =>
    (tier==='all' || p.tier===tier) &&
    (status==='all' || p.status===status) &&
    (!q || (p.name+p.pharmacy+p.phone+p.city).toLowerCase().includes(q.toLowerCase()))
  );

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Фармацевты" subtitle="Реестр пользователей PharmaPay · 48 фармацевтов в выборке" actions={<>
        <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
        <Button variant="outline" size="md" leading={<IconUpload size={14}/>}>Пригласить</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Всего фармацевтов" value="1 248" sub="из 1 540" accent="green" icon={<IconUsers size={18}/>}/>
        <Metric label="Platinum" value="312" sub="25%" accent="amber" icon={<IconStar size={18}/>}/>
        <Metric label="Активны 30д" value="1 086" sub="87%" accent="blue" icon={<IconCheck size={18}/>}/>
        <Metric label="Заблокированы" value="4" accent="ink" icon={<IconLock size={18}/>}/>
      </div>

      <div className="card">
        <div className="flex items-center justify-between px-5 py-3 border-b hairline">
          <div className="flex items-center gap-2">
            <SearchInput value={q} onChange={setQ} placeholder="ФИО, телефон, аптека, ИИН…" className="w-[320px] !h-9"/>
            <Select value={tier} onChange={setTier} options={[{value:'all',label:'Все тиры'},'Silver','Gold','Platinum']} className="w-[140px]"/>
            <Select value={status} onChange={setStatus} options={[{value:'all',label:'Все'},{value:'active',label:'Активные'},{value:'pending',label:'Pending'},{value:'blocked',label:'Заблокированы'}]} className="w-[160px]"/>
          </div>
          <span className="text-[12px] text-ink-500 font-semibold">{items.length} из {AD.PHARMACISTS.length}</span>
        </div>
        <div className="overflow-auto scrollbar-thin max-h-[640px]">
          <table className="tbl">
            <thead><tr><th>Фармацевт</th><th>Аптека</th><th>Чеков 30д</th><th>Правил</th><th>Курсы</th><th>Заработал</th><th>Тир</th><th>Статус</th></tr></thead>
            <tbody>
              {items.map(p => (
                <tr key={p.id} onClick={()=>setSelected(p)}>
                  <td>
                    <div className="flex items-center gap-3">
                      <Avatar name={p.name}/>
                      <div>
                        <div className="font-extrabold">{p.name}</div>
                        <div className="text-[11px] text-ink-500 font-semibold num">{p.phone}</div>
                      </div>
                    </div>
                  </td>
                  <td><div className="font-bold">{p.pharmacy}</div><div className="text-[11px] text-ink-500">{p.city}</div></td>
                  <td className="num">{p.receipts30d}</td>
                  <td className="num">{p.rulesAccepted30d}</td>
                  <td>
                    <div className="flex items-center gap-2">
                      <span className="num text-[12px] font-bold">{p.coursesDone}/{p.coursesTotal}</span>
                      <div className="w-16"><ProgressBar value={p.coursesDone} max={p.coursesTotal}/></div>
                    </div>
                  </td>
                  <td className="num font-extrabold">{AD.fmtKzt(p.earned30d)}</td>
                  <td><span className={`chip ${p.tier==='Platinum'?'chip-amber':p.tier==='Gold'?'chip-blue':'chip-ink'}`}>{p.tier}</span></td>
                  <td><StatusChip status={p.status==='active'?'active':p.status==='blocked'?'archived':'pending'}/></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <Drawer open={!!selected} onClose={()=>setSelected(null)} title={selected?.name || ''} subtitle={selected?.pharmacy} width={520}
        footer={selected && <>
          <Button variant="ghost">История</Button>
          <Button variant="danger" leading={<IconLock size={14}/>} onClick={()=>setConfirmBlock(selected)}>Заблокировать</Button>
        </>}>
        {selected && (
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-4">
              <Avatar name={selected.name} size={64}/>
              <div className="flex-1">
                <div className="text-[18px] font-extrabold">{selected.name}</div>
                <div className="text-[12px] text-ink-500 num">ИИН {selected.iin}</div>
                <div className="text-[12px] text-ink-500 num">{selected.phone}</div>
              </div>
              <StatusChip status={selected.status==='active'?'active':selected.status==='blocked'?'archived':'pending'}/>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Metric label="Заработано 30д" value={AD.fmtKzt(selected.earned30d)} accent="green"/>
              <Metric label="Баланс" value={AD.fmtKzt(selected.balance)} accent="blue"/>
              <Metric label="Чеков 30д" value={selected.receipts30d} accent="ink"/>
              <Metric label="Принято правил" value={selected.rulesAccepted30d} accent="amber"/>
            </div>
            <SectionCard title="Курсы LMS" padded={false}>
              <ul className="divide-hairline">
                {AD.LMS_COURSES.slice(0,4).map((c,i) => (
                  <li key={c.id} className="flex items-center gap-3 px-4 py-3">
                    <span className="w-9 h-9 rounded-lg flex items-center justify-center flex-none" style={{background: i<selected.coursesDone?'#D7F5E4':'#EEF0F5', color:i<selected.coursesDone?'#0F8F55':'#9098A6'}}>
                      {i<selected.coursesDone?<IconCheck size={16}/>:<IconLMS size={16}/>}
                    </span>
                    <div className="flex-1 min-w-0">
                      <div className="text-[13px] font-bold truncate">{c.title}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{c.duration} · +{c.bonus} ₸</div>
                    </div>
                    {i<selected.coursesDone && <span className="chip chip-green">Готово</span>}
                  </li>
                ))}
              </ul>
            </SectionCard>
            <SectionCard title="Последние чеки" padded={false}>
              <ul className="divide-hairline">
                {AD.RECONCILE_QUEUE.slice(0,4).map(r => (
                  <li key={r.id} className="flex items-center gap-3 px-4 py-3">
                    <span className="w-9 h-9 rounded-lg bg-paper-input flex items-center justify-center flex-none"><IconReceipt size={16}/></span>
                    <div className="flex-1">
                      <div className="text-[13px] font-bold">{r.products[0]}{r.products.length>1?` +${r.products.length-1}`:''}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{r.uploaded}</div>
                    </div>
                    <span className="text-[13px] font-extrabold num">{AD.fmtKzt(r.total)}</span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          </div>
        )}
      </Drawer>

      <Modal open={!!confirmBlock} onClose={()=>setConfirmBlock(null)} title="Заблокировать фармацевта?" subtitle={confirmBlock ? `${confirmBlock.name} больше не сможет загружать чеки и получать бонусы.` : ''}
        footer={<>
          <Button variant="ghost" onClick={()=>setConfirmBlock(null)}>Отмена</Button>
          <Button variant="danger" onClick={()=>{ setConfirmBlock(null); setSelected(null); toast.push('Фармацевт заблокирован'); }}>Заблокировать</Button>
        </>}/>
    </div>
  );
}

Object.assign(window, { PharmacistsSection });
