// Dashboard — top-level analytics overview.

function DashboardSection() {
  const monthMetrics = [
    { label:'GMV май',                value:'182 400 000 ₸', sub:'прогноз 215 000 000 ₸', delta:+18.2, accent:'green', icon:<IconReceipt size={18}/>, meta:'Цель 200 000 000 ₸' },
    { label:'Принятых рекомендаций', value:'14 820',      sub:'из 50 410',         delta:+6.4,  accent:'blue',  icon:<IconRules size={18}/>, meta:'Конверсия 29.4%' },
    { label:'Выплачено фармацевтам', value:'1 842 300 ₸',  sub:'/ 3 000 000 ₸',  delta:-2.1,  accent:'amber', icon:<IconFinance size={18}/>, meta:'62% от лимита' },
    { label:'Активных фармацевтов',  value:'1 248',       sub:'из 1 540',          delta:+8.7,  accent:'purple',icon:<IconPharmacist size={18}/>, meta:'507 аптек' },
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Дашборд аналитики"
        subtitle="Витрина показателей по программе фарм-маркетинга. Май 2026 · все бренды Jadran."
        actions={<>
          <Button variant="outline" size="md" leading={<IconDownload size={14}/>}>Экспорт</Button>
          <Button variant="ink" size="md" leading={<IconRefresh size={14}/>}>Обновить</Button>
        </>}
      />

      {/* KPI cards */}
      <div className="grid grid-cols-4 gap-4">
        {monthMetrics.map(m => <Metric key={m.label} {...m}/>)}
      </div>

      {/* Big chart + heatmap */}
      <div className="grid grid-cols-3 gap-4">
        <SectionCard className="col-span-2" title="Динамика принятых рекомендаций" subtitle="14 дней · принято vs показано" action={
          <div className="flex items-center gap-1.5">
            <button className="chip chip-green">Принято</button>
            <button className="chip chip-ink">Показано</button>
            <IconButton tip="Опции"><IconDots size={16}/></IconButton>
          </div>
        }>
          <BigChart/>
        </SectionCard>

        <SectionCard title="Тепловая карта активности" subtitle="12 недель × дни недели">
          <Heatmap/>
        </SectionCard>
      </div>

      {/* Tables row */}
      <div className="grid grid-cols-3 gap-4">
        <SectionCard className="col-span-2" title="Топ правил замены по конверсии" action={<Button variant="ghost" size="sm" trailing={<IconArrowRight size={14}/>}>Все правила</Button>} padded={false}>
          <TopRulesTable/>
        </SectionCard>
        <SectionCard title="Топ-5 аптек по lift" subtitle="прирост принятых рекомендаций vs контроль" padded={false}>
          <TopPharmaciesList/>
        </SectionCard>
      </div>

      {/* Bottom — funnel + alerts */}
      <div className="grid grid-cols-3 gap-4">
        <SectionCard className="col-span-2" title="Воронка фармацевта" subtitle="как рекомендации превращаются в выплаты">
          <Funnel/>
        </SectionCard>
        <SectionCard title="Уведомления" subtitle="на сегодня" action={<Button variant="ghost" size="sm">Все</Button>}>
          <AlertsList/>
        </SectionCard>
      </div>
    </div>
  );
}

function PageHeader({ title, subtitle, actions }) {
  return (
    <div className="flex items-start justify-between gap-4 pt-2">
      <div>
        <h1 className="text-[24px] font-extrabold text-ink-900 leading-tight tracking-tight">{title}</h1>
        {subtitle && <p className="text-[14px] text-ink-500 mt-1 max-w-[680px]">{subtitle}</p>}
      </div>
      <div className="flex items-center gap-2 flex-none pt-1">{actions}</div>
    </div>
  );
}

// ─── Big chart ───────────────────────────────────────────────────────────

function BigChart() {
  const days = 14;
  const shown   = Array.from({length:days}, (_,i) => 800 + Math.round(380 + Math.sin(i/2)*220 + i*42 + (i%3)*55));
  const accepted= shown.map((v,i) => Math.round(v * (0.22 + 0.08 * Math.sin(i/3 + 1)) ));
  const W=720, H=240, pad={l:32, r:12, t:12, b:28};
  const max = Math.max(...shown) * 1.05;
  const xs = (i) => pad.l + i * (W - pad.l - pad.r) / (days-1);
  const ys = (v) => H - pad.b - (v / max) * (H - pad.t - pad.b);
  const lineShown   = shown.map((v,i)=>`${i?'L':'M'}${xs(i).toFixed(1)} ${ys(v).toFixed(1)}`).join(' ');
  const areaShown   = `${lineShown} L ${xs(days-1).toFixed(1)} ${H-pad.b} L ${pad.l} ${H-pad.b} Z`;
  const lineAccept  = accepted.map((v,i)=>`${i?'L':'M'}${xs(i).toFixed(1)} ${ys(v).toFixed(1)}`).join(' ');
  const areaAccept  = `${lineAccept} L ${xs(days-1).toFixed(1)} ${H-pad.b} L ${pad.l} ${H-pad.b} Z`;
  const yticks = 4;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full h-auto" style={{maxHeight:280}}>
      <defs>
        <linearGradient id="gShown" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#9098A6" stopOpacity="0.18"/>
          <stop offset="100%" stopColor="#9098A6" stopOpacity="0"/>
        </linearGradient>
        <linearGradient id="gAccept" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#16C97A" stopOpacity="0.35"/>
          <stop offset="100%" stopColor="#16C97A" stopOpacity="0"/>
        </linearGradient>
      </defs>
      {Array.from({length: yticks+1}, (_,k) => {
        const y = pad.t + k * (H - pad.t - pad.b) / yticks;
        const v = Math.round(max - k * max / yticks);
        return (
          <g key={k}>
            <line x1={pad.l} x2={W-pad.r} y1={y} y2={y} stroke="rgba(15,20,36,0.06)" strokeWidth="1"/>
            <text x={pad.l-6} y={y+3} textAnchor="end" fontSize="10" fill="#9098A6" fontFamily="JetBrains Mono">{v}</text>
          </g>
        );
      })}
      <path d={areaShown}  fill="url(#gShown)"/>
      <path d={lineShown}  stroke="#9098A6" strokeWidth="1.5" fill="none" strokeLinecap="round"/>
      <path d={areaAccept} fill="url(#gAccept)"/>
      <path d={lineAccept} stroke="#16C97A" strokeWidth="2.2" fill="none" strokeLinecap="round"/>
      {accepted.map((v,i) => <circle key={i} cx={xs(i)} cy={ys(v)} r="3" fill="#fff" stroke="#16C97A" strokeWidth="1.8"/>)}
      {Array.from({length:days}, (_,i) => i%2===0 && (
        <text key={i} x={xs(i)} y={H-8} textAnchor="middle" fontSize="10" fill="#9098A6" fontFamily="JetBrains Mono">{8+i}/05</text>
      ))}
    </svg>
  );
}

// ─── Heatmap ─────────────────────────────────────────────────────────────

function Heatmap() {
  const days = ['Пн','Вт','Ср','Чт','Пт','Сб','Вс'];
  return (
    <div className="flex flex-col gap-2">
      <div className="grid gap-1.5" style={{ gridTemplateColumns: 'auto repeat(12, 1fr)' }}>
        <span/>
        {Array.from({length:12}, (_,i) => <span key={i} className="text-[9px] font-bold text-ink-400 text-center">W{i+1}</span>)}
        {days.map((d, di) => (
          <React.Fragment key={d}>
            <span className="text-[10px] font-bold text-ink-500 pr-1">{d}</span>
            {AD.HEATMAP.map((week,wi) => {
              const v = week[di];
              return <span key={wi} className={`aspect-square rounded-[3px] heat-${v} tip`}>
                <span className="tip-body">{['нет данных','низкая','умеренная','высокая','пиковая'][v]} активность</span>
              </span>;
            })}
          </React.Fragment>
        ))}
      </div>
      <div className="flex items-center gap-2 mt-2 text-[10px] font-bold text-ink-400">
        <span>меньше</span>
        {[0,1,2,3,4].map(i => <span key={i} className={`w-3 h-3 rounded-[2px] heat-${i}`}/>)}
        <span>больше</span>
      </div>
    </div>
  );
}

// ─── Top rules table ─────────────────────────────────────────────────────

function TopRulesTable() {
  const rules = [...AD.RULES_SUBST, ...AD.RULES_CROSS].sort((a,b) => b.convRate - a.convRate).slice(0,5);
  return (
    <div className="overflow-hidden">
      <table className="tbl">
        <thead>
          <tr><th>Правило</th><th>Тип</th><th>Тренд</th><th className="text-right">Конверсия</th><th className="text-right">Принято</th></tr>
        </thead>
        <tbody>
          {rules.map(r => {
            const trig = AD.productById(r.trigger.kind==='product' ? r.trigger.value : r.trigger.kind==='product_any' ? r.trigger.value[0] : '');
            const rec  = AD.productById(r.recommend);
            return (
              <tr key={r.id}>
                <td>
                  <div className="flex items-center gap-2">
                    <span className="w-8 h-8 rounded-md bg-ink-100 text-ink-500 flex items-center justify-center"><IconSwap size={14}/></span>
                    <div className="min-w-0">
                      <div className="text-[13px] font-bold text-ink-900 truncate max-w-[260px]">{trig ? trig.name.split(' ').slice(0,3).join(' ') : r.trigger.value} → {rec.name.split(' ').slice(0,3).join(' ')}</div>
                      <div className="text-[11px] text-ink-500 font-semibold">{r.pharmacies} аптек · бонус {AD.fmtKzt(r.bonus)}</div>
                    </div>
                  </div>
                </td>
                <td><span className={`chip ${r.type==='substitution'?'chip-blue':'chip-amber'}`}>{r.type==='substitution'?'Замена':'Кросс-сейл'}</span></td>
                <td><Sparkline values={r.spark} width={80} height={28}/></td>
                <td className="text-right num font-extrabold text-ink-900">{r.convRate.toFixed(1)}%</td>
                <td className="text-right num font-bold text-ink-700">{AD.fmt(r.accepts)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ─── Top pharmacies list ────────────────────────────────────────────────

function TopPharmaciesList() {
  const list = AD.PHARMACY_LIST.filter(p => p.group !== 'control').sort((a,b) => b.liftPct - a.liftPct).slice(0,6);
  return (
    <ul className="divide-hairline">
      {list.map((p,i) => (
        <li key={p.id} className="flex items-center gap-3 px-5 py-3.5">
          <span className="w-6 h-6 rounded-md bg-ink-100 text-ink-500 flex items-center justify-center text-[11px] font-bold flex-none num">{i+1}</span>
          <div className="flex-1 min-w-0">
            <div className="text-[13px] font-bold text-ink-900 truncate">{p.name}</div>
            <div className="text-[11px] text-ink-500 font-semibold">{p.city} · {p.pharmacists} фарм. · {AD.fmt(p.receipts30d)} чеков</div>
          </div>
          <span className="chip chip-green num">+{p.liftPct}%</span>
        </li>
      ))}
    </ul>
  );
}

// ─── Funnel ─────────────────────────────────────────────────────────────

function Funnel() {
  const steps = [
    { label:'Чеков загружено', value:50410, color:'#0F1424' },
    { label:'Распознано чеков', value:48820, color:'#3F465A' },
    { label:'Сработал триггер правила', value:33240, color:'#2A2BE2' },
    { label:'Принято рекомендаций', value:14820, color:'#16C97A' },
    { label:'Подтверждено к выплате', value:13980, color:'#0F8F55' },
  ];
  const max = steps[0].value;
  return (
    <div className="flex flex-col gap-3">
      {steps.map((s,i) => (
        <div key={s.label} className="flex items-center gap-3">
          <div className="w-44 flex-none">
            <div className="text-[13px] font-bold text-ink-900">{s.label}</div>
            <div className="text-[11px] text-ink-500 font-semibold">{i>0 && `${Math.round(s.value/steps[i-1].value*100)}% от предыдущего`}</div>
          </div>
          <div className="flex-1 h-9 bg-ink-50 rounded-md overflow-hidden">
            <div className="h-full flex items-center pl-3 text-white text-[13px] font-extrabold rounded-md" style={{width:`${s.value/max*100}%`, background:s.color}}>
              <span className="num">{AD.fmt(s.value)}</span>
            </div>
          </div>
          <div className="w-20 text-right text-[12px] font-bold text-ink-500 num">{Math.round(s.value/max*100)}%</div>
        </div>
      ))}
    </div>
  );
}

// ─── Alerts ─────────────────────────────────────────────────────────────

function AlertsList() {
  const alerts = [
    { kind:'warning', title:'Бюджет на май использован на 62%', body:'Прогноз перерасхода — нет', time:'12 мин назад', icon:<IconAlert size={14}/> },
    { kind:'info',    title:'24 чека на сверке',                 body:'Из них 3 с низким OCR-скором',  time:'42 мин назад', icon:<IconInfo size={14}/> },
    { kind:'success', title:'Правило r_003 прошло порог',         body:'Конверсия 33.9% — в топ-3',     time:'1 ч назад',    icon:<IconCheck size={14}/> },
    { kind:'info',    title:'Выплата за 1–15 мая ждёт одобрения', body:'4 180 400 ₸ · 312 фармацевтов',    time:'3 ч назад',    icon:<IconFinance size={14}/> },
  ];
  const palette = {
    warning:{bg:'#FEF3C7', fg:'#B45309'}, info:{bg:'#E8EAFE', fg:'#2A2BE2'},
    success:{bg:'#D7F5E4', fg:'#0F8F55'}, danger:{bg:'#FEE2E2', fg:'#B91C1C'},
  };
  return (
    <ul className="flex flex-col gap-2">
      {alerts.map((a,i) => {
        const c = palette[a.kind];
        return (
          <li key={i} className="flex items-start gap-3 p-3 rounded-xl bg-paper-hover">
            <span className="w-8 h-8 rounded-lg flex-none flex items-center justify-center" style={{background:c.bg, color:c.fg}}>{a.icon}</span>
            <div className="flex-1 min-w-0">
              <div className="text-[13px] font-extrabold text-ink-900">{a.title}</div>
              <div className="text-[12px] text-ink-500 font-semibold">{a.body}</div>
              <div className="text-[11px] text-ink-400 font-semibold mt-0.5">{a.time}</div>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

Object.assign(window, { DashboardSection, PageHeader });
