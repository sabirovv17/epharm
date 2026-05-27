// Promo campaigns — list of marketing campaigns with budgets, status, KPI.

function PromoSection() {
  const [view, setView] = React.useState('grid'); // grid | list
  const [q, setQ] = React.useState('');
  const [status, setStatus] = React.useState('all');
  const items = AD.PROMOS.filter(p =>
    (status==='all' || p.status===status) &&
    (!q || p.title.toLowerCase().includes(q.toLowerCase()))
  );
  const toast = useToast();
  const [openCreate, setOpenCreate] = React.useState(false);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Промо-кампании" subtitle="Активные и запланированные кампании. Каждая привязана к контракту бренда."
        actions={<>
          <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
          <Button variant="primary" size="md" leading={<IconPlus size={14}/>} onClick={()=>setOpenCreate(true)}>Новая кампания</Button>
        </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Активных кампаний" value="3" sub="из 5" accent="green" icon={<IconPromo size={18}/>}/>
        <Metric label="Бюджет в работе" value="6 600 000 ₸" accent="blue" delta={+8.4} icon={<IconFinance size={18}/>}/>
        <Metric label="Освоено" value="2 460 740 ₸" sub="37%" accent="amber" icon={<IconArrowUp size={18}/>}/>
        <Metric label="Среднее ROI" value="3.2×" sub="по принятым" accent="purple" delta={+0.4} icon={<IconLift size={18}/>}/>
      </div>

      <div className="card">
        <div className="flex items-center justify-between px-5 py-3 border-b hairline">
          <div className="flex items-center gap-2">
            <SearchInput value={q} onChange={setQ} placeholder="Поиск кампании" className="w-[280px] !h-9"/>
            <Select value={status} onChange={setStatus} options={[{value:'all',label:'Все статусы'},{value:'active',label:'Активные'},{value:'draft',label:'Черновики'},{value:'paused',label:'Пауза'}]} className="w-[160px]"/>
          </div>
          <div className="flex items-center gap-1 border hairline rounded-lg p-0.5">
            <button onClick={()=>setView('grid')}  className={`px-2.5 h-7 rounded-md text-[12px] font-bold flex items-center gap-1.5 ${view==='grid'?'bg-ink-100 text-ink-900':'text-ink-500'}`}><IconBox size={13}/>Сетка</button>
            <button onClick={()=>setView('list')}  className={`px-2.5 h-7 rounded-md text-[12px] font-bold flex items-center gap-1.5 ${view==='list'?'bg-ink-100 text-ink-900':'text-ink-500'}`}><IconReconcile size={13}/>Таблица</button>
          </div>
        </div>

        {view === 'grid' ? (
          <div className="grid grid-cols-3 gap-4 p-5">
            {items.map(p => <PromoCard key={p.id} promo={p} onToggle={()=>toast.push(`Кампания ${p.status==='active'?'на паузе':'возобновлена'}`)}/>)}
          </div>
        ) : (
          <table className="tbl">
            <thead><tr><th>Кампания</th><th>Период</th><th>Аптек</th><th>KPI</th><th>Бюджет</th><th>Статус</th><th/></tr></thead>
            <tbody>
              {items.map(p => (
                <tr key={p.id}>
                  <td>
                    <div className="flex items-center gap-3">
                      <span className="w-10 h-10 rounded-lg flex-none" style={{background:p.cover}}/>
                      <div>
                        <div className="font-extrabold">{p.title}</div>
                        <div className="text-[11px] text-ink-500 font-semibold">{p.brand}</div>
                      </div>
                    </div>
                  </td>
                  <td className="num">{p.period}</td>
                  <td className="num">{p.pharmacies}</td>
                  <td>{p.kpi}</td>
                  <td>
                    <div className="text-[12px] text-ink-500 font-semibold">{AD.fmtKzt(p.spent)} / {AD.fmtKzt(p.budget)}</div>
                    <div className="mt-1 w-32"><ProgressBar value={p.spent} max={p.budget}/></div>
                  </td>
                  <td><StatusChip status={p.status}/></td>
                  <td><IconButton><IconDots size={16}/></IconButton></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Modal open={openCreate} onClose={()=>setOpenCreate(false)} title="Новая кампания" subtitle="Заполните основные параметры — детали можно дополнить позже" width={560}
        footer={<>
          <Button variant="ghost" onClick={()=>setOpenCreate(false)}>Отмена</Button>
          <Button variant="primary" onClick={()=>{ setOpenCreate(false); toast.push('Кампания сохранена в черновики'); }}>Создать черновик</Button>
        </>}>
        <div className="flex flex-col gap-3">
          <Field label="Название"><Input placeholder="Например: Майский марафон Аквамарис"/></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Дата старта"><Input defaultValue="01.06.2026"/></Field>
            <Field label="Дата окончания"><Input defaultValue="30.06.2026"/></Field>
          </div>
          <Field label="Бренд"><Select value="Jadran-Galenski" onChange={()=>{}} options={['Jadran-Galenski','Polpharma','Sanofi']}/></Field>
          <Field label="Бюджет, ₸"><Input type="number" defaultValue="3000000"/></Field>
          <Field label="Группа аптек"><Select value="all" onChange={()=>{}} options={[{value:'all',label:'Все 507 аптек'},{value:'pilot',label:'Только пилотные (8 сетей)'},{value:'rolled',label:'Развёрнутая выборка'}]}/></Field>
        </div>
      </Modal>
    </div>
  );
}

function PromoCard({ promo, onToggle }) {
  return (
    <div className="card-soft overflow-hidden">
      <div className="h-28 relative" style={{background: `linear-gradient(135deg, ${promo.cover}, ${promo.cover}cc)`}}>
        <div className="absolute top-3 left-3"><StatusChip status={promo.status}/></div>
        <div className="absolute bottom-3 left-4 text-white">
          <div className="text-[11px] font-bold uppercase tracking-[0.06em] opacity-80">{promo.brand}</div>
          <div className="text-[16px] font-extrabold leading-tight max-w-[240px]">{promo.title}</div>
        </div>
      </div>
      <div className="p-4">
        <div className="flex items-center justify-between mb-2 text-[12px] text-ink-500 font-semibold">
          <span>{promo.period}</span>
          <span>{promo.pharmacies} аптек</span>
        </div>
        <div className="flex items-center justify-between mb-1 text-[12px] font-bold">
          <span className="text-ink-500">Бюджет</span>
          <span className="text-ink-900 num">{AD.fmtKzt(promo.spent)} / {AD.fmtKzt(promo.budget)}</span>
        </div>
        <ProgressBar value={promo.spent} max={promo.budget}/>
        <div className="mt-3 flex items-center justify-between">
          <span className="text-[12px] font-bold text-ink-700">{promo.kpi}</span>
          <div className="flex items-center gap-1">
            <IconButton onClick={onToggle}>{promo.status==='active' ? <IconPause size={14}/> : <IconPlay size={14}/>}</IconButton>
            <IconButton><IconEdit size={14}/></IconButton>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { PromoSection });
