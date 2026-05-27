// ────────────────────────────────────────────────────────────────────────────
// home.jsx — Главная (Home) tab
//
// Two header states keyed off the `authed` prop:
//
//   • Unauthed → «Добро пожаловать!» card + white «Войти» CTA inside the
//     green header. The catalog below is still fully browseable.
//   • Authed   → Balance card (gold coin + amount, two glass pills for
//     History + Загрузить чек).
//
// Body (identical in both states):
//   1. Promo carousel — 3 hand-illustrated promo cards.
//   2. Search input.
//   3. Filter row — Sort button, Brand button (with count badge),
//      «Все» / «Новинки» / «Конкурсные» chips (single-select).
//   4. Catalog — featured BigProductCard (if any) on top, then a 2-column
//      grid of SmallProductCards. Filtered by the active chip.
//
// Tapping any catalog tile opens ProductDetailSheet (bottom-sheet with
// the full package, tier ladder, bonuses, how-to-get-bonus steps, and a
// primary «Загрузить чек» CTA).
//
// The Sort sheet and Brand sheet are exported alongside HomeScreen and
// mounted at the App root.
// ────────────────────────────────────────────────────────────────────────────

// Home (Главная) screen
function HomeScreen({ authed, onLogin, onUpload, onCamera, onHistory, openSort, openBrands, activeChip, setActiveChip, sortIdx, brandSel, onProduct }) {
  const { PROMOS, PRODUCTS } = window.PP_DATA;

  // Filter products by active chip
  const filteredProducts = React.useMemo(() => {
    if (activeChip === 'new') return PRODUCTS.filter(p => p.new);
    if (activeChip === 'contest') return PRODUCTS.filter(p => p.contest);
    return PRODUCTS;
  }, [activeChip, PRODUCTS]);

  // Pull the "featured" big card up if it qualifies for the current filter; otherwise show only small cards.
  const featured = filteredProducts.find(p => p.featured);
  const rest = filteredProducts.filter(p => p !== featured);

  return (
    <div className="screen absolute inset-0 bg-paper flex flex-col">
      {/* Green header card */}
      <div className="grad-blueHead px-5 pt-1 pb-8 rounded-b-3xl relative">
        <StatusBar time="9:41" dark/>
        <div className="mt-2 mb-4"><Logo size="md"/></div>

        {authed ? (
          /* Balance card — only after login */
          <div className="bg-brand-green-700/40 rounded-3xl p-4 backdrop-blur-md border border-white/10">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-3 min-w-0">
                <I.Coin size={40}/>
                <div className="text-white min-w-0">
                  <div className="text-[14px] font-semibold leading-tight">PharmaPay</div>
                  <div className="text-[14px] font-semibold leading-tight">Баланс</div>
                </div>
              </div>
              <div className="text-white text-[24px] font-extrabold tabular-nums whitespace-nowrap">0 ₸</div>
            </div>
            <div className="mt-4 flex gap-2.5">
              <GlassPill className="flex-1 !px-3" onClick={onHistory}>
                <I.Clock size={18}/> <span className="text-[15px]">История</span>
              </GlassPill>
              <GlassPill className="flex-1 !px-3" onClick={onUpload}>
                <I.Upload size={18}/> <span className="text-[15px] whitespace-nowrap">Загрузить чек</span>
              </GlassPill>
            </div>
          </div>
        ) : (
          /* Welcome banner — before login */
          <div className="bg-brand-green-700/40 rounded-3xl p-5 backdrop-blur-md border border-white/15">
            <h2 className="text-white text-[26px] font-extrabold leading-tight">Добро пожаловать!</h2>
            <button
              onClick={onLogin}
              className="mt-4 w-full h-[60px] rounded-2xl bg-white text-brand-green-600 text-[20px] font-extrabold shadow-fab active:scale-[0.99] transition-transform"
            >
              Войти
            </button>
          </div>
        )}
      </div>

      {/* Scroll body */}
      <div className="flex-1 overflow-y-auto no-scrollbar pb-[120px] -mt-6">
        {/* Promo carousel — overlaps header */}
        <div className="px-5 mt-2 flex gap-3 overflow-x-auto no-scrollbar pb-2">
          {PROMOS.map(p => <PromoCard key={p.id} promo={p}/>)}
        </div>

        {/* Search */}
        <div className="px-5 mt-4">
          <SearchInput value="" onChange={()=>{}} />
        </div>

        {/* Filter row */}
        <div className="mt-4 px-5 flex gap-2 overflow-x-auto no-scrollbar pb-1">
          <button onClick={openSort} className="shrink-0 h-11 w-11 rounded-full bg-white shadow-card grid place-items-center text-ink-900"><I.Sort/></button>
          <button onClick={openBrands} className="shrink-0 h-11 rounded-full bg-white shadow-card px-4 flex items-center gap-2 text-ink-900 font-bold text-[16px]">
            Бренд <I.ChevronDown size={16}/>
            {brandSel?.length>0 && <span className="ml-1 bg-brand-green-600 text-white text-[11px] rounded-full px-2 py-[1px]">{brandSel.length}</span>}
          </button>
          <FilterChip active={activeChip==='all'} onClick={()=>setActiveChip('all')}>Все</FilterChip>
          <FilterChip active={activeChip==='new'} onClick={()=>setActiveChip('new')}>Новинки</FilterChip>
          <FilterChip active={activeChip==='contest'} onClick={()=>setActiveChip('contest')}
            leading={<I.TrophyEmoji size={22}/>}>
            Конкурсные
          </FilterChip>
        </div>

        {/* Product list — featured big card + grid of small cards */}
        <div className="mt-4 px-5 flex flex-col gap-3">
          {filteredProducts.length === 0 && (
            <div className="text-center text-ink-500 text-[14px] font-medium py-8">
              Ничего не найдено по этому фильтру
            </div>
          )}
          {featured && <BigProductCard p={featured} onClick={()=>onProduct?.(featured)}/>}
          {rest.length > 0 && (
            <div className="grid grid-cols-2 gap-3">
              {rest.map(p => <SmallProductCard key={p.id} p={p} onClick={()=>onProduct?.(p)}/>)}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function BigProductCard({ p, onClick }) {
  return (
    <button onClick={onClick} className="text-left bg-white rounded-3xl shadow-card p-4 active:scale-[0.99] transition-transform">
      {/* image area with floating period badge */}
      <div className="relative h-[200px] rounded-2xl overflow-hidden grid place-items-center" style={{ background: p.pkg.bg }}>
        <span className="absolute top-3 left-3 bg-white/95 backdrop-blur text-[12px] font-bold text-ink-900 rounded-xl px-3 py-1.5 shadow-sm">{p.period}</span>
        <PackageMock pkg={p.pkg}/>
      </div>

      {/* title row */}
      <div className="mt-3 flex items-baseline justify-between gap-3">
        <div className="text-[16px] font-extrabold text-ink-900 leading-tight">{p.name}</div>
        {p.bought !== null && (
          <div className="text-[13px] font-semibold text-ink-500 shrink-0 whitespace-nowrap">Куплено: {p.bought} уп.</div>
        )}
      </div>

      {/* Tier ladder */}
      <TierLadder tiers={p.tiers}/>

      {/* Bonuses */}
      {p.bonuses.length > 0 && (
        <div className="mt-3 flex flex-col gap-1.5">
          {p.bonuses.map((b,i)=>(
            <div key={i} className="flex items-start gap-2 text-[13px] text-ink-700 font-medium leading-snug">
              <I.GiftEmoji size={18}/><span>{b}</span>
            </div>
          ))}
        </div>
      )}
    </button>
  );
}

// Stylised package render — the green Лифта box in the catalog screenshot.
function PackageMock({ pkg }) {
  return (
    <div className="relative w-[150px] h-[170px] rounded-md overflow-hidden shadow-2xl" style={{ background: pkg.bg }}>
      <div className="absolute top-1.5 left-2 text-[7px] text-white/80 tracking-wide">РК-ЛС-5№024187</div>
      <div className="absolute top-7 left-2 right-2">
        <div className="text-white font-extrabold tracking-[0.02em] leading-none" style={{fontSize: 22}}>{pkg.label.split(' ')[0]}</div>
        {pkg.label.includes(' ') && (
          <div className="text-white/95 font-bold mt-0.5" style={{fontSize: 11}}>{pkg.label.split(' ').slice(1).join(' ')}</div>
        )}
        {pkg.sub && <div className="mt-1.5 text-white/85 text-[8.5px] leading-tight border-t border-white/30 pt-1">{pkg.sub}</div>}
      </div>
      {/* watermark glyph */}
      <div className="absolute inset-x-0 top-[55%] grid place-items-center opacity-25 pointer-events-none">
        <svg width="120" height="80" viewBox="0 0 120 80" fill="none">
          <path d="M5 40 C 30 0, 60 0, 75 40 S 110 80, 115 40" stroke="#fff" strokeWidth="3" strokeLinecap="round"/>
        </svg>
      </div>
      {pkg.maker && (
        <div className="absolute bottom-1.5 left-2 right-2 flex items-center justify-between">
          <div className="text-white font-extrabold text-[9px] tracking-wider">{pkg.maker}</div>
          <div className="text-white/85 text-[6.5px] leading-tight text-right">1 таблетка,<br/>покрытая пленочной<br/>оболочкой</div>
        </div>
      )}
      {/* colour ribbon down the left side, like real pharma packs */}
      <div className="absolute top-7 left-0 h-[110px] w-1.5 flex flex-col">
        {['#FFB84B','#3A7CD9','#D94B4B','#6FB54B'].map((c,i)=>(
          <div key={i} style={{background: c, flex: 1}}/>
        ))}
      </div>
    </div>
  );
}

// Connecting-line tier ladder — pill prices on top, divider with gift glyphs, labels under.
function TierLadder({ tiers }) {
  if (!tiers || tiers.length === 0) return null;
  return (
    <div className="mt-4 relative">
      {/* Pills row */}
      <div className="grid gap-2" style={{gridTemplateColumns: `repeat(${tiers.length}, minmax(0, 1fr))`}}>
        {tiers.map((t,i)=>(
          <div key={i} className="flex justify-center">
            <div className="h-[34px] min-w-[64px] px-3 rounded-full bg-brand-green-600 text-white font-extrabold text-[15px] grid place-items-center shadow-fab">
              {t.price}
            </div>
          </div>
        ))}
      </div>

      {/* Divider line with gift glyphs in the gaps */}
      <div className="relative mt-3 h-1.5">
        <div className="absolute inset-x-0 top-1/2 -translate-y-1/2 h-1.5 rounded-full bg-paper-input"/>
        {/* gift markers between tiers (i.e. tiers.length-1 of them) */}
        <div className="absolute inset-0 grid" style={{gridTemplateColumns: `repeat(${tiers.length}, minmax(0, 1fr))`}}>
          {tiers.map((_,i)=>(
            <div key={i} className="relative">
              {i > 0 && (
                <div className="absolute -top-3 -left-[14px]"><I.GiftEmoji size={28}/></div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* labels */}
      <div className="mt-3 grid gap-2 text-center text-[13px] font-bold text-ink-700" style={{gridTemplateColumns: `repeat(${tiers.length}, minmax(0, 1fr))`}}>
        {tiers.map((t,i)=>(<div key={i}>{t.label}</div>))}
      </div>
    </div>
  );
}

function SmallProductCard({ p, onClick }) {
  return (
    <button onClick={onClick} className="bg-white rounded-2xl shadow-card p-3 flex flex-col text-left active:scale-[0.99] transition-transform">
      <div className="relative h-[140px] rounded-xl overflow-hidden grid place-items-center" style={{background: p.pkg.bg}}>
        <div className="font-extrabold text-[13px] text-center leading-tight px-2" style={{color: p.pkg.accent || '#FFFFFF'}}>{p.pkg.label}</div>
        <div className="absolute bottom-2 left-2 bg-brand-green-600 text-white text-[12px] font-bold rounded-md px-2 py-0.5">{p.period}</div>
        {p.new && <div className="absolute top-2 right-2 bg-brand-blue-500 text-white text-[10px] font-extrabold rounded-md px-1.5 py-0.5">NEW</div>}
        {p.contest && <div className="absolute top-2 left-2"><I.TrophyEmoji size={26}/></div>}
      </div>
      <div className="mt-2 text-[14px] font-bold leading-tight text-ink-900 line-clamp-2">{p.name}</div>
      {p.restrictions && <div className="mt-1 text-[12px] font-semibold text-brand-blue-500 leading-tight">{p.restrictions}</div>}
    </button>
  );
}

// Sort sheet
function SortSheet({ open, onClose, sortIdx, setSortIdx }) {
  const { SORT_OPTIONS } = window.PP_DATA;
  return (
    <BottomSheet open={open} onClose={onClose}>
      <div className="px-5 pt-2 pb-8">
        <h2 className="text-[22px] font-extrabold text-ink-900">Сортировка</h2>
        <div className="mt-4 flex flex-col">
          {SORT_OPTIONS.map((o,i)=>(
            <button key={i} onClick={()=>{setSortIdx(i); onClose();}}
              className="flex items-center justify-between py-4 border-b border-ink-300/30 last:border-0">
              <span className="text-[17px] font-bold text-ink-900 text-left">{o}</span>
              <div className={`w-6 h-6 rounded-full border-2 ${sortIdx===i?'border-brand-green-600':'border-ink-300'} grid place-items-center`}>
                {sortIdx===i && <div className="w-3 h-3 rounded-full bg-brand-green-600"/>}
              </div>
            </button>
          ))}
        </div>
      </div>
    </BottomSheet>
  );
}

// Brand sheet
function BrandSheet({ open, onClose, brandSel, setBrandSel }) {
  const { BRANDS } = window.PP_DATA;
  const [q, setQ] = React.useState('');
  const list = BRANDS.filter(b => b.toLowerCase().includes(q.toLowerCase()));
  const toggle = (b) => setBrandSel(brandSel.includes(b) ? brandSel.filter(x=>x!==b) : [...brandSel, b]);
  return (
    <BottomSheet open={open} onClose={onClose} height="80%">
      <div className="px-5 pt-2 pb-6 h-full flex flex-col">
        <div className="flex items-center justify-between">
          <h2 className="text-[22px] font-extrabold text-ink-900">Бренды</h2>
          <button onClick={()=>setBrandSel([])} className="text-brand-blue-500 font-bold text-[16px]">Сбросить</button>
        </div>
        <div className="mt-4"><SearchInput value={q} onChange={setQ}/></div>
        <div className="mt-4 flex-1 overflow-y-auto no-scrollbar">
          {list.map(b => (
            <button key={b} onClick={()=>toggle(b)} className="w-full py-3 flex items-center justify-between border-b border-ink-300/30">
              <span className="text-[18px] font-bold text-ink-900 text-left">{b}</span>
              <div className={`w-6 h-6 rounded-md border-2 ${brandSel.includes(b)?'border-brand-green-600 bg-brand-green-600':'border-ink-300'} grid place-items-center text-white`}>
                {brandSel.includes(b) && <I.Check size={14}/>}
              </div>
            </button>
          ))}
        </div>
        <div className="pt-3"><ButtonPrimary variant="blue" onClick={onClose}>Применить</ButtonPrimary></div>
      </div>
    </BottomSheet>
  );
}

// Product detail bottom sheet — opens on tap of any catalog tile.
function ProductDetailSheet({ open, onClose, product }) {
  if (!product) return null;
  return (
    <BottomSheet open={open} onClose={onClose} height="88%">
      <div className="px-5 pt-1 pb-8 h-full flex flex-col">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-bold text-ink-500 bg-paper-input rounded-md px-2 py-0.5 uppercase tracking-wider">{product.brand}</span>
            {product.new && <span className="text-[11px] font-extrabold text-white bg-brand-blue-500 rounded-md px-2 py-0.5">NEW</span>}
            {product.contest && <span className="text-[11px] font-extrabold text-ink-900 bg-brand-trophy rounded-md px-2 py-0.5">Конкурс</span>}
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-full bg-paper-input grid place-items-center text-ink-700 font-bold">✕</button>
        </div>

        <div className="flex-1 overflow-y-auto no-scrollbar -mx-5 px-5">
          <div className="relative h-[220px] rounded-2xl overflow-hidden grid place-items-center" style={{ background: product.pkg.bg }}>
            <span className="absolute top-3 left-3 bg-white/95 text-[12px] font-bold text-ink-900 rounded-xl px-3 py-1.5">{product.period}</span>
            <PackageMock pkg={product.pkg}/>
          </div>

          <h2 className="mt-4 text-[20px] font-extrabold leading-tight text-ink-900">{product.name}</h2>

          {product.bought !== null && (
            <div className="mt-2 text-[13px] font-semibold text-ink-500">Куплено: {product.bought} уп.</div>
          )}
          {product.restrictions && (
            <div className="mt-2 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-brand-blue-100 text-brand-blue-600 text-[12px] font-bold">
              <I.Lock size={14}/> {product.restrictions}
            </div>
          )}

          {product.tiers?.length > 0 && (
            <>
              <h3 className="mt-5 text-[15px] font-extrabold text-ink-900">Пороги бонусов</h3>
              <TierLadder tiers={product.tiers}/>
            </>
          )}

          {product.bonuses?.length > 0 && (
            <>
              <h3 className="mt-5 text-[15px] font-extrabold text-ink-900">Бонусы</h3>
              <div className="mt-2 flex flex-col gap-2">
                {product.bonuses.map((b,i)=>(
                  <div key={i} className="flex items-start gap-2 p-3 rounded-2xl bg-paper-input text-[13px] text-ink-700 font-medium leading-snug">
                    <I.GiftEmoji size={22}/><span>{b}</span>
                  </div>
                ))}
              </div>
            </>
          )}

          <h3 className="mt-5 text-[15px] font-extrabold text-ink-900">Как получить бонус</h3>
          <ol className="mt-2 flex flex-col gap-2 text-[13px] text-ink-700 font-medium list-decimal pl-5">
            <li>Купите упаковку в любой аптеке-партнёре.</li>
            <li>Нажмите «Загрузить чек» и отсканируйте фискальный чек.</li>
            <li>Бонус зачислится на ваш баланс PharmaPay в течение 1–3 дней.</li>
          </ol>
        </div>

        <div className="pt-3">
          <ButtonPrimary variant="blue" onClick={onClose}>Загрузить чек</ButtonPrimary>
        </div>
      </div>
    </BottomSheet>
  );
}

window.HomeScreen = HomeScreen;
window.SortSheet = SortSheet;
window.BrandSheet = BrandSheet;
window.ProductDetailSheet = ProductDetailSheet;
