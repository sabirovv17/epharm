// Rules Engine — the headline section. Manages substitution & cross-sell rules.
// Layout: metrics row → tabs → list (left) + rule builder (right).

const { useState: useStateRE, useMemo: useMemoRE } = React;

function RulesSection() {
  const toast = useToast();

  // Live state — rules are mutable in this prototype
  const [subst, setSubst] = useStateRE(AD.RULES_SUBST.slice());
  const [cross, setCross] = useStateRE(AD.RULES_CROSS.slice());
  const [archive, setArchive] = useStateRE(AD.RULES_ARCHIVE.slice());
  const [tab, setTab] = useStateRE('substitution');
  const [q, setQ] = useStateRE('');
  const [filter, setFilter] = useStateRE('all'); // all | active | paused
  const [selectedId, setSelectedId] = useStateRE(AD.RULES_SUBST[0].id);
  const [createOpen, setCreateOpen] = useStateRE(false);
  const [confirmDel, setConfirmDel] = useStateRE(null);

  const allRules = [...subst, ...cross, ...archive];
  const findRule = (id) => allRules.find(r => r.id === id);
  const selected = findRule(selectedId);

  const setRule = (id, patch) => {
    const apply = (list) => list.map(r => r.id === id ? { ...r, ...patch } : r);
    setSubst(apply); setCross(apply); setArchive(apply);
  };

  const currentList = tab === 'substitution' ? subst : tab === 'crosssell' ? cross : archive;
  const filtered = currentList.filter(r => {
    if (filter !== 'all' && r.status !== filter && tab !== 'archive') return false;
    if (!q) return true;
    const trig = r.trigger.kind === 'product' ? AD.productById(r.trigger.value)?.name : r.trigger.value;
    const rec = AD.productById(r.recommend)?.name || '';
    return `${trig} ${rec}`.toLowerCase().includes(q.toLowerCase());
  });

  const toggleStatus = (r) => {
    const next = r.status === 'active' ? 'paused' : 'active';
    setRule(r.id, { status: next });
    toast.push(`Правило ${next === 'active' ? 'включено' : 'поставлено на паузу'}`, {
      action: { label: 'Отменить', onClick: () => setRule(r.id, { status: r.status }) },
    });
  };

  const archiveRule = (r) => {
    setSubst(s => s.filter(x => x.id !== r.id));
    setCross(s => s.filter(x => x.id !== r.id));
    setArchive(a => [{ ...r, status:'archived' }, ...a]);
    toast.push('Правило отправлено в архив');
    setSelectedId(filtered.find(x => x.id !== r.id)?.id || null);
  };

  const restoreRule = (r) => {
    setArchive(a => a.filter(x => x.id !== r.id));
    if (r.type === 'substitution') setSubst(s => [{ ...r, status:'paused' }, ...s]);
    else setCross(s => [{ ...r, status:'paused' }, ...s]);
    toast.push('Правило восстановлено в «Паузу»');
  };

  const duplicateRule = (r) => {
    const copy = { ...r, id: 'r_' + Math.random().toString(36).slice(2,7), status:'draft', impressions:0, accepts:0, convRate:0, revenue:0, payout:0, updatedAt:'сегодня' };
    if (r.type === 'substitution') setSubst(s => [copy, ...s]);
    else setCross(s => [copy, ...s]);
    setSelectedId(copy.id);
    setTab(r.type === 'substitution' ? 'substitution' : 'crosssell');
    toast.push('Правило продублировано — новая копия в черновиках');
  };

  // Drag reorder (within list)
  const [dragId, setDragId] = useStateRE(null);
  const onDragStart = (id) => setDragId(id);
  const onDragOver = (e, overId) => {
    e.preventDefault();
    if (!dragId || dragId === overId) return;
    const list = tab === 'substitution' ? subst : tab === 'crosssell' ? cross : archive;
    const setter = tab === 'substitution' ? setSubst : tab === 'crosssell' ? setCross : setArchive;
    const from = list.findIndex(r => r.id === dragId);
    const to = list.findIndex(r => r.id === overId);
    if (from < 0 || to < 0) return;
    const next = list.slice();
    const [m] = next.splice(from, 1);
    next.splice(to, 0, m);
    setter(next);
  };

  const metrics = useMemoRE(() => {
    const total = subst.length + cross.length;
    const active = subst.filter(r => r.status==='active').length + cross.filter(r => r.status==='active').length;
    const impressions = subst.reduce((a,r)=>a+r.impressions,0) + cross.reduce((a,r)=>a+r.impressions,0);
    const accepts = subst.reduce((a,r)=>a+r.accepts,0) + cross.reduce((a,r)=>a+r.accepts,0);
    const payout = subst.reduce((a,r)=>a+r.payout,0) + cross.reduce((a,r)=>a+r.payout,0);
    return { active, total, impressions, accepts, payout, conv: impressions?(accepts/impressions*100):0 };
  }, [subst, cross]);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Rules Engine"
        subtitle="Правила замены и кросс-сейл. Триггер срабатывает в чеке — фармацевт получает рекомендацию и бонус."
        actions={<>
          <RulesMoreMenu/>
          <Button variant="primary" size="md" leading={<IconPlus size={14}/>} onClick={() => setCreateOpen(true)}>Новое правило</Button>
        </>}
      />

      {/* Summary bar — quiet single row instead of 4 big KPI tiles */}
      <SummaryBar metrics={metrics}/>

      {/* Main work area: list + builder */}
      <div className="grid gap-4" style={{ gridTemplateColumns: 'minmax(0, 1fr) 480px' }}>
        {/* Left — rules list */}
        <div className="card flex flex-col min-h-[640px]">
          <div className="px-5 pt-4">
            <Tabs
              value={tab}
              onChange={(v)=>{ setTab(v); const first = (v==='substitution'?subst:v==='crosssell'?cross:archive)[0]; if(first) setSelectedId(first.id); }}
              items={[
                { value:'substitution', label:'Замены',     count: subst.length },
                { value:'crosssell',    label:'Кросс-сейл', count: cross.length },
                { value:'archive',      label:'Архив',      count: archive.length },
              ]}
              trailing={
                <div className="flex items-center gap-2">
                  <SearchInput value={q} onChange={setQ} placeholder="По товару, бренду, МНН…" className="w-[240px] !h-9"/>
                  {tab !== 'archive' && (
                    <Select value={filter} onChange={setFilter}
                      options={[{value:'all',label:'Все статусы'},{value:'active',label:'Активные'},{value:'paused',label:'Пауза'}]}
                      className="w-[150px]"/>
                  )}
                </div>
              }
            />
          </div>

          <div className="flex-1 overflow-auto scrollbar-thin">
            {filtered.length === 0 ? (
              <Empty title="Правил пока нет" body={tab==='archive' ? 'Здесь окажутся правила, отправленные в архив.' : 'Создайте первое правило замены — фармацевт начнёт получать подсказки в кассе.'} icon={<IconRules size={26}/>}
                action={tab !== 'archive' && <Button leading={<IconPlus size={14}/>} onClick={() => setCreateOpen(true)}>Новое правило</Button>}/>
            ) : (
              <ul className="divide-hairline">
                {filtered.map(r => (
                  <RuleRow key={r.id} rule={r} selected={selectedId===r.id} onSelect={() => setSelectedId(r.id)}
                    onToggle={() => toggleStatus(r)} onArchive={() => setConfirmDel(r)} onDuplicate={() => duplicateRule(r)} onRestore={() => restoreRule(r)}
                    onDragStart={() => onDragStart(r.id)} onDragOver={(e) => onDragOver(e, r.id)} onDragEnd={() => setDragId(null)}
                    dragging={dragId === r.id}
                  />
                ))}
              </ul>
            )}
          </div>
        </div>

        {/* Right — builder panel */}
        <div className="card flex flex-col min-h-[640px] sticky top-[88px] self-start">
          {selected ? (
            <RuleBuilder
              rule={selected}
              onSave={(patch) => { setRule(selected.id, patch); toast.push('Правило сохранено'); }}
              onToggle={() => toggleStatus(selected)}
              onArchive={() => setConfirmDel(selected)}
              onDuplicate={() => duplicateRule(selected)}
            />
          ) : (
            <Empty title="Выберите правило" body="Список слева — кликните, чтобы открыть конструктор." icon={<IconRules size={26}/>}/>
          )}
        </div>
      </div>

      {/* Modals */}
      <CreateRuleModal open={createOpen} onClose={() => setCreateOpen(false)} onCreate={(r) => {
        if (r.type === 'substitution') { setSubst(s => [r, ...s]); setTab('substitution'); }
        else { setCross(s => [r, ...s]); setTab('crosssell'); }
        setSelectedId(r.id);
        setCreateOpen(false);
        toast.push('Правило создано — в черновиках');
      }}/>

      <Modal open={!!confirmDel} onClose={() => setConfirmDel(null)} title="Отправить правило в архив?"
        subtitle={confirmDel ? `«${ruleSummary(confirmDel)}» перестанет срабатывать в чеках.` : ''}
        footer={<>
          <Button variant="ghost" onClick={() => setConfirmDel(null)}>Отмена</Button>
          <Button variant="danger" onClick={() => { archiveRule(confirmDel); setConfirmDel(null); }} leading={<IconArchive size={14}/>}>В архив</Button>
        </>}/>
    </div>
  );
}

const ruleSummary = (r) => {
  const trig = r.trigger.kind === 'product' ? AD.productById(r.trigger.value)?.name :
               r.trigger.kind === 'product_any' ? r.trigger.value.map(v => AD.productById(v)?.name.split(' ').slice(0,2).join(' ')).join(', ') :
               `МНН: ${r.trigger.value}`;
  const rec = AD.productById(r.recommend)?.name || '';
  return `${trig} → ${rec}`;
};

// ─── Rule row ────────────────────────────────────────────────────────────

function RuleRow({ rule, selected, onSelect, onToggle, onArchive, onDuplicate, onRestore, onDragStart, onDragOver, onDragEnd, dragging }) {
  const trigProduct = rule.trigger.kind === 'product' ? AD.productById(rule.trigger.value) :
                      rule.trigger.kind === 'product_any' ? AD.productById(rule.trigger.value[0]) : null;
  const rec = AD.productById(rule.recommend);
  const isArchive = rule.status === 'archived';

  return (
    <li
      draggable={!isArchive}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDragEnd={onDragEnd}
      onClick={onSelect}
      className={`group flex items-center gap-3 pl-3 pr-4 py-3 cursor-pointer ${selected ? 'bg-brand-green-50/60' : 'hover:bg-paper-hover'} ${dragging ? 'dragging' : ''} relative`}
      style={selected ? { boxShadow: 'inset 3px 0 0 #16C97A' } : {}}
    >
      {!isArchive && (
        <span className="drag-handle text-ink-200 group-hover:text-ink-400 flex items-center transition" onClick={e=>e.stopPropagation()}>
          <IconDrag size={14}/>
        </span>
      )}

      {/* Trigger → recommendation */}
      <ProductBlock product={trigProduct} fallback={rule.trigger}/>
      <span className="self-center text-ink-300 flex-none"><IconArrowRight size={14}/></span>
      <ProductBlock product={rec} accent/>

      {/* Right side — compact: conversion + bonus + status */}
      <div className="flex-none flex items-center gap-5 pl-2">
        <div className="text-right w-14">
          <div className="text-[14px] font-extrabold text-ink-900 num leading-none">{rule.convRate.toFixed(1)}%</div>
          <div className="text-[10px] font-bold text-ink-400 mt-1 uppercase tracking-[0.04em]">конв.</div>
        </div>
        <div className="text-right w-20">
          <div className="text-[13px] font-extrabold text-brand-green-700 num leading-none">+{AD.fmtKzt(rule.bonus)}</div>
          <div className="text-[10px] font-bold text-ink-400 mt-1 uppercase tracking-[0.04em]">бонус</div>
        </div>
        <StatusChip status={rule.status}/>
      </div>
    </li>
  );
}

function ProductBlock({ product, accent, fallback }) {
  if (!product) {
    const name = fallback?.kind === 'mnn' ? `МНН: ${fallback.value}` :
                 fallback?.kind === 'product_any' ? `Группа товаров (${fallback.value.length})` :
                 'Любой триггер';
    return (
      <div className="flex items-center gap-2 flex-1 min-w-0">
        <span className="w-10 h-10 rounded-lg bg-brand-blue-100 text-brand-blue-600 flex items-center justify-center flex-none"><IconLayers size={18}/></span>
        <div className="min-w-0">
          <div className="text-[13px] font-extrabold text-ink-900 truncate">{name}</div>
          <div className="text-[11px] text-ink-500 font-semibold">МНН-группа</div>
        </div>
      </div>
    );
  }
  return (
    <div className="flex items-center gap-2 flex-1 min-w-0">
      <ProductIcon product={product} accent={accent}/>
      <div className="min-w-0">
        <div className="text-[13px] font-extrabold text-ink-900 truncate">{product.name}</div>
        <div className="text-[11px] text-ink-500 font-semibold truncate">{product.brand}</div>
      </div>
    </div>
  );
}

function ProductIcon({ product, accent, size=40 }) {
  // Pseudo-colored package mock based on vendor
  const palette = {
    'Jadran-Galenski':'#16C97A', 'Aurena Labs':'#2A2BE2', 'GlaxoSmithKline':'#F4B73A',
    'Novartis':'#5560FB', 'Bayer':'#E5484D', 'Polpharma':'#8B5CF6',
    'KRKA':'#0F8F55', 'Reckitt':'#B91C1C',
  };
  const color = palette[product.brand] || '#5A6173';
  return (
    <span className={`rounded-lg flex items-center justify-center flex-none ${accent?'ring-2 ring-brand-green-400/40':''}`} style={{width:size, height:size, background: color+'22', color}}>
      <IconBox size={size*0.45}/>
    </span>
  );
}

// ─── Rule builder ────────────────────────────────────────────────────────

function RuleBuilder({ rule, onSave, onToggle, onArchive, onDuplicate }) {
  const [local, setLocal] = useStateRE(rule);
  const [tab, setTab] = useStateRE('builder');

  // Reset local when rule changes
  React.useEffect(() => { setLocal(rule); setTab('builder'); }, [rule.id]);

  const dirty = JSON.stringify(local) !== JSON.stringify(rule);

  const isArchive = local.status === 'archived';

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-5 pt-4 pb-3 border-b hairline">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-400 mb-1">{local.type === 'substitution' ? 'Замена' : 'Кросс-сейл'} · {local.id} · {local.pharmacies} аптек · обновлено {local.updatedAt}</div>
            <div className="text-[16px] font-extrabold text-ink-900 truncate">{ruleSummary(local)}</div>
          </div>
          {!isArchive && (
            <div className="flex items-center gap-2 flex-none">
              <Toggle on={local.status==='active'} onChange={onToggle}/>
              <span className="text-[12px] font-bold text-ink-700">{local.status==='active'?'Активно':'Пауза'}</span>
            </div>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="px-5">
        <Tabs value={tab} onChange={setTab} items={[
          { value:'builder',   label:'Конструктор' },
          { value:'analytics', label:'Аналитика' },
          { value:'preview',   label:'Превью' },
        ]}/>
      </div>

      <div className="flex-1 overflow-auto scrollbar-thin px-5 py-4">
        {tab === 'builder' && <BuilderForm value={local} onChange={setLocal}/>}
        {tab === 'analytics' && <RuleAnalytics rule={local}/>}
        {tab === 'preview' && <RulePreview rule={local}/>}
      </div>

      {/* Footer */}
      {tab === 'builder' && (
        <div className="px-5 py-3 border-t hairline bg-paper-hover flex items-center gap-2 rounded-b-2xl">
          {dirty && <span className="chip chip-amber">Есть несохранённые изменения</span>}
          <Button variant="ghost" size="md" className="ml-auto" disabled={!dirty} onClick={() => setLocal(rule)}>Отмена</Button>
          <Button variant="primary" size="md" disabled={!dirty} onClick={() => onSave(local)} leading={<IconCheck size={14}/>}>Сохранить</Button>
        </div>
      )}
    </div>
  );
}

// ─── Builder form ────────────────────────────────────────────────────────

function BuilderForm({ value, onChange }) {
  const v = value;
  const set = (patch) => onChange({ ...v, ...patch });
  const productOptions = AD.PRODUCT_LIBRARY.map(p => ({ value:p.id, label:`${p.name} · ${p.brand}` }));
  const mnnOptions = ['Морская вода','Ксилометазолин','Оксиметазолин','Парацетамол','Ибупрофен','Лоратадин','Амилметакрезол'];

  return (
    <div className="flex flex-col gap-5">
      {/* TRIGGER */}
      <FormBlock step={1} title="Триггер" subtitle="Что должно появиться в чеке, чтобы правило сработало" color="blue">
        <div className="flex gap-2 mb-3">
          {[
            { val:'product', label:'Конкретный товар', icon:<IconBox size={14}/> },
            { val:'mnn',     label:'МНН-группа',       icon:<IconLayers size={14}/> },
            { val:'product_any', label:'Группа товаров', icon:<IconStack size={14}/> },
          ].map(t => (
            <button key={t.val} onClick={() => set({ trigger: { kind: t.val, value: t.val === 'product_any' ? [] : '' } })}
              className={`flex-1 h-11 rounded-lg border text-[13px] font-bold flex items-center justify-center gap-2 transition ${v.trigger.kind===t.val?'border-brand-green-600 bg-brand-green-50 text-brand-green-700':'border-ink-200 text-ink-700 hover:bg-paper-hover'}`}>
              {t.icon}{t.label}
            </button>
          ))}
        </div>
        {v.trigger.kind === 'product' && (
          <Field label="Товар-триггер">
            <Select value={v.trigger.value} onChange={val => set({ trigger:{...v.trigger, value:val} })} options={productOptions} placeholder="Выберите товар"/>
          </Field>
        )}
        {v.trigger.kind === 'mnn' && (
          <Field label="МНН (международное непатентованное название)" hint="Правило сработает на любой товар с этим МНН, кроме рекомендуемого">
            <Select value={v.trigger.value} onChange={val => set({ trigger:{...v.trigger, value:val} })} options={mnnOptions} placeholder="Выберите МНН"/>
          </Field>
        )}
        {v.trigger.kind === 'product_any' && (
          <Field label="Любой из товаров">
            <div className="flex flex-wrap gap-1.5 p-2 rounded-lg border hairline bg-paper-input min-h-[42px]">
              {(v.trigger.value||[]).map(id => (
                <span key={id} className="chip chip-blue">
                  {AD.productById(id)?.name.split(' ').slice(0,3).join(' ')}
                  <button onClick={()=>set({trigger:{...v.trigger, value:v.trigger.value.filter(x=>x!==id)}})}><IconClose size={12}/></button>
                </span>
              ))}
              <Select value="" onChange={(id)=>{ if(id && !v.trigger.value.includes(id)) set({trigger:{...v.trigger, value:[...v.trigger.value, id]}}); }} options={productOptions} placeholder="+ добавить"/>
            </div>
          </Field>
        )}
      </FormBlock>

      {/* RECOMMENDATION */}
      <FormBlock step={2} title="Рекомендация" subtitle="Что фармацевт предложит вместо или в дополнение" color="green">
        <Field label="Рекомендуемый товар">
          <Select value={v.recommend} onChange={val => set({ recommend: val })} options={productOptions}/>
        </Field>
        <Field label="Преимущества (буллеты, появятся в кассе фармацевта)" hint="По одному на строку">
          <textarea className="inp" rows="3" value={(v.advantages||[]).join('\n')} onChange={e=>set({advantages: e.target.value.split('\n').filter(Boolean)})}/>
        </Field>
        <Field label="Скрипт для покупателя" hint="Что сказать клиенту. Появится фармацевту крупно.">
          <textarea className="inp" rows="3" value={v.script || ''} onChange={e=>set({script: e.target.value})}/>
        </Field>
      </FormBlock>

      {/* BONUS */}
      <FormBlock step={3} title="Бонус фармацевту" subtitle="Сколько начислится за каждую принятую рекомендацию" color="amber">
        <div className="grid grid-cols-2 gap-3">
          <Field label="Сумма за упаковку, ₸">
            <Input type="number" value={v.bonus} onChange={e=>set({bonus: Number(e.target.value)||0})}/>
          </Field>
          <Field label="Максимум в день / на 1 фармацевта" hint="0 — без лимита">
            <Input type="number" defaultValue="0" placeholder="0 (без лимита)"/>
          </Field>
        </div>
      </FormBlock>

      {/* META */}
      <FormBlock step={4} title="Дополнительно" subtitle="A/B-тест, сроки, география" color="purple" collapsible defaultCollapsed>
        <div className="grid grid-cols-2 gap-3">
          <Field label="A/B-тест">
            <div className="flex items-center gap-2">
              <Toggle on={!!v.abTest} onChange={(on)=>set({abTest: on?{variant:'A',share:50}:null})}/>
              <span className="text-[13px] font-semibold text-ink-700">{v.abTest?`Вариант ${v.abTest.variant}, ${v.abTest.share}%`:'Выключен'}</span>
            </div>
          </Field>
          <Field label="Действует до">
            <Input type="text" defaultValue="31.05.2026"/>
          </Field>
          <Field label="Только города">
            <Input defaultValue="Все города"/>
          </Field>
          <Field label="Только сети">
            <Input defaultValue="Все сети"/>
          </Field>
        </div>
      </FormBlock>
    </div>
  );
}

function FormBlock({ step, title, subtitle, color, children, collapsible, defaultCollapsed }) {
  const [open, setOpen] = useStateRE(!defaultCollapsed);
  const tint = { blue:{bg:'#E8EAFE',fg:'#2A2BE2'}, green:{bg:'#D7F5E4',fg:'#0F8F55'}, amber:{bg:'#FEF3C7',fg:'#B45309'}, purple:{bg:'#F3E8FF',fg:'#7C3AED'} }[color];
  return (
    <div className="card-soft p-4">
      <div className="flex items-center gap-3 mb-3">
        <span className="w-8 h-8 rounded-lg flex items-center justify-center font-extrabold text-[14px]" style={{background:tint.bg, color:tint.fg}}>{step}</span>
        <div className="flex-1">
          <div className="text-[14px] font-extrabold text-ink-900">{title}</div>
          {subtitle && <div className="text-[12px] text-ink-500 font-semibold">{subtitle}</div>}
        </div>
        {collapsible && (
          <IconButton onClick={()=>setOpen(o=>!o)}>{open ? <IconChevDown size={16}/> : <IconChevRight size={16}/>}</IconButton>
        )}
      </div>
      {open && <div className="flex flex-col gap-3">{children}</div>}
    </div>
  );
}

// ─── Analytics tab ───────────────────────────────────────────────────────

function RuleAnalytics({ rule }) {
  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Metric label="Показано" value={AD.fmt(rule.impressions)} accent="ink" icon={<IconEye size={16}/>}/>
        <Metric label="Принято"  value={AD.fmt(rule.accepts)}     accent="green" icon={<IconCheck size={16}/>} delta={+3.2}/>
        <Metric label="Конверсия" value={`${rule.convRate.toFixed(1)}%`} accent="green"/>
        <Metric label="Выручка"  value={AD.fmtKzt(rule.revenue)}   accent="blue"/>
        <Metric label="Выплачено фармацевтам" value={AD.fmtKzt(rule.payout)} accent="amber"/>
        <Metric label="Аптек участвовало" value={rule.pharmacies} accent="purple"/>
      </div>
      <SectionCard title="Динамика за 14 дней" padded={false}>
        <div className="px-5 py-3"><Sparkline values={rule.spark} width={460} height={120}/></div>
      </SectionCard>
      <SectionCard title="Топ аптек по правилу" padded={false}>
        <ul className="divide-hairline">
          {AD.PHARMACY_LIST.slice(0,5).map((p,i) => (
            <li key={p.id} className="flex items-center gap-3 px-4 py-2.5">
              <span className="text-[11px] font-bold text-ink-400 num w-5">{i+1}</span>
              <div className="flex-1 min-w-0">
                <div className="text-[13px] font-bold text-ink-900 truncate">{p.name}</div>
                <div className="text-[11px] text-ink-500 font-semibold">{p.city}</div>
              </div>
              <span className="text-[13px] font-extrabold text-ink-900 num">{8+i*4}</span>
            </li>
          ))}
        </ul>
      </SectionCard>
    </div>
  );
}

// ─── Preview tab ─────────────────────────────────────────────────────────

function RulePreview({ rule }) {
  const trig = rule.trigger.kind === 'product' ? AD.productById(rule.trigger.value) :
               rule.trigger.kind === 'product_any' ? AD.productById(rule.trigger.value[0]) : null;
  const rec = AD.productById(rule.recommend);
  return (
    <div className="flex flex-col gap-3">
      <ComingSoonBanner title="Как фармацевт увидит подсказку" body="Так выглядит карточка рекомендации на кассе. Тапнув её, фармацевт прочитает скрипт и преимущества."/>
      <div className="card-soft p-4 bg-gradient-to-br from-brand-green-50 to-white border-brand-green-200/60">
        <div className="text-[11px] font-bold uppercase tracking-[0.06em] text-brand-green-700 mb-2 flex items-center gap-1.5"><IconSpark size={12}/>Подсказка PharmaPay</div>
        <div className="text-[16px] font-extrabold text-ink-900 mb-3">{rule.type==='substitution' ? `Замените на ${rec?.name}` : `Предложите ${rec?.name}`}</div>
        <div className="text-[14px] text-ink-700 leading-relaxed bg-white rounded-xl p-3 border hairline mb-3">
          «{rule.script}»
        </div>
        <div className="flex flex-col gap-1.5">
          {(rule.advantages||[]).map((a,i) => (
            <div key={i} className="flex items-start gap-2">
              <span className="w-5 h-5 mt-0.5 rounded-md bg-brand-green-100 text-brand-green-700 flex items-center justify-center flex-none"><IconCheck size={12}/></span>
              <span className="text-[13px] font-semibold text-ink-700">{a}</span>
            </div>
          ))}
        </div>
        <div className="mt-4 pt-3 border-t hairline flex items-center justify-between">
          <div>
            <div className="text-[11px] font-bold uppercase text-ink-400">Бонус фармацевту</div>
            <div className="text-[20px] font-extrabold text-brand-green-700 num">+{AD.fmtKzt(rule.bonus)}</div>
          </div>
          <Button variant="primary" size="md">Принять</Button>
        </div>
      </div>
      {trig && (
        <div className="card-soft p-3 flex items-center gap-3">
          <ProductIcon product={trig} size={36}/>
          <div className="flex-1">
            <div className="text-[12px] font-bold text-ink-400 uppercase">Сработает на товар</div>
            <div className="text-[13px] font-extrabold text-ink-900">{trig.name}</div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Create rule modal ──────────────────────────────────────────────────

function CreateRuleModal({ open, onClose, onCreate }) {
  const [type, setType] = useStateRE('substitution');
  const [step, setStep] = useStateRE(1);
  const [draft, setDraft] = useStateRE({
    type:'substitution', status:'draft',
    trigger:{kind:'product', value:''}, recommend:'', bonus:300,
    script:'', advantages:[], pharmacies:0, impressions:0, accepts:0, convRate:0, revenue:0, payout:0,
    spark:[5,7,6,8,9,8,10,11,12,13,14,13,15,16], updatedAt:'сегодня', createdBy:'damir',
  });
  React.useEffect(() => { if (open) { setStep(1); setType('substitution'); setDraft(d=>({...d, type:'substitution'})); } }, [open]);

  const submit = () => onCreate({ ...draft, type, id:'r_' + Math.random().toString(36).slice(2,7) });

  return (
    <Modal open={open} onClose={onClose} title="Новое правило" subtitle="Шаг за шагом — 30 секунд" width={620}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Отмена</Button>
        {step > 1 && <Button variant="outline" onClick={()=>setStep(s=>s-1)} leading={<IconChevLeft size={14}/>}>Назад</Button>}
        {step < 3 ? <Button onClick={()=>setStep(s=>s+1)} trailing={<IconChevRight size={14}/>}>Далее</Button>
                  : <Button variant="primary" onClick={submit} leading={<IconCheck size={14}/>}>Создать</Button>}
      </>}>
      <div className="flex items-center gap-1.5 mb-5">
        {[1,2,3].map(n => (
          <React.Fragment key={n}>
            <span className={`w-8 h-8 rounded-full flex items-center justify-center text-[13px] font-extrabold ${step>=n?'bg-brand-green-600 text-white':'bg-ink-100 text-ink-400'}`}>{n}</span>
            {n<3 && <span className={`flex-1 h-0.5 ${step>n?'bg-brand-green-600':'bg-ink-100'}`}/>}
          </React.Fragment>
        ))}
      </div>

      {step === 1 && (
        <div className="flex flex-col gap-3">
          <Field label="Тип правила">
            <div className="grid grid-cols-2 gap-3">
              {[
                { v:'substitution', label:'Замена', desc:'Заменяет конкурентный товар на ваш' },
                { v:'crosssell',    label:'Кросс-сейл', desc:'Добавляет ваш товар к другому' },
              ].map(t => (
                <button key={t.v} onClick={()=>{ setType(t.v); setDraft(d=>({...d,type:t.v})); }}
                  className={`p-4 rounded-xl border text-left ${type===t.v?'border-brand-green-600 bg-brand-green-50':'border-ink-200 hover:bg-paper-hover'}`}>
                  <div className="flex items-center gap-2 mb-1.5">
                    {t.v==='substitution' ? <IconSwap size={18}/> : <IconStack size={18}/>}
                    <span className="text-[14px] font-extrabold text-ink-900">{t.label}</span>
                    {type===t.v && <span className="ml-auto text-brand-green-600"><IconCheck size={16}/></span>}
                  </div>
                  <div className="text-[12px] text-ink-500 font-semibold">{t.desc}</div>
                </button>
              ))}
            </div>
          </Field>
        </div>
      )}

      {step === 2 && <BuilderForm value={draft} onChange={setDraft}/>}

      {step === 3 && (
        <div className="flex flex-col gap-3">
          <div className="text-[14px] font-extrabold text-ink-900 mb-1">Проверьте правило</div>
          <RulePreview rule={draft}/>
        </div>
      )}
    </Modal>
  );
}

// ─── Summary bar (replaces 4 big KPI tiles) ─────────────────────────────

function SummaryBar({ metrics }) {
  const items = [
    { label:'Активных правил',      value:`${metrics.active}`, sub:`из ${metrics.total}`, dot:'#16C97A' },
    { label:'Показано',             value: AD.fmt(metrics.impressions), sub:'рекомендаций', dot:'#2A2BE2' },
    { label:'Принято',              value: AD.fmt(metrics.accepts), sub:`${metrics.conv.toFixed(1)}% конверсия`, dot:'#0F8F55', delta:+4.8 },
    { label:'Выплачено фармацевтам',value: AD.fmtKzt(metrics.payout), sub:'62% бюджета мая', dot:'#F4B73A' },
  ];
  return (
    <div className="card flex items-stretch divide-x divide-ink-100 overflow-hidden">
      {items.map(it => (
        <div key={it.label} className="flex-1 px-5 py-3.5 flex items-center gap-3">
          <span className="w-1.5 h-9 rounded-full flex-none" style={{background: it.dot}}/>
          <div className="flex-1 min-w-0">
            <div className="text-[11px] font-bold text-ink-500 uppercase tracking-[0.04em] truncate">{it.label}</div>
            <div className="flex items-baseline gap-2 mt-0.5">
              <span className="text-[19px] font-extrabold text-ink-900 num leading-none">{it.value}</span>
              {it.sub && <span className="text-[12px] font-semibold text-ink-500 truncate">{it.sub}</span>}
            </div>
          </div>
          {it.delta != null && (
            <span className={`chip ${it.delta>=0?'chip-green':'chip-red'} flex-none`}>
              {it.delta>=0 ? <IconArrowUp size={10}/> : <IconArrowDown size={10}/>}
              {Math.abs(it.delta).toFixed(1)}%
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

// ─── "More" menu in page header — secondary actions ─────────────────────

function RulesMoreMenu() {
  const [open, setOpen] = useStateRE(false);
  const items = [
    { label:'Импорт правил из CSV', icon:<IconUpload size={14}/> },
    { label:'Экспорт реестра',      icon:<IconDownload size={14}/> },
    { label:'История версий',       icon:<IconHistory size={14}/> },
    { label:'Запустить ре-расчёт',  icon:<IconRefresh size={14}/> },
  ];
  return (
    <div className="relative">
      <Button variant="outline" size="md" trailing={<IconChevDown size={12}/>} onClick={()=>setOpen(o=>!o)}>Ещё</Button>
      {open && (
        <>
          <div className="fixed inset-0 z-[40]" onClick={()=>setOpen(false)}/>
          <div className="absolute top-full right-0 mt-1.5 w-56 card shadow-elevated p-1 z-[41]">
            {items.map(it => (
              <button key={it.label} className="w-full text-left px-2.5 py-2 rounded-md hover:bg-paper-hover text-[13px] font-semibold flex items-center gap-2.5" onClick={()=>setOpen(false)}>
                <span className="text-ink-500">{it.icon}</span>
                <span>{it.label}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

Object.assign(window, { RulesSection });
