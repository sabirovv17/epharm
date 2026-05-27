// ────────────────────────────────────────────────────────────────────────────
// ui.jsx — Shared UI primitives + platform-aware system chrome
//
// Most components here are platform-agnostic (Logo, ButtonPrimary, GlassPill,
// FilterChip, SearchInput, BottomSheet, Row, PromoCard). They render the same
// way on iOS and Android and use the brand tokens from the Tailwind config in
// PharmaPay.html (and PharmaPay (Android).html — same palette).
//
// Two primitives ARE platform-aware: `StatusBar` and `BottomNav`. Both check
// `window.PLATFORM` at render and switch to the matching system style:
//
//   ┌────────────────┬──────────────────────────┬────────────────────────────┐
//   │                │  iOS variant             │  Android (Material 3)      │
//   ├────────────────┼──────────────────────────┼────────────────────────────┤
//   │ StatusBar      │ SF Pro 17/600, 4-bar     │ Roboto 14/500, signal      │
//   │                │ cellular, 3-arc wifi,    │ triangle, wifi fan,        │
//   │                │ pill battery + fill bar  │ vertical capsule battery   │
//   │                │ (54px tall)              │ (38px tall)                │
//   ├────────────────┼──────────────────────────┼────────────────────────────┤
//   │ BottomNav      │ Flat 4-tab grid, no      │ M3 Navigation Bar — pill   │
//   │                │ pill, colour-only active │ behind active icon,        │
//   │                │ state, 84px + safe area  │ always-visible labels      │
//   │                │                          │ (80dp + gesture handle)    │
//   └────────────────┴──────────────────────────┴────────────────────────────┘
//
// Every primitive is exposed on `window` at the bottom of this file because
// the prototype uses sibling <script type="text/babel"> tags, which don't
// share scope. In a production codebase, use normal imports.
// ────────────────────────────────────────────────────────────────────────────

// Shared UI primitives for PharmaPay.

// PharmaPay wordmark (white "Pharma", green "Pay").
function Logo({ size = 'md', className = '' }) {
  const s = { sm: 'text-[20px]', md: 'text-[26px]', lg: 'text-[34px]' }[size];
  return (
    <div className={`logo ${s} text-white ${className}`}>
      Pharma<span className="pay">Pay</span>
    </div>
  );
}

// Platform-aware status bar. Reads `window.PLATFORM` ('ios' | 'android') and renders
// the matching system chrome. Defaults to iOS.
function StatusBar({ time = '9:41', dark = false, battery = 52 }) {
  return window.PLATFORM === 'android'
    ? <AndroidStatusBar time={time} dark={dark} battery={battery}/>
    : <IOSStatusBar time={time} dark={dark} battery={battery}/>;
}

// iOS 17/18 layout — time left, icons right, dynamic-island gap, SF Pro.
function IOSStatusBar({ time, dark, battery }) {
  const c = dark ? '#fff' : '#0F1424';
  return (
    <div className="relative h-[54px] select-none" style={{color: c}}>
      <div className="absolute left-0 top-0 h-[54px] flex items-center pl-7">
        <span
          className="tabular-nums"
          style={{
            fontSize: 17, lineHeight: '22px', fontWeight: 600, letterSpacing: '-0.32px',
            fontFamily: '-apple-system, "SF Pro Display", "SF Pro Text", system-ui, sans-serif',
          }}
        >{time}</span>
      </div>
      <div className="absolute right-0 top-0 h-[54px] flex items-center pr-6 gap-[6px]">
        {/* cellular: 4 progressively-taller rounded bars, all filled */}
        <svg width="17" height="11" viewBox="0 0 17 11" aria-hidden="true">
          <rect x="0"   y="7.5" width="3" height="3.5" rx="1" fill={c}/>
          <rect x="4.5" y="5.5" width="3" height="5.5" rx="1" fill={c}/>
          <rect x="9"   y="3"   width="3" height="8"   rx="1" fill={c}/>
          <rect x="13.5" y="0"  width="3" height="11"  rx="1" fill={c}/>
        </svg>
        {/* wifi: three concentric arcs */}
        <svg width="16" height="11" viewBox="0 0 16 11" fill={c} aria-hidden="true">
          <path d="M8 0C5 0 2.3 1.1.3 3l1.3 1.3C3.3 2.7 5.5 1.8 8 1.8s4.7.9 6.4 2.5L15.7 3C13.7 1.1 11 0 8 0Z"/>
          <path d="M8 3.3c-2 0-3.9.8-5.3 2.2l1.3 1.3C5.1 5.7 6.5 5.1 8 5.1s2.9.6 4 1.7l1.3-1.3A7.4 7.4 0 0 0 8 3.3Z"/>
          <path d="M8 6.6c-1.1 0-2.2.5-3 1.3L8 11l3-3.1c-.8-.8-1.9-1.3-3-1.3Z"/>
        </svg>
        <BatteryGlyph color={c} pct={battery} dark={dark}/>
      </div>
    </div>
  );
}

// Material 3 Android layout — time left, icons right (signal triangle / wifi fan / battery pill), Roboto.
function AndroidStatusBar({ time, dark, battery }) {
  const c = dark ? '#fff' : '#1B1F26';
  return (
    <div className="relative h-[38px] select-none" style={{color: c}}>
      <div className="absolute left-0 top-0 h-[38px] flex items-center pl-4">
        <span
          className="tabular-nums"
          style={{
            fontSize: 14, fontWeight: 500, lineHeight: '20px', letterSpacing: '0.1px',
            fontFamily: 'Roboto, "Google Sans", system-ui, sans-serif',
          }}
        >{time}</span>
      </div>
      <div className="absolute right-0 top-0 h-[38px] flex items-center pr-4 gap-[6px]">
        {/* signal — solid triangle (material symbols: signal_cellular_4_bar) */}
        <svg width="16" height="16" viewBox="0 0 24 24" fill={c} aria-hidden="true">
          <path d="M2 22V2L22 22H2z"/>
        </svg>
        {/* wifi — fan */}
        <svg width="16" height="16" viewBox="0 0 24 24" fill={c} aria-hidden="true">
          <path d="M12 21l11-13c-3-2.5-7-4-11-4S4 5.5 1 8l11 13z"/>
        </svg>
        {/* battery — vertical capsule with cap on top, fill from bottom up */}
        <AndroidBatteryGlyph color={c} pct={battery}/>
      </div>
    </div>
  );
}

// Android battery glyph — vertical rounded rect, cap on top, fill grows from bottom.
function AndroidBatteryGlyph({ color, pct }) {
  const W = 10, H = 18;
  const innerH = (H - 4) * (Math.max(0, Math.min(100, pct))/100);
  return (
    <div className="relative inline-flex items-end" style={{width: W, height: H + 2}}>
      {/* cap */}
      <div style={{
        position: 'absolute', top: 0, left: '50%', transform: 'translateX(-50%)',
        width: 4, height: 2, background: color, borderTopLeftRadius: 1, borderTopRightRadius: 1,
      }}/>
      {/* body outline + fill */}
      <div className="relative" style={{ width: W, height: H, marginTop: 2 }}>
        <div className="absolute inset-0" style={{
          borderRadius: 2.5, boxShadow: `inset 0 0 0 1.2px ${color}`, opacity: 0.55,
        }}/>
        <div className="absolute" style={{
          left: 2, right: 2, bottom: 2, height: innerH,
          background: color, borderRadius: 1.5,
        }}/>
      </div>
    </div>
  );
}

function BatteryGlyph({ color, pct, dark }) {
  // iOS-style: hairline pill outline + solid fill (no number).
  const W = 25, H = 12;
  const PAD = 2;
  const innerW = W - PAD*2;
  const fillW = innerW * (Math.max(0, Math.min(100, pct))/100);
  return (
    <div className="relative inline-flex items-center" style={{width: W + 2, height: H}}>
      <div className="relative" style={{width: W, height: H}}>
        {/* outline */}
        <div className="absolute inset-0" style={{
          borderRadius: 3.5,
          boxShadow: `inset 0 0 0 1px ${color}`,
          opacity: 0.55,
        }}/>
        {/* fill bar */}
        <div className="absolute" style={{
          left: PAD, top: PAD, height: H - PAD*2, width: fillW,
          background: color, borderRadius: 1.5,
        }}/>
      </div>
      {/* tip */}
      <div style={{
        width: 2, height: 5,
        background: color, opacity: 0.55,
        borderTopRightRadius: 2, borderBottomRightRadius: 2,
        marginLeft: 1,
      }}/>
    </div>
  );
}

// Primary buttons
function ButtonPrimary({ children, onClick, variant = 'blue', className = '', disabled }) {
  const styles = {
    blue: 'bg-brand-green-600 text-white shadow-fab',
    green: 'bg-brand-blue-600 text-white',
    mint: 'bg-brand-blue-400 text-white',
    white: 'bg-white text-brand-green-600',
  }[variant];
  return (
    <button onClick={onClick} disabled={disabled}
      className={`w-full h-[60px] rounded-full font-bold text-[18px] active:scale-[0.99] transition-transform ${styles} ${disabled?'opacity-50':''} ${className}`}>
      {children}
    </button>
  );
}

// Soft glass pill on blue header
function GlassPill({ children, onClick, className = '' }) {
  return (
    <button onClick={onClick}
      className={`glass-pill h-[52px] rounded-full px-5 flex items-center justify-center gap-2 text-white font-semibold text-[16px] ${className}`}>
      {children}
    </button>
  );
}

// Filter chip (Все / Новинки / Конкурсные)
function FilterChip({ active, children, onClick, leading, trailing }) {
  return (
    <button onClick={onClick}
      className={`shrink-0 h-11 rounded-full px-5 flex items-center gap-2 text-[16px] font-bold transition-colors whitespace-nowrap
      ${active ? 'bg-brand-green-600 text-white shadow-fab' : 'bg-white text-ink-900 shadow-card'}`}>
      {leading}
      <span>{children}</span>
      {trailing}
    </button>
  );
}

// Search input
function SearchInput({ value, onChange, placeholder = 'Поиск', onFocus, className = '' }) {
  return (
    <div className={`h-14 bg-paper-input rounded-2xl px-4 flex items-center gap-3 ${className}`}>
      <I.Search className="text-ink-400" />
      <input value={value} onChange={(e)=>onChange?.(e.target.value)} onFocus={onFocus}
        placeholder={placeholder}
        className="flex-1 bg-transparent outline-none text-[16px] font-medium placeholder:text-ink-400 text-ink-900" />
    </div>
  );
}

// Bottom sheet wrapper
function BottomSheet({ open, onClose, children, height = 'auto', dark = false }) {
  if (!open) return null;
  return (
    <div className="absolute inset-0 z-40">
      <div onClick={onClose} className="absolute inset-0 bg-ink-900/45" />
      <div className={`absolute left-0 right-0 bottom-0 rounded-t-3xl slide-up ${dark? 'grad-receipt text-white':'bg-white text-ink-900'}`} style={{maxHeight: '88%', height}}>
        <div className="flex justify-center pt-3 pb-1"><div className={`w-9 h-1 rounded-full ${dark?'bg-white/40':'bg-ink-300'}`}/></div>
        {children}
      </div>
    </div>
  );
}

// Profile-style row
function Row({ icon, label, onClick }) {
  return (
    <button onClick={onClick}
      className="w-full bg-white rounded-2xl shadow-card px-4 py-4 flex items-center gap-3 active:bg-paper-input/60">
      <div className="w-10 h-10 rounded-xl bg-brand-green-100 grid place-items-center text-brand-green-600">
        {icon}
      </div>
      <span className="flex-1 text-left text-[17px] font-bold text-ink-900">{label}</span>
      <I.Chevron className="text-ink-400" />
    </button>
  );
}

// Promo carousel card (shared by home & welcome 1)
function PromoCard({ promo }) {
  return (
    <div className="relative shrink-0 w-[200px] h-[260px] rounded-2xl overflow-hidden promo-outline"
      style={{ background: promo.bg }}>
      {promo.kind === 'info' && (
        <div className="absolute inset-0 p-3 flex flex-col items-center text-center">
          <div className="absolute top-2 left-1 right-1 bottom-2 rounded-2xl border-2 border-dashed border-amber-200/70 pointer-events-none" />
          <div className="w-8 h-8 mt-1 rounded-full bg-brand-warn grid place-items-center">
            <span className="text-white font-black">!</span>
          </div>
          <div className="mt-2 font-extrabold text-[15px] leading-tight text-ink-900">{promo.title}</div>
          <div className="mt-2 text-[11px] text-ink-700 leading-snug">
            {promo.subtitle.split('2025').map((s,i)=> i===0 ? <span key={i}>{s}<span className="font-bold text-brand-green-600">2025</span></span> : s)}
          </div>
          <div className="mt-auto mb-2 bg-white rounded-xl px-2 py-1.5 text-[10px] font-semibold text-ink-700 inline-flex items-center gap-1 shadow-sm">
            <span className="w-4 h-4 rounded-full bg-brand-blue-500"/> {promo.note}
          </div>
        </div>
      )}
      {promo.kind === 'huggies' && (
        <div className="absolute inset-0 p-2 flex flex-col items-center text-center">
          <div className="text-[10px] font-bold text-red-700 mb-1">{promo.period}</div>
          <div className="text-[11px] font-bold leading-tight text-ink-900">{promo.title}<br/>{promo.subtitle}</div>
          <div className="relative my-2 flex gap-1 items-end justify-center">
            {['#FF9A4B','#3A7CD9','#D94B4B','#6FB54B'].map((c,i)=>(
              <div key={i} className="w-7 h-12 rounded-md shadow-sm" style={{background: c, transform: `rotate(${i%2?'-':''}${i*2}deg)`}}/>
            ))}
          </div>
          <div className="mt-auto bg-gradient-to-b from-red-600 to-red-800 text-white font-black text-[22px] px-4 py-1 rounded-md tracking-wide">50000₸</div>
          <div className="mt-1 text-[9.5px] font-semibold text-ink-700">{promo.footer}</div>
        </div>
      )}
      {promo.kind === 'kotex' && (
        <div className="absolute inset-0 p-2 flex flex-col items-center text-center text-white">
          <div className="text-[10px] font-bold mb-1 opacity-90">{promo.period}</div>
          <div className="text-[14px] font-extrabold leading-tight">{promo.title}<br/><span className="text-[18px]">{promo.subtitle}</span></div>
          <div className="my-2 flex gap-0.5 items-end">
            {['#E94A4A','#26A57F','#FFB84B','#6FB54B','#E94A4A'].map((c,i)=>(
              <div key={i} className="w-5 h-12 rounded-sm" style={{background: c, transform: `rotate(${(i-2)*3}deg)`}}/>
            ))}
          </div>
          <div className="bg-gradient-to-b from-pink-200 to-pink-100 text-pink-700 font-black text-[22px] px-3 py-0.5 rounded-md">10000₸</div>
          <div className="mt-auto text-[10px] font-semibold opacity-90">{promo.footer}</div>
        </div>
      )}
    </div>
  );
}

// Bottom navigation — platform-aware. iOS = simple 4-tab bar; Android = Material 3
// Navigation Bar with a pill indicator behind the active icon.
function BottomNav({ tab, onTab, onReceipt }) {
  const items = [
    { id: 'home', label: 'Главная', icon: I.HomeFill, iconOff: I.Home },
    { id: 'training', label: 'Обучение', icon: I.GradFill, iconOff: I.Grad },
    { id: 'receipt', label: 'Чек', icon: I.CameraFill, iconOff: I.CameraOutline, action: onReceipt },
    { id: 'profile', label: 'Профиль', icon: I.UserFill, iconOff: I.User },
  ];
  return window.PLATFORM === 'android'
    ? <AndroidBottomNav items={items} tab={tab} onTab={onTab}/>
    : <IOSBottomNav items={items} tab={tab} onTab={onTab}/>;
}

function IOSBottomNav({ items, tab, onTab }) {
  const TabBtn = ({ it }) => {
    const active = tab === it.id;
    const Icon = active ? it.icon : it.iconOff;
    const handler = it.action || (() => onTab(it.id));
    return (
      <button onClick={handler}
        className="flex flex-col items-center justify-center gap-1 py-2 active:scale-95 transition-transform">
        <div className={active ? 'text-brand-blue-600' : 'text-ink-400'}><Icon size={24}/></div>
        <span className={`text-[11px] leading-none ${active ? 'text-brand-blue-600 font-bold' : 'text-ink-400 font-semibold'}`}>{it.label}</span>
      </button>
    );
  };
  return (
    <div className="absolute left-0 right-0 bottom-0 z-30">
      <div className="relative bg-white shadow-navTop pt-2 pb-7 grid grid-cols-4">
        {items.map(it => <TabBtn key={it.id} it={it}/>)}
      </div>
    </div>
  );
}

// Material 3 Navigation Bar — 80dp container, active icon sits in a 64×32 pill,
// labels are always visible (M3 default).
function AndroidBottomNav({ items, tab, onTab }) {
  const TabBtn = ({ it }) => {
    const active = tab === it.id;
    const Icon = active ? it.icon : it.iconOff;
    const handler = it.action || (() => onTab(it.id));
    return (
      <button onClick={handler}
        className="flex flex-col items-center justify-start pt-3 pb-2 select-none"
        style={{ fontFamily: 'Roboto, "Google Sans", system-ui, sans-serif' }}>
        {/* Active-pill behind icon */}
        <div className="relative h-8 w-16 rounded-full grid place-items-center"
          style={{ background: active ? '#C9F2DE' : 'transparent', transition: 'background 160ms ease' }}>
          <div className={active ? 'text-brand-blue-700' : 'text-ink-500'}>
            <Icon size={22}/>
          </div>
        </div>
        <div
          className="mt-1"
          style={{
            fontSize: 12, lineHeight: '16px', letterSpacing: '0.5px',
            fontWeight: active ? 700 : 500,
            color: active ? '#0F1424' : '#5A6173',
          }}
        >{it.label}</div>
      </button>
    );
  };
  return (
    <div className="absolute left-0 right-0 bottom-0 z-30">
      <div className="bg-white grid grid-cols-4" style={{
        height: 80,
        boxShadow: '0 -1px 3px rgba(15,20,36,0.06)',
        borderTop: '1px solid rgba(15,20,36,0.04)',
      }}>
        {items.map(it => <TabBtn key={it.id} it={it}/>)}
      </div>
      {/* Gesture handle */}
      <div className="bg-white pt-1 pb-2 grid place-items-center">
        <div style={{ width: 108, height: 4, borderRadius: 2, background: 'rgba(15,20,36,0.45)' }}/>
      </div>
    </div>
  );
}

Object.assign(window, {
  Logo, StatusBar, ButtonPrimary, GlassPill, FilterChip,
  SearchInput, BottomSheet, Row, PromoCard, BottomNav,
});
