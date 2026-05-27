// Shared UI primitives for the admin console.
// All components reach colors / shadows from the Tailwind config in PharmaPay Admin.html.

const { useState, useEffect, useRef, useMemo, createContext, useContext } = React;

// ─── Buttons ──────────────────────────────────────────────────────────────
function Button({ variant='primary', size='md', leading, trailing, children, className='', ...rest }) {
  const cls = `btn btn-${size} btn-${variant} ${className}`;
  return (
    <button className={cls} {...rest}>
      {leading}
      <span>{children}</span>
      {trailing}
    </button>
  );
}

function IconButton({ tip, children, className='', ...rest }) {
  const body = (
    <button className={`btn btn-md btn-ghost btn-icon ${className}`} {...rest}>
      {children}
    </button>
  );
  if (!tip) return body;
  return <span className="tip inline-flex">{body}<span className="tip-body">{tip}</span></span>;
}

// ─── Inputs ───────────────────────────────────────────────────────────────
function Input({ leading, trailing, className='', ...rest }) {
  if (!leading && !trailing) return <input className={`inp ${className}`} {...rest}/>;
  return (
    <div className="relative">
      {leading && <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-ink-400 pointer-events-none">{leading}</span>}
      <input className={`inp ${leading ? 'inp-search' : ''} ${trailing ? 'pr-10' : ''} ${className}`} {...rest}/>
      {trailing && <span className="absolute inset-y-0 right-0 pr-2 flex items-center">{trailing}</span>}
    </div>
  );
}

function SearchInput({ value, onChange, placeholder='Поиск', className='' }) {
  return (
    <Input leading={<IconSearch size={16}/>} value={value} onChange={e=>onChange(e.target.value)} placeholder={placeholder} className={className}/>
  );
}

function Field({ label, hint, error, children, optional }) {
  return (
    <label className="block">
      {label && <span className="block text-[12px] font-semibold text-ink-700 mb-1.5">{label}{optional && <span className="ml-1 font-medium text-ink-400">— необязательно</span>}</span>}
      {children}
      {hint && !error && <span className="block text-[12px] text-ink-400 mt-1">{hint}</span>}
      {error && <span className="block text-[12px] text-accent-danger mt-1">{error}</span>}
    </label>
  );
}

// ─── Select ───────────────────────────────────────────────────────────────
function Select({ value, onChange, options, placeholder='Выбрать', className='' }) {
  return (
    <div className={`relative ${className}`}>
      <select
        value={value || ''}
        onChange={e => onChange(e.target.value)}
        className="inp appearance-none pr-9 cursor-pointer"
      >
        {!value && <option value="" disabled>{placeholder}</option>}
        {options.map(o => (
          <option key={typeof o==='string'?o:o.value} value={typeof o==='string'?o:o.value}>
            {typeof o==='string'?o:o.label}
          </option>
        ))}
      </select>
      <span className="absolute inset-y-0 right-0 pr-2.5 flex items-center text-ink-400 pointer-events-none"><IconChevDown size={16}/></span>
    </div>
  );
}

// ─── Toggle ───────────────────────────────────────────────────────────────
function Toggle({ on, onChange, label }) {
  const t = (
    <span className={`toggle ${on ? 'on' : ''}`} role="switch" aria-checked={on} onClick={()=>onChange(!on)} />
  );
  if (!label) return t;
  return (
    <label className="inline-flex items-center gap-2 cursor-pointer select-none">
      {t}
      <span className="text-[13px] font-semibold text-ink-700">{label}</span>
    </label>
  );
}

// ─── Tabs ─────────────────────────────────────────────────────────────────
function Tabs({ items, value, onChange, trailing }) {
  return (
    <div className="flex items-end justify-between border-b hairline border-b">
      <div className="flex items-center gap-7">
        {items.map(it => (
          <button key={it.value} className={`tab ${value===it.value?'active':''}`} onClick={()=>onChange(it.value)}>
            <span>{it.label}</span>
            {typeof it.count === 'number' && (
              <span className={`text-[11px] font-bold px-1.5 h-[18px] inline-flex items-center rounded-full ${value===it.value?'bg-brand-green-100 text-brand-green-700':'bg-ink-100 text-ink-500'}`}>{it.count}</span>
            )}
          </button>
        ))}
      </div>
      {trailing && <div className="pb-2">{trailing}</div>}
    </div>
  );
}

// ─── Chips & status ───────────────────────────────────────────────────────
function StatusChip({ status }) {
  const map = {
    active:   { cls:'chip-green', dot:'#16C97A', label:'Активно' },
    paused:   { cls:'chip-amber', dot:'#F1B416', label:'Пауза'   },
    draft:    { cls:'chip-ink',   dot:'#9098A6', label:'Черновик'},
    archived: { cls:'chip-ink',   dot:'#9098A6', label:'Архив'   },
    pending:  { cls:'chip-blue',  dot:'#2A2BE2', label:'На согласовании' },
    rejected: { cls:'chip-red',   dot:'#E5484D', label:'Отклонено' },
    approved: { cls:'chip-green', dot:'#16C97A', label:'Утверждено' },
  };
  const m = map[status] || map.draft;
  return <span className={`chip ${m.cls}`}><span className="chip-dot" style={{background:m.dot}}/> {m.label}</span>;
}

// ─── Avatar ───────────────────────────────────────────────────────────────
function Avatar({ name, size=32, src }) {
  const initials = (name||'??').split(/\s+/).slice(0,2).map(s=>s[0]?.toUpperCase()).join('');
  const hash = [...(name||'')].reduce((a,c)=>a+c.charCodeAt(0),0);
  const palette = ['#16C97A','#2A2BE2','#F4B73A','#8B5CF6','#0F8F55','#3F47F0','#E5484D','#0F1424'];
  const bg = palette[hash % palette.length];
  return (
    <span className="inline-flex items-center justify-center rounded-full text-white font-bold flex-none" style={{width:size, height:size, background:src?'transparent':bg, fontSize:size*0.4}}>
      {src ? <img src={src} className="w-full h-full rounded-full object-cover"/> : initials}
    </span>
  );
}

// ─── Modal ────────────────────────────────────────────────────────────────
function Modal({ open, onClose, title, subtitle, children, footer, width=520 }) {
  useEffect(() => {
    if (!open) return;
    const k = e => e.key === 'Escape' && onClose && onClose();
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[80] flex items-center justify-center p-6 scrim" onClick={onClose}>
      <div className="card shadow-elevated w-full slide-in" style={{maxWidth:width}} onClick={e=>e.stopPropagation()}>
        <div className="flex items-start justify-between px-5 pt-5 pb-3 border-b hairline">
          <div>
            <div className="text-[16px] font-extrabold text-ink-900">{title}</div>
            {subtitle && <div className="text-[13px] text-ink-500 mt-0.5">{subtitle}</div>}
          </div>
          <IconButton onClick={onClose} tip="Esc"><IconClose size={18}/></IconButton>
        </div>
        <div className="px-5 py-4 max-h-[70vh] overflow-auto scrollbar-thin">{children}</div>
        {footer && <div className="px-5 py-3 border-t hairline flex items-center justify-end gap-2 bg-paper-hover rounded-b-2xl">{footer}</div>}
      </div>
    </div>
  );
}

// ─── Toast ────────────────────────────────────────────────────────────────
const ToastCtx = createContext({ push:()=>{} });
function ToastHost({ children }) {
  const [items, setItems] = useState([]);
  const push = (msg, opts={}) => {
    const id = Math.random().toString(36).slice(2);
    setItems(s => [...s, { id, msg, kind: opts.kind||'success', action: opts.action }]);
    setTimeout(() => setItems(s => s.filter(x => x.id !== id)), opts.duration || 3200);
  };
  return (
    <ToastCtx.Provider value={{push}}>
      {children}
      <div className="fixed bottom-5 left-1/2 -translate-x-1/2 z-[90] flex flex-col gap-2 items-center">
        {items.map(it => (
          <div key={it.id} className="card shadow-elevated px-4 py-2.5 flex items-center gap-3 slide-in min-w-[280px]">
            <span className={`chip-dot`} style={{background: it.kind==='error'?'#E5484D':it.kind==='info'?'#2A2BE2':'#16C97A'}}/>
            <span className="text-[14px] font-semibold text-ink-900 flex-1">{it.msg}</span>
            {it.action && <button className="text-[13px] font-bold text-brand-green-700" onClick={it.action.onClick}>{it.action.label}</button>}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}
const useToast = () => useContext(ToastCtx);

// ─── Metric card ──────────────────────────────────────────────────────────
function Metric({ label, value, sub, delta, icon, accent='green', meta }) {
  const tint = {
    green: { bg:'#EDFBF3', fg:'#0F8F55' },
    blue:  { bg:'#E8EAFE', fg:'#2A2BE2' },
    amber: { bg:'#FEF3C7', fg:'#B45309' },
    purple:{ bg:'#F3E8FF', fg:'#7C3AED' },
    ink:   { bg:'#EEF0F5', fg:'#0F1424' },
  }[accent];
  return (
    <div className="card p-5 flex flex-col gap-3">
      <div className="flex items-start justify-between">
        <span className="text-[13px] font-semibold text-ink-500">{label}</span>
        {icon && <span className="w-9 h-9 rounded-xl flex items-center justify-center" style={{background:tint.bg, color:tint.fg}}>{icon}</span>}
      </div>
      <div className="flex items-baseline gap-2">
        <span className="text-[28px] font-extrabold text-ink-900 leading-none num tracking-tight">{value}</span>
        {sub && <span className="text-[14px] font-semibold text-ink-500">{sub}</span>}
      </div>
      <div className="flex items-center justify-between">
        {meta && <span className="text-[12px] font-semibold text-ink-500">{meta}</span>}
        {delta != null && (
          <span className={`chip ${delta>=0 ? 'chip-green' : 'chip-red'} ml-auto`}>
            {delta>=0 ? <IconArrowUp size={11}/> : <IconArrowDown size={11}/>}
            {Math.abs(delta).toFixed(1)}%
          </span>
        )}
      </div>
    </div>
  );
}

// ─── Section card ─────────────────────────────────────────────────────────
function SectionCard({ title, subtitle, action, children, padded=true, className='' }) {
  return (
    <div className={`card ${className}`}>
      {(title || action) && (
        <div className="flex items-center justify-between px-5 py-4 border-b hairline">
          <div>
            {title && <div className="text-[15px] font-extrabold text-ink-900">{title}</div>}
            {subtitle && <div className="text-[13px] text-ink-500 mt-0.5">{subtitle}</div>}
          </div>
          {action}
        </div>
      )}
      <div className={padded ? 'p-5' : ''}>{children}</div>
    </div>
  );
}

// ─── Progress / sparkline ─────────────────────────────────────────────────
function ProgressBar({ value, max=100, color='#16C97A', height=6 }) {
  return (
    <div className="w-full rounded-full bg-ink-100 overflow-hidden" style={{height}}>
      <div style={{width:`${Math.min(100, (value/max)*100)}%`, height:'100%', background:color, borderRadius:999, transition:'width 240ms ease'}}/>
    </div>
  );
}

function Sparkline({ values, color='#16C97A', width=120, height=36, fill=true }) {
  if (!values || values.length===0) return null;
  const max = Math.max(...values), min = Math.min(...values);
  const rng = max - min || 1;
  const stepX = width / (values.length-1 || 1);
  const pts = values.map((v,i) => [i*stepX, height - ((v-min)/rng) * (height-4) - 2]);
  const d = pts.map((p,i) => `${i?'L':'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
  const area = `${d} L ${width},${height} L 0,${height} Z`;
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
      {fill && <path d={area} fill={color} opacity="0.12"/>}
      <path d={d} stroke={color} strokeWidth="1.8" fill="none" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}

// ─── Empty state ──────────────────────────────────────────────────────────
function Empty({ title, body, action, icon }) {
  return (
    <div className="flex flex-col items-center text-center px-6 py-12 gap-3">
      {icon && <span className="w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 flex items-center justify-center">{icon}</span>}
      <div className="text-[15px] font-extrabold text-ink-900">{title}</div>
      {body && <div className="text-[13px] text-ink-500 max-w-[340px]">{body}</div>}
      {action}
    </div>
  );
}

// ─── Coming soon (for lighter sections) ───────────────────────────────────
function ComingSoonBanner({ title='В разработке', body }) {
  return (
    <div className="card p-4 flex items-center gap-3 bg-brand-blue-100/40 border-brand-blue-200/60">
      <span className="w-9 h-9 rounded-xl bg-brand-blue-100 text-brand-blue-600 flex items-center justify-center"><IconInfo size={18}/></span>
      <div className="flex-1">
        <div className="text-[13px] font-extrabold text-ink-900">{title}</div>
        {body && <div className="text-[12px] text-ink-500">{body}</div>}
      </div>
    </div>
  );
}

// ─── Right-side drawer ────────────────────────────────────────────────────
function Drawer({ open, onClose, title, subtitle, children, footer, width=520 }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[70]" onClick={onClose}>
      <div className="absolute inset-0 scrim"/>
      <div className="absolute right-0 top-0 bottom-0 bg-white shadow-elevated flex flex-col slide-in" style={{width}} onClick={e=>e.stopPropagation()}>
        <div className="flex items-start justify-between px-5 pt-5 pb-4 border-b hairline">
          <div className="flex-1 min-w-0">
            <div className="text-[16px] font-extrabold text-ink-900">{title}</div>
            {subtitle && <div className="text-[13px] text-ink-500 mt-0.5 truncate">{subtitle}</div>}
          </div>
          <IconButton onClick={onClose} tip="Закрыть"><IconClose size={18}/></IconButton>
        </div>
        <div className="flex-1 overflow-auto scrollbar-thin px-5 py-4">{children}</div>
        {footer && <div className="px-5 py-3 border-t hairline bg-paper-hover flex items-center justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}

Object.assign(window, {
  Button, IconButton, Input, SearchInput, Field, Select, Toggle, Tabs,
  StatusChip, Avatar, Modal, ToastHost, useToast, Metric, SectionCard,
  ProgressBar, Sparkline, Empty, ComingSoonBanner, Drawer,
});
