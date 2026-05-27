// Управление экранами — playlist editor for in-pharmacy DOOH screens.

function ScreensSection() {
  const [selectedPL, setSelectedPL] = React.useState(AD.SCREEN_PLAYLISTS[0]);
  const [slides, setSlides] = React.useState(AD.SCREEN_SLIDES.slice());
  const [dragId, setDragId] = React.useState(null);
  const toast = useToast();

  const onDragOver = (e, overId) => {
    e.preventDefault();
    if (!dragId || dragId===overId) return;
    setSlides(arr => {
      const from = arr.findIndex(s=>s.id===dragId), to = arr.findIndex(s=>s.id===overId);
      const next = arr.slice(); const [m] = next.splice(from,1); next.splice(to,0,m); return next;
    });
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Управление экранами" subtitle="Плейлисты для indoor-DOOH экранов в кассовой зоне аптек" actions={<>
        <Button variant="outline" size="md" leading={<IconUpload size={14}/>}>Загрузить креатив</Button>
        <Button variant="primary" size="md" leading={<IconPlus size={14}/>}>Новый плейлист</Button>
      </>}/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Экранов онлайн" value="496" sub="из 507" accent="green" icon={<IconScreens size={18}/>}/>
        <Metric label="Плейлистов" value="3" sub="активных 2" accent="blue" icon={<IconLayers size={18}/>}/>
        <Metric label="Показов в день" value="48 200" accent="amber" icon={<IconEye size={18}/>}/>
        <Metric label="Среднее время в кассе" value="2:42" sub="достаточно для полного цикла" accent="purple" icon={<IconClock size={18}/>}/>
      </div>

      <div className="grid gap-4" style={{gridTemplateColumns:'280px minmax(0,1fr) 360px'}}>
        {/* Playlists */}
        <SectionCard title="Плейлисты" padded={false}>
          <ul className="divide-hairline">
            {AD.SCREEN_PLAYLISTS.map(pl => (
              <li key={pl.id} onClick={()=>setSelectedPL(pl)}
                className={`px-4 py-3 cursor-pointer ${selectedPL.id===pl.id?'bg-brand-green-50/60':'hover:bg-paper-hover'}`}
                style={selectedPL.id===pl.id?{boxShadow:'inset 3px 0 0 #16C97A'}:{}}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-[13px] font-extrabold">{pl.name}</span>
                  <span className={`chip-dot`} style={{background: pl.active?'#16C97A':'#9098A6'}}/>
                </div>
                <div className="text-[11px] text-ink-500 font-semibold">{pl.items} слайдов · {pl.duration}</div>
                <div className="text-[11px] text-ink-500 font-semibold mt-0.5">На {pl.screens} экранах</div>
              </li>
            ))}
          </ul>
        </SectionCard>

        {/* Slides */}
        <SectionCard title={selectedPL.name} subtitle={`${slides.length} слайдов · общее время ${slides.reduce((a,s)=>a+s.duration,0)} сек`} padded={false}
          action={<>
            <span className="text-[11px] text-ink-500 font-semibold">Перетащите для изменения порядка</span>
            <Button variant="outline" size="sm" leading={<IconPlus size={14}/>}>Добавить слайд</Button>
          </>}>
          <div className="p-4 flex flex-col gap-2">
            {slides.map((s, i) => (
              <div key={s.id}
                draggable onDragStart={()=>setDragId(s.id)} onDragOver={(e)=>onDragOver(e, s.id)} onDragEnd={()=>setDragId(null)}
                className={`flex items-center gap-3 p-3 rounded-xl border hairline bg-white hover:shadow-card ${dragId===s.id?'dragging':''}`}>
                <span className="drag-handle text-ink-300"><IconDrag size={16}/></span>
                <span className="text-[11px] font-bold text-ink-400 num w-6">{String(i+1).padStart(2,'0')}</span>
                <span className="w-20 h-14 rounded-lg flex items-center justify-center text-white flex-none" style={{background: s.cover}}>
                  {s.type==='video' ? <IconPlayCircle size={22}/> : <IconBox size={22}/>}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-[13px] font-extrabold truncate">{s.title}</div>
                  <div className="text-[11px] text-ink-500 font-semibold flex items-center gap-2">
                    <span>{s.type==='video'?'Видео':'Изображение'}</span>
                    <span>·</span>
                    <span className="num">{s.duration} сек</span>
                  </div>
                </div>
                <IconButton tip="Просмотр"><IconEye size={14}/></IconButton>
                <IconButton tip="Редактировать"><IconEdit size={14}/></IconButton>
                <IconButton tip="Удалить" onClick={()=>{ setSlides(arr=>arr.filter(x=>x.id!==s.id)); toast.push('Слайд удалён'); }}><IconTrash size={14}/></IconButton>
              </div>
            ))}
          </div>
        </SectionCard>

        {/* Preview */}
        <SectionCard title="Превью" subtitle="Как видит покупатель в кассе">
          <div className="rounded-2xl bg-ink-900 p-3 flex flex-col gap-2 shadow-elevated">
            <div className="aspect-video rounded-lg overflow-hidden relative flex items-center justify-center text-white text-center px-4" style={{background: slides[0]?.cover || '#16C97A'}}>
              <div>
                <div className="text-[18px] font-extrabold leading-tight">{slides[0]?.title}</div>
                <div className="text-[11px] font-bold opacity-80 mt-2">Слайд 1 из {slides.length} · {slides[0]?.duration}с</div>
              </div>
              <div className="absolute bottom-2 left-2 right-2 flex gap-1">
                {slides.map((_,i) => <span key={i} className={`flex-1 h-1 rounded-full ${i===0?'bg-white':'bg-white/30'}`}/>)}
              </div>
            </div>
            <div className="text-[11px] font-bold text-white/65 text-center">PharmaPay Screen · 1080p · 24fps</div>
          </div>
          <div className="card-soft mt-3 p-3 text-[12px] text-ink-700 font-semibold">
            <div className="font-extrabold text-ink-900 mb-1">Расписание</div>
            Будни: 09:00 – 21:00<br/>
            Выходные: 10:00 – 22:00<br/>
            <span className="text-ink-500">Все часовые пояса учтены автоматически.</span>
          </div>
        </SectionCard>
      </div>
    </div>
  );
}

Object.assign(window, { ScreensSection });
