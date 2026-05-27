// Сеть аптек — table + chain breakdown + map placeholder.

function PharmaciesSection() {
  const [q, setQ] = React.useState('');
  const [group, setGroup] = React.useState('all');
  const [chain, setChain] = React.useState('all');
  const [selected, setSelected] = React.useState(null);

  const items = AD.PHARMACY_LIST.filter(p =>
    (group==='all' || p.group===group) &&
    (chain==='all' || p.chainId===chain) &&
    (!q || (p.name+p.city+p.addr).toLowerCase().includes(q.toLowerCase()))
  );

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Сеть аптек" subtitle="507 точек продаж · 8 сетей · 3 группы выборки" actions={<>
        <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
        <Button variant="outline" size="md" leading={<IconUpload size={14}/>}>Импорт точек</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Всего точек" value="507" accent="green" icon={<IconPharmacy size={18}/>} meta="из них 489 активны"/>
        <Metric label="Пилотных"    value="298" sub="59%" accent="blue" icon={<IconLayers size={18}/>}/>
        <Metric label="Контрольных" value="73"  sub="14%" accent="ink"  icon={<IconShield size={18}/>}/>
        <Metric label="Развёрнутых" value="136" sub="27%" accent="amber" icon={<IconArrowUp size={18}/>}/>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <SectionCard className="col-span-2" title="Аптеки" padded={false} action={
          <div className="flex items-center gap-2">
            <SearchInput value={q} onChange={setQ} placeholder="Название, город, адрес" className="w-[260px] !h-9"/>
            <Select value={group} onChange={setGroup} options={[{value:'all',label:'Все группы'},{value:'pilot',label:'Пилотные'},{value:'control',label:'Контрольные'},{value:'rolled',label:'Развёрнутые'}]} className="w-[160px]"/>
            <Select value={chain} onChange={setChain} options={[{value:'all',label:'Все сети'},...AD.CHAINS.map(c=>({value:c.id,label:c.name}))]} className="w-[180px]"/>
          </div>
        }>
          <div className="overflow-auto scrollbar-thin max-h-[620px]">
            <table className="tbl">
              <thead><tr><th>Аптека</th><th>Город</th><th>Группа</th><th>Фарм.</th><th>Чеков 30д</th><th>GMV 30д</th><th>Lift</th><th/></tr></thead>
              <tbody>
                {items.map(p => (
                  <tr key={p.id} className={selected===p.id?'selected':''} onClick={()=>setSelected(p.id)}>
                    <td>
                      <div className="font-extrabold">{p.name}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{p.addr}</div>
                    </td>
                    <td>{p.city}<div className="text-[11px] text-ink-500">{p.district}</div></td>
                    <td><span className={`chip ${p.group==='pilot'?'chip-blue':p.group==='control'?'chip-ink':'chip-amber'}`}>{p.group==='pilot'?'Пилот':p.group==='control'?'Контроль':'Развёрнут.'}</span></td>
                    <td className="num">{p.pharmacists}</td>
                    <td className="num">{AD.fmt(p.receipts30d)}</td>
                    <td className="num">{AD.fmtKzt(p.gmv30d)}</td>
                    <td>{p.liftPct ? <span className="chip chip-green num">+{p.liftPct}%</span> : <span className="text-ink-300">—</span>}</td>
                    <td><IconButton><IconDots size={16}/></IconButton></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>

        <div className="flex flex-col gap-4">
          <SectionCard title="Сети" padded={false}>
            <ul className="divide-hairline">
              {AD.CHAINS.map(c => (
                <li key={c.id} className="flex items-center gap-3 px-4 py-3">
                  <span className="w-3 h-3 rounded-full flex-none" style={{background:c.color}}/>
                  <div className="flex-1 min-w-0">
                    <div className="text-[13px] font-bold text-ink-900">{c.name}</div>
                    <div className="text-[11px] text-ink-500 font-semibold">{c.points} точек · {c.group==='pilot'?'пилот':c.group==='control'?'контроль':'развёрнут.'}</div>
                  </div>
                  <span className="text-[13px] font-extrabold text-ink-900 num">{c.points}</span>
                </li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard title="Карта точек">
            <div className="rounded-xl bg-paper-input h-48 flex flex-col items-center justify-center text-ink-400 gap-2 grid-bg relative overflow-hidden">
              {AD.CHAINS.flatMap((c,ci) => Array.from({length: Math.min(c.points/12, 8)}, (_,i) => (
                <span key={c.id+i} className="absolute w-2.5 h-2.5 rounded-full" style={{background:c.color, opacity:0.85, left: `${10 + (ci*11 + i*13) % 80}%`, top: `${15 + (ci*17 + i*23) % 70}%`}}/>
              )))}
              <div className="absolute bottom-2 right-2 text-[10px] font-bold text-ink-400 bg-white/80 px-2 py-1 rounded">8 городов</div>
            </div>
            <Button variant="outline" size="sm" className="w-full mt-3" trailing={<IconExternal size={12}/>}>Открыть в полноэкранном режиме</Button>
          </SectionCard>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { PharmaciesSection });
