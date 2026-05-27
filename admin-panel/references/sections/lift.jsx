// Lift analytics — pilot vs control comparison.

function LiftSection() {
  const [period, setPeriod] = React.useState('12w');
  const L = AD.LIFT_DATA;
  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="Аналитика lift" subtitle="Прирост продаж пилотных аптек относительно контрольной группы. Учитывает только бренды Jadran." actions={
        <Select value={period} onChange={setPeriod} options={[{value:'4w',label:'4 недели'},{value:'12w',label:'12 недель'},{value:'ytd',label:'YTD'}]} className="w-[160px]"/>
      }/>

      <div className="grid grid-cols-4 gap-4">
        <Metric label="Lift" value={`+${L.liftPct}%`} accent="green" icon={<IconLift size={18}/>} meta="vs контроль" delta={+50.4}/>
        <Metric label="p-value" value={L.pValue} sub="статзначимо" accent="blue" icon={<IconShield size={18}/>}/>
        <Metric label="Пилотных аптек" value={L.pilotN} accent="ink" icon={<IconPharmacy size={18}/>}/>
        <Metric label="Контрольных" value={L.controlN} accent="amber" icon={<IconShield size={18}/>}/>
      </div>

      <SectionCard title="Пилот vs контроль" subtitle={`${L.weeks} недель · еженедельные продажи Аквамарис, у.е.`}>
        <LiftChart pilot={L.pilot} control={L.control}/>
        <div className="flex items-center gap-6 mt-4 text-[12px] font-semibold">
          <span className="flex items-center gap-2"><span className="w-3 h-3 rounded-sm bg-brand-green-600"/>Пилотная группа · +50.4%</span>
          <span className="flex items-center gap-2"><span className="w-3 h-3 rounded-sm bg-ink-400"/>Контрольная группа · +20.8%</span>
          <span className="ml-auto chip chip-green">Δ +29.6 п.п.</span>
        </div>
      </SectionCard>

      <div className="grid grid-cols-2 gap-4">
        <SectionCard title="Lift по сетям" padded={false}>
          <ul className="divide-hairline">
            {AD.CHAINS.filter(c=>c.group!=='control').map((c,i) => (
              <li key={c.id} className="flex items-center gap-3 px-4 py-3">
                <span className="w-3 h-3 rounded-full" style={{background:c.color}}/>
                <div className="flex-1">
                  <div className="text-[13px] font-bold">{c.name}</div>
                  <div className="text-[11px] text-ink-500 font-semibold">{c.points} точек · {c.group==='pilot'?'пилот':'развёрнут.'}</div>
                </div>
                <div className="w-32"><ProgressBar value={20+i*8} max={70}/></div>
                <span className="chip chip-green num">+{18+i*6}%</span>
              </li>
            ))}
          </ul>
        </SectionCard>
        <SectionCard title="Lift по категориям" padded={false}>
          <ul className="divide-hairline">
            {[
              { name:'Аквамарис Норм спрей', lift:62 },
              { name:'Аквамарис Беби', lift:48 },
              { name:'Аквамарис Стронг', lift:38 },
              { name:'Пиносол', lift:24 },
              { name:'МНН-замена морская вода', lift:71 },
            ].map((it,i) => (
              <li key={it.name} className="flex items-center gap-3 px-4 py-3">
                <span className="w-9 h-9 rounded-lg bg-brand-green-100 text-brand-green-700 flex items-center justify-center"><IconBox size={16}/></span>
                <div className="flex-1">
                  <div className="text-[13px] font-bold">{it.name}</div>
                  <div className="text-[11px] text-ink-500 font-semibold">pilot n=168 · control n=168</div>
                </div>
                <div className="w-32"><ProgressBar value={it.lift} max={80}/></div>
                <span className="chip chip-green num">+{it.lift}%</span>
              </li>
            ))}
          </ul>
        </SectionCard>
      </div>

      <SectionCard title="Метод" subtitle="DID (difference-in-differences) на еженедельном уровне">
        <div className="grid grid-cols-2 gap-4 text-[13px] text-ink-700">
          <div>
            <div className="font-extrabold text-ink-900 mb-1">Что измеряем</div>
            <p>Изменение средненедельных продаж бренда Аквамарис в пилотных аптеках минус то же изменение в контрольных. Контрольная группа аналогична по геометрии, среднему чеку и сезонности.</p>
          </div>
          <div>
            <div className="font-extrabold text-ink-900 mb-1">Допущения</div>
            <p>Pre-trend параллельный (визуально и тест Granger). Цены не менялись. Других кампаний на бренд в контрольной группе не проводилось.</p>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}

function LiftChart({ pilot, control }) {
  const W=720, H=260, pad={l:36, r:12, t:14, b:28};
  const max = Math.max(...pilot, ...control)*1.05;
  const min = Math.min(...pilot, ...control)*0.92;
  const rng = max-min;
  const n = pilot.length;
  const xs = (i) => pad.l + i*(W-pad.l-pad.r)/(n-1);
  const ys = (v) => H-pad.b - ((v-min)/rng) * (H-pad.t-pad.b);
  const path = (arr) => arr.map((v,i)=>`${i?'L':'M'}${xs(i).toFixed(1)} ${ys(v).toFixed(1)}`).join(' ');
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full h-auto" style={{maxHeight:320}}>
      <defs>
        <linearGradient id="lf-pilot" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#16C97A" stopOpacity="0.35"/>
          <stop offset="100%" stopColor="#16C97A" stopOpacity="0"/>
        </linearGradient>
      </defs>
      {[0,1,2,3,4].map(k => {
        const y = pad.t + k*(H-pad.t-pad.b)/4;
        return <line key={k} x1={pad.l} x2={W-pad.r} y1={y} y2={y} stroke="rgba(15,20,36,0.06)"/>;
      })}
      <path d={`${path(pilot)} L ${xs(n-1)} ${H-pad.b} L ${pad.l} ${H-pad.b} Z`} fill="url(#lf-pilot)"/>
      <path d={path(pilot)} stroke="#16C97A" strokeWidth="2.4" fill="none" strokeLinecap="round"/>
      <path d={path(control)} stroke="#9098A6" strokeWidth="2" fill="none" strokeDasharray="4 4" strokeLinecap="round"/>
      {Array.from({length:n}, (_,i) => i%2===1 && <text key={i} x={xs(i)} y={H-8} textAnchor="middle" fontSize="10" fill="#9098A6" fontFamily="JetBrains Mono">W{i+1}</text>)}
    </svg>
  );
}

Object.assign(window, { LiftSection });
