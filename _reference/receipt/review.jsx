// ────────────────────────────────────────────────────────────────────────────
// review.jsx — Post-capture receipt review flow
//
// After the user snaps a photo of their fiscal receipt, they land on
// ReceiptReviewScreen — a short checklist that gathers the missing pieces
// before submission:
//
//   1. «Добавить акции»        — promos from the receipt (PromoPickerScreen).
//   2. «Добавить адрес аптеки» — where the purchase happened (AddressSheet).
//   3. «Добавить номер карты»  — where bonuses will be paid out (CardSheet).
//
// Each row is a list-card that flips into a filled state once the user has
// supplied that piece. Bottom CTA «Продолжить» enables when at least one
// promo is added (acta and card are saved across the profile session).
//
// PromoPickerScreen mirrors the catalog on Home but the cards have an inline
// «Добавить +» / «Добавлено ✓» toggle and a bottom «Добавить (N)» CTA.
//
// AddressSheet: 88%-tall bottom sheet, search at top, "Рядом с вами" list of
// nearby partner pharmacies (mock geolocation result). Tapping a row selects
// the pharmacy and closes the sheet.
//
// CardSheet: 88%-tall bottom sheet with a live card preview, masked digit
// field and a custom 3×4 numeric keypad with backspace. CTA «Привязать карту»
// activates once 16 digits are entered.
// ────────────────────────────────────────────────────────────────────────────

const { useState: useStateR, useMemo: useMemoR, useEffect: useEffectR, useRef: useRefR } = React;

// ─── Mock data ───────────────────────────────────────────────────────────

const NEARBY_PHARMACIES = [
  { id:'eu_112',   chain:'Europharma',    name:'Europharma №112',    addr:'пр. Достык, 132',           city:'Алматы',  distance:'120 м',  color:'#16C97A' },
  { id:'sadyhan_3',chain:'Садыхан',       name:'Садыхан №3',          addr:'ул. Толе би, 286',         city:'Алматы',  distance:'340 м',  color:'#0F8F55' },
  { id:'biosfera', chain:'Биосфера',      name:'Биосфера',             addr:'ул. Жандосова, 51',        city:'Алматы',  distance:'480 м',  color:'#3DCDA2' },
  { id:'zerde_18', chain:'Зерде',         name:'Зерде №18',            addr:'мкр. Алмагуль, 9',         city:'Алматы',  distance:'620 м',  color:'#16C97A' },
  { id:'farmis_4', chain:'Фармис',        name:'Фармис №4',            addr:'пр. Абая, 159',            city:'Алматы',  distance:'780 м',  color:'#21D17A' },
  { id:'krug_15',  chain:'Круглосуточная',name:'Круглосуточная №15',   addr:'ул. Сейфуллина, 458',      city:'Алматы',  distance:'1.1 км', color:'#0F8F55' },
  { id:'avic',     chain:'Avicenna',      name:'Avicenna',              addr:'ул. Назарбаева, 232',     city:'Алматы',  distance:'1.4 км', color:'#3DCDA2' },
  { id:'a36_22',   chain:'Аптека 36',     name:'Аптека 36, №22',        addr:'ул. Розыбакиева, 247',    city:'Алматы',  distance:'2.0 км', color:'#16C97A' },
];

// ─── Receipt review screen ──────────────────────────────────────────────

function ReceiptReviewScreen({ onBack, draft, setDraft, openPromos, openAddress, openCard, onContinue }) {
  const { promos, pharmacy, card } = draft;
  const promoCount = promos.length;
  const hasPharmacy = !!pharmacy;
  const hasCard = !!card && card.replace(/\s/g,'').length === 16;
  const allReady = promoCount > 0 && hasPharmacy && hasCard;

  return (
    <div className="screen absolute inset-0 bg-white flex flex-col">
      <StatusBar time="9:41"/>
      {/* Header bar */}
      <div className="relative h-12 flex items-center px-5">
        <button onClick={onBack} className="absolute left-5 w-10 h-10 -ml-2 grid place-items-center text-ink-900"><I.Back/></button>
        <h1 className="flex-1 text-center text-[18px] font-extrabold text-ink-900">Чек</h1>
      </div>

      <div className="flex-1 overflow-y-auto no-scrollbar px-5 pt-2 pb-6">
        {/* Receipt thumbnail + status banner */}
        <div className="flex items-stretch gap-3">
          <ReceiptThumb/>
          <div className="flex-1 rounded-2xl bg-brand-green-100 border border-brand-green-200 px-4 py-3 flex items-center gap-3">
            <ReceiptCheckGlyph/>
            <div className="min-w-0">
              <div className="text-[16px] font-extrabold text-brand-green-700 leading-tight">Чек загружен</div>
              <div className="text-[12px] font-semibold text-brand-green-700/70 leading-tight mt-0.5 truncate">Укажите акции из вашего чека</div>
            </div>
          </div>
        </div>

        {/* Three action rows */}
        <div className="mt-6 flex flex-col gap-4">
          <ChecklistRow
            filled={promoCount > 0}
            iconEmpty={<I.Plus/>}
            iconFilled={<I.Check size={20}/>}
            labelEmpty="Добавить акции"
            labelFilled={`${promoCount} ${pluralPromo(promoCount)} в чеке`}
            sublineEmpty="Выберите товары из чека для начисления бонусов"
            sublineFilled={promos.slice(0,2).map(p=>p.name).join(' · ') + (promoCount > 2 ? ' · ещё ' + (promoCount-2) : '')}
            onClick={openPromos}
          />
          <ChecklistRow
            filled={hasPharmacy}
            iconEmpty={<I.PencilEdit/>}
            iconFilled={<I.Check size={20}/>}
            labelEmpty="Добавить адрес аптеки"
            labelFilled={pharmacy?.name}
            sublineEmpty="Где была совершена покупка"
            sublineFilled={pharmacy?.addr}
            onClick={openAddress}
          />
          <ChecklistRow
            filled={hasCard}
            iconEmpty={<I.CreditCardOutline/>}
            iconFilled={<I.Check size={20}/>}
            labelEmpty="Добавить номер карты"
            labelFilled={card ? maskCard(card) : ''}
            sublineEmpty="Для начисления бонусов"
            sublineFilled="Сохранена в профиле"
            onClick={openCard}
          />
        </div>

        {/* Optional helper near the end */}
        <div className="mt-6 rounded-2xl bg-paper-input/70 px-4 py-3 flex items-start gap-3">
          <I.Lock className="text-ink-400 mt-0.5"/>
          <p className="text-[12px] text-ink-500 leading-snug">
            Данные защищены — мы используем их только для зачисления выплат.
          </p>
        </div>
      </div>

      {/* Sticky CTA */}
      <div className="px-5 pt-3 pb-7 bg-gradient-to-b from-transparent to-white">
        <ButtonPrimary variant="green" disabled={!allReady} onClick={onContinue}>
          Продолжить
        </ButtonPrimary>
        {!allReady && (
          <p className="text-center text-[12px] font-semibold text-ink-400 mt-2">
            Заполните все пункты, чтобы продолжить
          </p>
        )}
      </div>
    </div>
  );
}

const pluralPromo = (n) => {
  const m = n % 10, h = n % 100;
  if (m === 1 && h !== 11) return 'акция';
  if (m >= 2 && m <= 4 && (h < 10 || h >= 20)) return 'акции';
  return 'акций';
};

const maskCard = (v) => {
  const d = v.replace(/\s/g,'');
  if (d.length < 16) return v;
  return `•••• •••• •••• ${d.slice(12)}`;
};

// ─── Checklist row ──────────────────────────────────────────────────────

function ChecklistRow({ filled, iconEmpty, iconFilled, labelEmpty, labelFilled, sublineEmpty, sublineFilled, onClick }) {
  return (
    <button onClick={onClick}
      className={`w-full text-left rounded-2xl shadow-card px-4 py-4 flex items-center gap-3 transition-colors active:scale-[0.995]
        ${filled ? 'bg-brand-green-50 border border-brand-green-200' : 'bg-white'}`}>
      <div className={`w-12 h-12 rounded-2xl grid place-items-center flex-none
        ${filled ? 'bg-brand-green-600 text-white shadow-fab' : 'bg-brand-green-100 text-brand-green-700'}`}>
        {filled ? iconFilled : iconEmpty}
      </div>
      <div className="flex-1 min-w-0">
        <div className={`text-[17px] font-extrabold leading-tight truncate ${filled ? 'text-ink-900' : 'text-ink-900'}`}>
          {filled ? labelFilled : labelEmpty}
        </div>
        <div className="text-[12px] font-semibold text-ink-500 leading-snug mt-0.5 truncate">
          {filled ? sublineFilled : sublineEmpty}
        </div>
      </div>
      <I.Chevron className="text-ink-400 flex-none"/>
    </button>
  );
}

// ─── Receipt thumbnail + check glyph ────────────────────────────────────

function ReceiptThumb() {
  return (
    <div className="w-[88px] h-[88px] rounded-2xl bg-paper-input p-1.5 flex-none relative overflow-hidden shadow-card">
      <div className="absolute inset-1.5 rounded-xl bg-white">
        <div className="absolute inset-0 p-2 flex flex-col gap-1">
          <div className="h-1 rounded-full bg-ink-300/60 w-3/4"/>
          <div className="h-1 rounded-full bg-ink-300/40 w-1/2"/>
          <div className="flex-1 grid place-items-center">
            <div className="w-9 h-9 rounded grid place-items-center" style={{background:'repeating-conic-gradient(#0F1424 0% 25%, #fff 0% 50%)', backgroundSize:'4px 4px'}}/>
          </div>
          <div className="h-1 rounded-full bg-ink-300/40 w-2/3"/>
          <div className="h-1 rounded-full bg-ink-300/30 w-1/3"/>
        </div>
        {/* zigzag bottom */}
        <svg viewBox="0 0 88 8" className="absolute -bottom-0.5 left-0 right-0 w-full" preserveAspectRatio="none">
          <path d="M0 0 L4 6 L8 0 L12 6 L16 0 L20 6 L24 0 L28 6 L32 0 L36 6 L40 0 L44 6 L48 0 L52 6 L56 0 L60 6 L64 0 L68 6 L72 0 L76 6 L80 0 L84 6 L88 0 L88 8 L0 8 Z" fill="#F2F4F8"/>
        </svg>
      </div>
    </div>
  );
}

function ReceiptCheckGlyph() {
  // Receipt with green check stamp — composite icon for "receipt accepted"
  return (
    <div className="relative w-12 h-12 flex-none">
      <svg viewBox="0 0 48 48" className="absolute inset-0">
        {/* receipt body */}
        <path d="M10 8 a3 3 0 0 1 3-3 h22 a3 3 0 0 1 3 3 v32 l-3-2 -3 2 -3-2 -3 2 -3-2 -3 2 -3-2 -3 2 z"
          fill="#FFFFFF" stroke="#16C97A" strokeWidth="2" strokeLinejoin="round"/>
        <rect x="15" y="14" width="14" height="1.8" rx="0.9" fill="#16C97A" opacity="0.5"/>
        <rect x="15" y="19" width="10" height="1.8" rx="0.9" fill="#16C97A" opacity="0.5"/>
        <rect x="15" y="24" width="12" height="1.8" rx="0.9" fill="#16C97A" opacity="0.5"/>
      </svg>
      {/* check disc */}
      <div className="absolute right-0 bottom-0 w-7 h-7 rounded-full bg-brand-green-600 grid place-items-center shadow-fab ring-2 ring-brand-green-100">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#fff" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12l5 5L20 7"/></svg>
      </div>
    </div>
  );
}

// ─── Promo picker screen ────────────────────────────────────────────────

function PromoPickerScreen({ onBack, selectedIds, onSubmit, openSort, openBrands, sortIdx, brandSel, chip, setChip }) {
  const [q, setQ] = useStateR('');
  const [sel, setSel] = useStateR(new Set(selectedIds));
  const toggle = (id) => {
    const next = new Set(sel);
    next.has(id) ? next.delete(id) : next.add(id);
    setSel(next);
  };
  const products = (window.PP_DATA?.PRODUCTS || []).filter(p => {
    if (chip === 'new' && !p.new) return false;
    if (chip === 'contest' && !p.contest) return false;
    if (q && !p.name.toLowerCase().includes(q.toLowerCase()) && !p.brand.toLowerCase().includes(q.toLowerCase())) return false;
    return true;
  });

  return (
    <div className="screen absolute inset-0 bg-white flex flex-col">
      <StatusBar time="9:41"/>
      {/* Header */}
      <div className="relative h-12 flex items-center px-5">
        <button onClick={onBack} className="absolute left-5 w-10 h-10 -ml-2 grid place-items-center text-ink-900"><I.Back/></button>
        <h1 className="flex-1 text-center text-[18px] font-extrabold text-ink-900">Добавить акции</h1>
      </div>

      <div className="flex-1 overflow-y-auto no-scrollbar px-5 pt-3 pb-32">
        <h2 className="text-[28px] font-extrabold text-ink-900 leading-tight mb-4">Акции</h2>

        <SearchInput value={q} onChange={setQ} placeholder="Поиск по названию или бренду"/>

        {/* Filter row */}
        <div className="mt-4 flex items-center gap-2 -mx-5 px-5 overflow-x-auto no-scrollbar">
          <button onClick={openSort} className="shrink-0 w-11 h-11 rounded-full bg-white shadow-card grid place-items-center text-ink-900"><I.Sort/></button>
          <button onClick={openBrands} className="shrink-0 h-11 rounded-full bg-white shadow-card px-4 flex items-center gap-2 text-ink-900 font-bold text-[15px]">
            Бренд{brandSel?.length ? <span className="text-[12px] bg-brand-green-100 text-brand-green-700 rounded-full px-2 py-0.5 font-extrabold">{brandSel.length}</span> : null}<I.ChevronDown/>
          </button>
          <FilterChip active={chip==='all'} onClick={()=>setChip('all')}>Все</FilterChip>
          <FilterChip active={chip==='new'} onClick={()=>setChip('new')}>Новинки</FilterChip>
          <FilterChip active={chip==='contest'} onClick={()=>setChip('contest')} leading={<I.TrophyEmoji size={18}/>}>Конкурсные</FilterChip>
        </div>

        {/* Product grid */}
        <div className="mt-5 grid grid-cols-2 gap-3">
          {products.map(p => (
            <PickerProductCard key={p.id} product={p} selected={sel.has(p.id)} onToggle={()=>toggle(p.id)}/>
          ))}
          {products.length === 0 && (
            <div className="col-span-2 py-12 text-center text-ink-400 text-[14px] font-semibold">Ничего не найдено</div>
          )}
        </div>
      </div>

      {/* Bottom CTA — sticks above safe area */}
      <div className="absolute left-0 right-0 bottom-0 px-5 pt-3 pb-7 bg-gradient-to-t from-white via-white/95 to-transparent">
        <ButtonPrimary variant="green" disabled={sel.size === 0} onClick={() => onSubmit(products.filter(p=>sel.has(p.id)))}>
          {sel.size === 0 ? 'Выберите акции' : `Добавить · ${sel.size}`}
        </ButtonPrimary>
      </div>
    </div>
  );
}

function PickerProductCard({ product, selected, onToggle }) {
  const accent = product.pkg.accent || '#16C97A';
  return (
    <div className={`rounded-2xl overflow-hidden shadow-card flex flex-col relative transition-all
      ${selected ? 'ring-2 ring-brand-green-600 bg-brand-green-50' : 'bg-white'}`}>
      {/* Image */}
      <div className="h-[140px] relative flex items-center justify-center" style={{background: product.pkg.bg}}>
        {product.new && <span className="absolute top-2 left-2 text-[10px] font-extrabold bg-brand-green-600 text-white rounded-full px-2 py-0.5 tracking-wide">NEW</span>}
        {product.contest && <span className="absolute top-2 left-2 text-[10px] font-extrabold bg-brand-trophy/95 text-white rounded-full px-1.5 py-0.5">🏆</span>}
        <span className="absolute bottom-2 left-2 text-[10px] font-extrabold bg-brand-green-600 text-white rounded-full px-2 py-0.5 tracking-wide">{product.period}</span>
        {selected && (
          <span className="absolute top-2 right-2 w-7 h-7 rounded-full bg-brand-green-600 ring-2 ring-white grid place-items-center text-white shadow-fab">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12l5 5L20 7"/></svg>
          </span>
        )}
        <div className="text-center px-2" style={{color: accent}}>
          <div className="text-[20px] font-extrabold uppercase tracking-tight leading-none">{product.pkg.label}</div>
          {product.pkg.sub && <div className="text-[10px] font-bold opacity-70 mt-1">{product.pkg.sub}</div>}
        </div>
      </div>
      {/* Body */}
      <div className="p-3 flex flex-col gap-2 flex-1">
        <div className="text-[14px] font-bold text-ink-900 leading-tight line-clamp-2 min-h-[36px]">{product.name}</div>
        {product.restrictions && <div className="text-[11px] font-semibold text-brand-green-700 leading-tight line-clamp-1">{product.restrictions}</div>}
        <button onClick={onToggle}
          className={`mt-1 h-9 rounded-full font-extrabold text-[14px] flex items-center justify-center gap-1.5 transition-colors
          ${selected ? 'bg-brand-green-600 text-white shadow-fab' : 'bg-brand-green-100 text-brand-green-700'}`}>
          {selected ? <>Добавлено <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12l5 5L20 7"/></svg></>
                    : <>Добавить <I.Plus size={16}/></>}
        </button>
      </div>
    </div>
  );
}

// ─── Address sheet ──────────────────────────────────────────────────────

function AddressSheet({ open, onClose, onPick, current }) {
  const [q, setQ] = useStateR('');
  useEffectR(() => { if (open) setQ(''); }, [open]);
  const filtered = NEARBY_PHARMACIES.filter(p => !q || (p.name+p.addr).toLowerCase().includes(q.toLowerCase()));
  return (
    <BottomSheet open={open} onClose={onClose} height="78%">
      <div className="flex flex-col h-full">
        <div className="px-5 pt-3 pb-2">
          <h2 className="text-[22px] font-extrabold text-ink-900">Адрес аптеки</h2>
          <p className="text-[13px] font-medium text-ink-500 mt-1">Выберите аптеку из списка или найдите вручную</p>
        </div>

        <div className="px-5">
          <SearchInput value={q} onChange={setQ} placeholder="Название аптеки или адрес"/>
        </div>

        {/* Geolocation banner */}
        <div className="px-5 mt-4">
          <div className="rounded-2xl border border-brand-green-200 bg-brand-green-50 px-4 py-3 flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-brand-green-600 grid place-items-center text-white shadow-fab">
              <I.MapPin/>
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-[14px] font-extrabold text-brand-green-700 leading-tight">Рядом с вами</div>
              <div className="text-[12px] font-semibold text-brand-green-700/70 leading-tight mt-0.5">Алматы · найдено {NEARBY_PHARMACIES.length} аптек поблизости</div>
            </div>
          </div>
        </div>

        {/* List */}
        <div className="flex-1 overflow-y-auto no-scrollbar mt-3 px-5 pb-6">
          <ul className="flex flex-col gap-2">
            {filtered.map(p => (
              <li key={p.id}>
                <button onClick={() => { onPick(p); onClose(); }}
                  className={`w-full flex items-center gap-3 px-3 py-3 rounded-2xl text-left transition-colors active:scale-[0.99]
                    ${current?.id === p.id ? 'bg-brand-green-50 border border-brand-green-200' : 'bg-paper-input/70 hover:bg-paper-input'}`}>
                  <div className="w-10 h-10 rounded-xl grid place-items-center text-white font-extrabold text-[14px] flex-none" style={{background: p.color}}>
                    {p.chain[0]}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-[15px] font-extrabold text-ink-900 leading-tight truncate">{p.name}</div>
                    <div className="text-[12px] font-semibold text-ink-500 leading-snug truncate">{p.addr} · {p.city}</div>
                  </div>
                  <span className="shrink-0 text-[11px] font-extrabold text-brand-green-700 bg-brand-green-100 rounded-full px-2 py-1">{p.distance}</span>
                </button>
              </li>
            ))}
            {filtered.length === 0 && (
              <li className="py-10 text-center text-ink-400 text-[14px] font-semibold">Ничего не найдено</li>
            )}
          </ul>
        </div>
      </div>
    </BottomSheet>
  );
}

// ─── Card sheet ─────────────────────────────────────────────────────────

function CardSheet({ open, onClose, onSubmit, initial }) {
  const [digits, setDigits] = useStateR(initial?.replace(/\s/g,'') || '');
  useEffectR(() => { if (open) setDigits(initial?.replace(/\s/g,'') || ''); }, [open, initial]);

  const press = (d) => { if (digits.length < 16) setDigits(s => s + d); };
  const back = () => setDigits(s => s.slice(0, -1));
  const formatted = digits.replace(/(.{4})/g, '$1 ').trim();
  const ready = digits.length === 16;

  return (
    <BottomSheet open={open} onClose={onClose}>
      <div className="flex flex-col">
        <div className="px-5 pt-3 pb-1">
          <h2 className="text-[22px] font-extrabold text-ink-900">Добавить карту</h2>
          <p className="text-[13px] font-medium text-ink-500 mt-1">Бонусы будут зачисляться на эту карту</p>
        </div>

        {/* Card preview */}
        <div className="px-5 mt-4">
          <CardPreview digits={digits}/>
        </div>

        {/* Number field */}
        <div className="px-5 mt-4">
          <div className="rounded-2xl bg-paper-input px-4 py-3.5 flex items-center gap-3">
            <I.CreditCardOutline className="text-brand-green-700 flex-none"/>
            <div className="flex-1 min-w-0">
              <div className="text-[11px] font-bold text-ink-500 uppercase tracking-wide">Номер карты</div>
              <div className="font-extrabold text-[16px] text-ink-900 tracking-[0.08em] mt-0.5 min-h-[20px] whitespace-nowrap overflow-hidden">
                {formatted || <span className="text-ink-300 tracking-normal font-semibold">Введите номер карты</span>}
              </div>
            </div>
            <span className="text-[12px] font-bold text-ink-400 font-mono tabular-nums flex-none">{digits.length}/16</span>
          </div>
        </div>

        {/* Numeric keypad */}
        <div className="px-5 pt-4">
          <div className="grid grid-cols-3 gap-2.5">
            {[1,2,3,4,5,6,7,8,9].map(n => (
              <KeypadButton key={n} onClick={() => press(String(n))}>{n}</KeypadButton>
            ))}
            <KeypadButton onClick={() => press('0')} className="col-start-2">0</KeypadButton>
            <KeypadButton onClick={back} kind="action" aria-label="Удалить">
              <I.Backspace/>
            </KeypadButton>
          </div>
        </div>

        {/* CTA */}
        <div className="px-5 pt-4 pb-6">
          <ButtonPrimary variant="green" disabled={!ready} onClick={() => { onSubmit(formatted); onClose(); }}>
            Привязать карту
          </ButtonPrimary>
        </div>
      </div>
    </BottomSheet>
  );
}

function KeypadButton({ onClick, children, className='', kind='digit', ...rest }) {
  const base = 'h-14 rounded-2xl flex items-center justify-center font-extrabold text-[24px] active:scale-[0.97] transition-transform';
  const cls = kind === 'action'
    ? `${base} bg-brand-green-600 text-white shadow-fab`
    : `${base} bg-paper-input text-ink-900`;
  return <button onClick={onClick} className={`${cls} ${className}`} {...rest}>{children}</button>;
}

function CardPreview({ digits }) {
  const padded = (digits + '••••••••••••••••').slice(0, 16);
  const groups = [padded.slice(0,4), padded.slice(4,8), padded.slice(8,12), padded.slice(12,16)];
  return (
    <div className="relative h-[150px] rounded-3xl p-4 flex flex-col justify-between text-white overflow-hidden"
      style={{ background: 'linear-gradient(135deg, #16A65C 0%, #21D17A 60%, #3DCDA2 100%)', boxShadow: '0 10px 28px rgba(15,143,85,0.35)' }}>
      <div className="absolute -top-10 -right-10 w-40 h-40 rounded-full bg-white/15 blur-2xl"/>
      <div className="absolute -bottom-12 -left-10 w-44 h-44 rounded-full bg-brand-green-300/30 blur-2xl"/>
      <div className="relative flex items-start justify-between">
        <div>
          <div className="text-[10px] font-bold uppercase tracking-[0.12em] opacity-80">PharmaPay</div>
          <div className="text-[18px] font-extrabold leading-tight mt-0.5">Бонусная карта</div>
        </div>
        {/* chip */}
        <div className="w-9 h-6 rounded-md" style={{background: 'linear-gradient(135deg, #F4B73A, #B97F11)', boxShadow:'inset 0 0 0 1px rgba(255,255,255,0.4)'}}/>
      </div>
      <div className="relative">
        <div className="flex items-center gap-2.5 text-[15px] font-extrabold tracking-[0.1em] tabular-nums whitespace-nowrap">
          {groups.map((g, i) => (
            <span key={i} className={g.includes('•') ? 'opacity-60' : ''}>{g}</span>
          ))}
        </div>
        <div className="flex items-center justify-between mt-2 text-[9px] font-bold uppercase tracking-[0.1em] opacity-80">
          <span>держатель</span>
          <span>VISA / KASPIGOLD / HALYK</span>
        </div>
      </div>
    </div>
  );
}

// Expose
window.ReceiptReviewScreen = ReceiptReviewScreen;
window.PromoPickerScreen = PromoPickerScreen;
window.AddressSheet = AddressSheet;
window.CardSheet = CardSheet;
