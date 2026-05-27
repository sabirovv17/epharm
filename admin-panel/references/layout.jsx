// Layout — Sidebar (12 sections, grouped) + Topbar + Contract widget at the bottom of the sidebar.

const { useState: useStateLay } = React;

function Sidebar({ active, onSelect, collapsed, onToggle, contractOpen, onContractOpen }) {
  const sections = AD.SECTIONS;
  const groups = sections.reduce((acc, s) => {
    (acc[s.group] = acc[s.group] || []).push(s); return acc;
  }, {});
  const order = ['Обзор','Кампании','Сеть','Операции','Аналитика','Система'];

  return (
    <aside className={`sidebar-bg text-white flex-none flex flex-col relative transition-[width] duration-200 ${collapsed ? 'w-[72px]' : 'w-[260px]'}`}>
      {/* Logo header */}
      <div className={`h-16 flex items-center px-4 gap-3 border-b border-white/5 ${collapsed ? 'justify-center px-2' : ''}`}>
        {collapsed ? (
          <button onClick={onToggle} title="Развернуть" className="w-10 h-10 rounded-xl bg-white/8 hover:bg-white/15 flex items-center justify-center flex-none transition">
            <svg viewBox="0 0 64 64" width="22" height="22">
              <path d="M16 12a3 3 0 0 1 3-3h26a3 3 0 0 1 3 3v40l-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3z" fill="#FFFFFF" stroke="#16C97A" strokeWidth="3" strokeLinejoin="round"/>
              <rect x="22" y="32" width="20" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
              <rect x="22" y="38" width="14" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
              <rect x="22" y="44" width="17" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
              <g transform="translate(32 14)">
                <circle r="11" fill="#2A2BE2"/>
                <rect x="-1.5" y="-7" width="3" height="14" rx="0.8" fill="#FFFFFF"/>
                <rect x="-7" y="-1.5" width="14" height="3" rx="0.8" fill="#FFFFFF"/>
              </g>
            </svg>
          </button>
        ) : (
          <>
            <span className="w-9 h-9 rounded-xl bg-white/8 flex items-center justify-center flex-none">
              <svg viewBox="0 0 64 64" width="22" height="22">
                <path d="M16 12a3 3 0 0 1 3-3h26a3 3 0 0 1 3 3v40l-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3z" fill="#FFFFFF" stroke="#16C97A" strokeWidth="3" strokeLinejoin="round"/>
                <rect x="22" y="32" width="20" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
                <rect x="22" y="38" width="14" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
                <rect x="22" y="44" width="17" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55"/>
                <g transform="translate(32 14)">
                  <circle r="11" fill="#2A2BE2"/>
                  <rect x="-1.5" y="-7" width="3" height="14" rx="0.8" fill="#FFFFFF"/>
                  <rect x="-7" y="-1.5" width="14" height="3" rx="0.8" fill="#FFFFFF"/>
                </g>
              </svg>
            </span>
            <div className="flex-1 min-w-0">
              <div className="text-[15px] font-extrabold tracking-tight leading-tight">
                Pharma<span className="text-brand-green-400">Pay</span>
              </div>
              <div className="text-[11px] uppercase tracking-[0.08em] text-white/45 font-bold">Console · HQ</div>
            </div>
            <button onClick={onToggle} title="Свернуть" className="w-7 h-7 rounded-md hover:bg-white/10 flex items-center justify-center text-white/55">
              <IconChevLeft size={16}/>
            </button>
          </>
        )}
      </div>

      {/* Floating expand tab — only visible when collapsed, sits on outer edge */}
      {collapsed && (
        <button
          onClick={onToggle}
          title="Развернуть сайдбар"
          className="absolute top-1/2 -right-3 -translate-y-1/2 w-6 h-12 rounded-r-lg bg-ink-900 hover:bg-ink-800 border border-l-0 border-white/10 text-white/70 hover:text-white flex items-center justify-center z-10 shadow-elevated"
          style={{boxShadow:'4px 0 12px rgba(15,20,36,0.18)'}}
        >
          <IconChevRight size={14}/>
        </button>
      )}

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto scrollbar-thin py-3">
        {order.map(g => groups[g] && (
          <div key={g} className="mb-3">
            {!collapsed && <div className="px-4 mb-1.5 text-[10px] uppercase tracking-[0.1em] font-bold text-white/40">{g}</div>}
            <ul className="px-2 flex flex-col gap-0.5">
              {groups[g].map(s => {
                const Icon = s.Icon;
                const isActive = active === s.id;
                return (
                  <li key={s.id}>
                    <button
                      onClick={() => onSelect(s.id)}
                      className={`w-full h-10 rounded-lg flex items-center gap-3 px-2.5 sidebar-hover ${isActive ? 'sidebar-active text-white' : 'text-white/75'} ${collapsed ? 'justify-center' : ''}`}
                      title={collapsed ? s.label : ''}
                    >
                      <span className={isActive ? 'text-brand-green-400' : 'text-white/65'}><Icon size={20}/></span>
                      {!collapsed && (
                        <>
                          <span className="flex-1 text-left text-[14px] font-semibold truncate">{s.label}</span>
                          {s.badge && (
                            <span className={`text-[10px] font-bold px-1.5 h-[18px] inline-flex items-center rounded-full ${s.badge==='!' ? 'bg-accent-danger text-white' : 'bg-white/12 text-white/80'}`}>
                              {s.badge}
                            </span>
                          )}
                        </>
                      )}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>

      {/* Contract widget */}
      <div className="p-3 border-t border-white/5">
        {collapsed ? (
          <button onClick={onContractOpen} className="w-full h-10 rounded-lg bg-white/5 hover:bg-white/10 flex items-center justify-center text-brand-green-400">
            <IconShield size={18}/>
          </button>
        ) : (
          <button onClick={onContractOpen} className="w-full text-left rounded-xl bg-gradient-to-br from-brand-green-700/40 to-brand-green-700/10 border border-white/5 p-3 hover:from-brand-green-700/55 transition">
            <div className="flex items-center gap-2 mb-2">
              <span className="w-7 h-7 rounded-lg bg-brand-green-600 flex items-center justify-center"><IconShield size={14}/></span>
              <div className="flex-1 min-w-0">
                <div className="text-[11px] font-bold text-white/55 uppercase tracking-[0.06em]">Активный контракт</div>
                <div className="text-[13px] font-extrabold text-white truncate">{AD.CONTRACT.brand}</div>
              </div>
              <IconChevRight size={14} className="text-white/40"/>
            </div>
            <div className="flex items-center justify-between text-[11px] text-white/60 font-semibold mb-1.5">
              <span>{AD.CONTRACT.brandsCount} бренда · бюджет</span>
              <span className="text-white num">{Math.round(AD.CONTRACT.budgetUsed/AD.CONTRACT.budgetTotal*100)}%</span>
            </div>
            <div className="h-1.5 rounded-full bg-white/10 overflow-hidden">
              <div className="h-full bg-brand-green-400" style={{width: `${AD.CONTRACT.budgetUsed/AD.CONTRACT.budgetTotal*100}%`}}/>
            </div>
            <div className="text-[11px] text-white/45 mt-1.5 num">{AD.fmtKzt(AD.CONTRACT.budgetUsed)} / {AD.fmtKzt(AD.CONTRACT.budgetTotal)}</div>
          </button>
        )}
      </div>
    </aside>
  );
}

// ─── Topbar ──────────────────────────────────────────────────────────────

function Topbar({ section, sectionLabel, role, onRoleSwitch, onMenu, onCommand }) {
  return (
    <header className="topbar-bg h-16 flex-none flex items-center px-6 gap-4 sticky top-0 z-30">
      <div className="flex items-center gap-2 min-w-0">
        <button onClick={onMenu} className="w-9 h-9 rounded-lg hover:bg-ink-100 text-ink-500 flex items-center justify-center md:hidden">
          <IconCommand size={18}/>
        </button>
        <div className="flex items-center gap-1.5 text-[13px] text-ink-500">
          <span>HQ</span>
          <IconChevRight size={12}/>
          <span className="text-ink-900 font-bold">{sectionLabel}</span>
        </div>
      </div>

      <div className="flex-1 max-w-[480px] mx-auto hidden md:block">
        <button onClick={onCommand} className="w-full h-9 rounded-lg bg-paper-input hover:bg-ink-100 flex items-center gap-2 px-3 text-ink-500 text-[13px] font-semibold transition">
          <IconSearch size={15}/>
          <span className="flex-1 text-left">Найти правило, аптеку, фармацевта…</span>
          <span className="kbd">⌘</span><span className="kbd">K</span>
        </button>
      </div>

      <div className="flex items-center gap-1.5 ml-auto">
        <button className="h-9 px-3 rounded-lg hover:bg-ink-100 text-[13px] font-bold text-ink-700 flex items-center gap-2">
          <IconCalendar size={15}/>
          <span>Май 2026</span>
          <IconChevDown size={13}/>
        </button>
        <span className="w-px h-5 bg-ink-200 mx-1"/>
        <IconButton tip="Уведомления"><span className="relative"><IconBell size={18}/><span className="absolute -top-1 -right-1 w-2 h-2 bg-accent-danger rounded-full ring-2 ring-white"/></span></IconButton>
        <IconButton tip="История"><IconHistory size={18}/></IconButton>
        <span className="w-px h-5 bg-ink-200 mx-1"/>
        <button onClick={onRoleSwitch} className="h-10 pl-1 pr-3 rounded-xl hover:bg-ink-100 flex items-center gap-2.5 transition">
          <Avatar name={role.name} size={32}/>
          <div className="text-left hidden sm:block">
            <div className="text-[13px] font-extrabold text-ink-900 leading-tight">{role.name}</div>
            <div className="text-[11px] text-ink-500 font-semibold leading-tight">{role.role} · {role.company}</div>
          </div>
          <IconChevDown size={14} className="text-ink-400 hidden sm:block"/>
        </button>
      </div>
    </header>
  );
}

// ─── Role switcher ───────────────────────────────────────────────────────

function RoleSwitcher({ open, onClose, current, onPick }) {
  return (
    <Modal open={open} onClose={onClose} title="Сменить роль" subtitle="Только для демо — в проде роли назначаются админом" width={460}
      footer={<Button variant="ghost" onClick={onClose}>Закрыть</Button>}>
      <div className="flex flex-col gap-2">
        {Object.values(AD.USERS).map(u => (
          <button key={u.id} onClick={() => { onPick(u); onClose(); }}
            className={`flex items-center gap-3 p-3 rounded-xl border text-left transition ${current.id===u.id?'border-brand-green-600 bg-brand-green-50':'border-ink-200 hover:bg-paper-hover'}`}>
            <Avatar name={u.name} size={40}/>
            <div className="flex-1">
              <div className="text-[14px] font-extrabold text-ink-900">{u.name}</div>
              <div className="text-[12px] text-ink-500 font-semibold">{u.role} · {u.company}</div>
            </div>
            {current.id===u.id && <span className="text-brand-green-600"><IconCheck size={18}/></span>}
          </button>
        ))}
      </div>
    </Modal>
  );
}

// ─── Contract details modal ──────────────────────────────────────────────

function ContractModal({ open, onClose }) {
  const C = AD.CONTRACT;
  const used = Math.round(C.budgetUsed/C.budgetTotal*100);
  const reviewer = AD.USERS[C.approvedBy];
  return (
    <Modal open={open} onClose={onClose} title={`Контракт · ${C.brand}`} subtitle={C.period} width={620}
      footer={<><Button variant="ghost" onClick={onClose}>Закрыть</Button><Button leading={<IconExternal size={14}/>}>Открыть полный документ</Button></>}>
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-3 gap-3">
          <Metric label="Бюджет всего"   value={AD.fmtKzt(C.budgetTotal)} accent="ink"/>
          <Metric label="Использовано"   value={AD.fmtKzt(C.budgetUsed)}  accent="green" meta={`${used}% от лимита`}/>
          <Metric label="Осталось"       value={AD.fmtKzt(C.budgetTotal - C.budgetUsed)} accent="blue"/>
        </div>
        <div className="card-soft p-4">
          <div className="text-[12px] font-bold text-ink-500 uppercase tracking-[0.06em] mb-2">Бренды в контракте</div>
          <div className="flex flex-wrap gap-2">
            {C.brands.map(b => <span key={b} className="chip chip-green">{b}</span>)}
          </div>
        </div>
        <div className="card-soft p-4 grid grid-cols-2 gap-3 text-[13px]">
          <div>
            <div className="text-[11px] font-bold text-ink-400 uppercase">Подписан</div>
            <div className="font-extrabold text-ink-900 mt-0.5">{C.signedAt}</div>
          </div>
          <div>
            <div className="text-[11px] font-bold text-ink-400 uppercase">Утверждено категорийным менеджером</div>
            <div className="flex items-center gap-2 mt-0.5">
              <Avatar name={reviewer.name} size={20}/>
              <span className="font-extrabold text-ink-900">{reviewer.name}</span>
            </div>
          </div>
          <div>
            <div className="text-[11px] font-bold text-ink-400 uppercase">Условия</div>
            <div className="text-ink-700 mt-0.5">Бонус фармацевту с каждой принятой рекомендации; cap 1500 ₸ / упаковка.</div>
          </div>
          <div>
            <div className="text-[11px] font-bold text-ink-400 uppercase">Распределение</div>
            <div className="text-ink-700 mt-0.5">312 аптек, пилот + развёрнутая выборка. Контрольная группа исключена.</div>
          </div>
        </div>
      </div>
    </Modal>
  );
}

// ─── Command palette ─────────────────────────────────────────────────────

function CommandPalette({ open, onClose, onNav }) {
  const [q, setQ] = useStateLay('');
  if (!open) return null;
  const items = [];
  AD.SECTIONS.forEach(s => items.push({ kind:'section', label:s.label, sub:s.group, id:s.id, icon: <s.Icon size={16}/> }));
  AD.PRODUCT_LIBRARY.slice(0,8).forEach(p => items.push({ kind:'product', label:p.name, sub:`${p.brand} · ${AD.fmtKzt(p.price)}`, id:p.id, icon:<IconBox size={16}/> }));
  AD.PHARMACY_LIST.slice(0,6).forEach(p => items.push({ kind:'pharmacy', label:p.name, sub:`${p.city} · ${p.addr}`, id:p.id, icon:<IconPharmacy size={16}/> }));
  const filtered = q ? items.filter(i => (i.label+' '+i.sub).toLowerCase().includes(q.toLowerCase())) : items.slice(0,12);
  return (
    <div className="fixed inset-0 z-[85] flex items-start justify-center pt-[12vh] px-4 scrim" onClick={onClose}>
      <div className="w-full max-w-[560px] card shadow-elevated overflow-hidden slide-in" onClick={e=>e.stopPropagation()}>
        <div className="flex items-center gap-2 px-3 h-12 border-b hairline">
          <IconSearch size={16} className="text-ink-400"/>
          <input autoFocus value={q} onChange={e=>setQ(e.target.value)} placeholder="Что найти…" className="flex-1 outline-none text-[14px] font-semibold text-ink-900 bg-transparent"/>
          <span className="kbd">esc</span>
        </div>
        <div className="max-h-[50vh] overflow-auto scrollbar-thin py-2">
          {filtered.length === 0 ? (
            <div className="text-center text-ink-400 text-[13px] py-8">Ничего не найдено</div>
          ) : filtered.map(it => (
            <button key={it.kind+it.id} onClick={() => { if (it.kind==='section') onNav(it.id); onClose(); }}
              className="w-full flex items-center gap-3 px-3 py-2 hover:bg-paper-hover text-left">
              <span className="w-7 h-7 rounded-md bg-ink-100 text-ink-500 flex items-center justify-center flex-none">{it.icon}</span>
              <div className="flex-1 min-w-0">
                <div className="text-[14px] font-bold text-ink-900 truncate">{it.label}</div>
                <div className="text-[12px] text-ink-500 truncate">{it.sub}</div>
              </div>
              <span className="text-[11px] font-bold text-ink-400 uppercase">{it.kind==='section'?'Раздел':it.kind==='product'?'Товар':'Аптека'}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { Sidebar, Topbar, RoleSwitcher, ContractModal, CommandPalette });
