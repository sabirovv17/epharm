// LMS — courses management.

function LMSSection() {
  const [q, setQ] = React.useState('');
  const [status, setStatus] = React.useState('all');
  const items = AD.LMS_COURSES.filter(c => (status==='all' || c.status===status) && (!q || c.title.toLowerCase().includes(q.toLowerCase())));

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Обучение / LMS" subtitle="Курсы для фармацевтов · контент, прогресс, бонусы" actions={<>
        <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
        <Button variant="primary" size="md" leading={<IconPlus size={14}/>}>Новый курс</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Опубликовано курсов" value="4" sub="из 5" accent="green" icon={<IconLMS size={18}/>}/>
        <Metric label="Прошло курсов" value="990" sub="фармацевтов" accent="blue" icon={<IconUsers size={18}/>} delta={+12.8}/>
        <Metric label="Средний прогресс" value="68%" accent="amber" icon={<IconArrowUp size={18}/>}/>
        <Metric label="Выплачено" value="312 800 ₸" sub="бонусов за курсы" accent="purple" icon={<IconFinance size={18}/>}/>
      </div>

      <div className="card">
        <div className="flex items-center justify-between px-5 py-3 border-b hairline">
          <div className="flex items-center gap-2">
            <SearchInput value={q} onChange={setQ} placeholder="Название курса" className="w-[280px] !h-9"/>
            <Select value={status} onChange={setStatus} options={[{value:'all',label:'Все'},{value:'published',label:'Опубликован'},{value:'draft',label:'Черновик'}]} className="w-[160px]"/>
          </div>
        </div>
        <div className="grid grid-cols-3 gap-4 p-5">
          {items.map(c => (
            <div key={c.id} className="card-soft p-4 flex flex-col gap-3 hover:shadow-card transition">
              <div className="aspect-video rounded-lg overflow-hidden relative flex items-end p-4 text-white" style={{background:`linear-gradient(135deg, ${{c_aqm_basic:'#16C97A',c_baby_safe:'#5560FB',c_pino:'#3DCDA2',c_subst:'#2A2BE2',c_cross:'#F4B73A'}[c.id]}, ${{c_aqm_basic:'#0F8F55',c_baby_safe:'#2A2BE2',c_pino:'#16C97A',c_subst:'#1F1FCC',c_cross:'#B45309'}[c.id]})`}}>
                <span className="absolute top-3 right-3"><StatusChip status={c.status==='published'?'active':'draft'}/></span>
                <span className="absolute top-3 left-3 chip chip-blue !bg-white/95">+{c.bonus} ₸</span>
                <div>
                  <div className="text-[11px] font-bold opacity-80 uppercase tracking-[0.06em]">{c.lessons} уроков · {c.duration}</div>
                  <div className="text-[16px] font-extrabold leading-tight max-w-[240px]">{c.title}</div>
                </div>
              </div>
              <div className="flex items-center justify-between text-[12px] font-bold">
                <span className="text-ink-500">Прохождение</span>
                <span className="text-ink-900 num">{c.completed} / {c.students}</span>
              </div>
              <ProgressBar value={c.completed} max={c.students}/>
              <div className="flex items-center justify-between pt-2 border-t hairline">
                <span className="text-[11px] text-ink-500 font-semibold">Обновлён {c.updatedAt}</span>
                <div className="flex items-center gap-1">
                  <IconButton tip="Превью"><IconEye size={14}/></IconButton>
                  <IconButton tip="Редактировать"><IconEdit size={14}/></IconButton>
                  <IconButton><IconDots size={14}/></IconButton>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { LMSSection });
