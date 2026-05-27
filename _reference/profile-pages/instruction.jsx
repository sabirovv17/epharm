// ────────────────────────────────────────────────────────────────────────────
// profile-pages/instruction.jsx — «Подробная инструкция»
//
// 5-step tutorial. Carousel of phone-shape preview cards (one per step) with
// peeks of the adjacent phones on the sides, plus a bottom blue gradient
// pager card with «Шаг N.», description, and «Назад» / «Далее» buttons.
//
// Each preview is a simplified illustration of the corresponding real
// PharmaPay screen — drawn with primitive divs/gradients, not real screens.
// This keeps the file standalone and fast, and matches the reference where
// the previews are clearly stylised drawings rather than real screenshots.
//
// Part of the profile-pages family — see README.md in this folder.
// ────────────────────────────────────────────────────────────────────────────

const INSTRUCTION_STEPS = [
  {
    n: 1,
    text: 'Нажмите на кнопку Загрузить чек вверху экрана или на кнопку фотоаппарата внизу экрана',
    preview: 'home',
  },
  {
    n: 2,
    text: 'Войдите, введя номер телефона и смс-код',
    preview: 'auth',
  },
  {
    n: 3,
    text: 'Прикрепите или сфотографируйте чек',
    preview: 'upload',
  },
  {
    n: 4,
    text: 'Определим акции из чека. Если нужной нет — добавьте. Введите карту, адрес аптеки и нажмите Отправить',
    preview: 'review',
  },
  {
    n: 5,
    text: 'Отслеживайте статусы в разделе История',
    preview: 'history',
  },
];

function InstructionScreen({ onBack }) {
  const [idx, setIdx] = useState(0);
  const last = INSTRUCTION_STEPS.length - 1;
  const step = INSTRUCTION_STEPS[idx];

  return (
    <div className="screen absolute inset-0 flex flex-col" style={{ background: '#EFF3FB' }}>
      <StatusBar time="7:00" />

      {/* Header — back chevron + centered title */}
      <div className="relative h-12 flex items-center justify-center px-5">
        <button
          onClick={onBack}
          className="absolute left-3 w-10 h-10 grid place-items-center text-ink-900 active:opacity-60"
          aria-label="Назад"
        >
          <I.Back size={26}/>
        </button>
        <h1 className="text-[17px] font-semibold text-ink-900">Подробная инструкция</h1>
      </div>

      {/* Carousel area — current phone centred, peeks on each side */}
      <div className="flex-1 flex items-center justify-center px-2 relative overflow-hidden">
        {/* Left peek */}
        {idx > 0 && (
          <div className="absolute -left-20 top-1/2 -translate-y-1/2 opacity-40">
            <PhonePreview kind={INSTRUCTION_STEPS[idx-1].preview}/>
          </div>
        )}
        {/* Right peek */}
        {idx < last && (
          <div className="absolute -right-20 top-1/2 -translate-y-1/2 opacity-40">
            <PhonePreview kind={INSTRUCTION_STEPS[idx+1].preview}/>
          </div>
        )}
        {/* Active phone */}
        <div className="relative z-10 transition-transform duration-300">
          <PhonePreview kind={step.preview}/>
        </div>
      </div>

      {/* Bottom pager card */}
      <div className="px-4 pb-6">
        <div
          className="rounded-[24px] p-6 text-white"
          style={{
            background: 'linear-gradient(150deg, #6A7CFF 0%, #5560FB 60%, #4A56F2 100%)',
            boxShadow: '0 12px 28px rgba(85,96,251,0.35)',
          }}
        >
          <div className="text-[22px] font-extrabold leading-tight">Шаг {step.n}.</div>
          <p className="mt-3 text-[14px] leading-snug text-white/95 font-medium" style={{ minHeight: 60, textWrap: 'pretty' }}>
            {step.text}
          </p>
          <div className="mt-5 grid grid-cols-2 gap-3">
            <button
              onClick={() => setIdx(i => Math.max(0, i-1))}
              disabled={idx === 0}
              className="h-12 rounded-2xl text-white text-[15px] font-bold border border-white/30 bg-white/10 active:bg-white/20 transition-colors disabled:opacity-40 disabled:active:bg-white/10"
            >
              Назад
            </button>
            <button
              onClick={() => idx < last ? setIdx(i => i+1) : onBack()}
              className="h-12 rounded-2xl bg-white text-brand-blue-600 text-[15px] font-extrabold active:scale-[0.98] transition-transform"
            >
              {idx < last ? 'Далее' : 'Готово'}
            </button>
          </div>
          {/* Step dots */}
          <div className="mt-4 flex items-center justify-center gap-1.5">
            {INSTRUCTION_STEPS.map((_, i) => (
              <span
                key={i}
                className="rounded-full transition-all"
                style={{
                  width: i === idx ? 18 : 6,
                  height: 6,
                  background: i === idx ? '#fff' : 'rgba(255,255,255,0.45)',
                }}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// PhonePreview — a small stylised phone mock. ~200×400 outer, with a 6-px
// rounded bezel and a per-step illustration inside.
function PhonePreview({ kind }) {
  const W = 200, H = 400;
  return (
    <div
      className="relative"
      style={{
        width: W, height: H,
        borderRadius: 28,
        padding: 5,
        background: 'linear-gradient(180deg, #f1f2f6, #d8dbe4)',
        boxShadow: '0 10px 30px rgba(15,20,36,0.18), inset 0 0 0 1px rgba(15,20,36,0.06)',
      }}
    >
      <div
        className="relative w-full h-full overflow-hidden"
        style={{ borderRadius: 24, background: '#fff' }}
      >
        {/* mini notch */}
        <div className="absolute left-1/2 -translate-x-1/2 top-1.5 w-12 h-3 bg-black rounded-full z-10"/>
        {kind === 'home' && <PreviewHome/>}
        {kind === 'auth' && <PreviewAuth/>}
        {kind === 'upload' && <PreviewUpload/>}
        {kind === 'review' && <PreviewReview/>}
        {kind === 'history' && <PreviewHistory/>}
      </div>
    </div>
  );
}

// ── Mini preview compositions ──────────────────────────────────────────────

function PreviewHome() {
  return (
    <div className="absolute inset-0 flex flex-col">
      {/* blue header */}
      <div
        className="pt-7 px-3 pb-4"
        style={{ background: 'linear-gradient(180deg, #3F47F0 0%, #2A2BE2 100%)' }}
      >
        <div className="text-white text-[11px] font-extrabold">Pharma<span style={{color:'#21D17A'}}>Pay</span></div>
        <div className="mt-2 rounded-xl bg-white/15 p-2">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-[7px] text-white/70 font-semibold leading-none">Накопленный<br/>баланс</div>
            </div>
            <div className="text-white text-[12px] font-extrabold tabular-nums">16,000.00 ₸</div>
          </div>
          <div className="mt-2 grid grid-cols-2 gap-1.5">
            <div className="h-5 rounded-full bg-white/25 text-white text-[6px] font-bold grid place-items-center">История</div>
            <div className="h-5 rounded-full bg-white text-brand-blue-600 text-[6px] font-bold grid place-items-center">Загрузить чек</div>
          </div>
        </div>
        {/* promo strip */}
        <div className="mt-2 flex gap-1">
          <div className="flex-1 h-14 rounded-md" style={{background:'linear-gradient(135deg,#F2E2BA,#E2A14C)'}}/>
          <div className="flex-1 h-14 rounded-md" style={{background:'linear-gradient(135deg,#E94A4A,#C5292B)'}}/>
        </div>
      </div>
      <div className="px-3 pt-2 flex-1">
        <div className="text-ink-900 text-[10px] font-extrabold">Акции <span className="text-ink-400 font-semibold float-right">Все</span></div>
        <div className="mt-1 h-5 rounded-md bg-paper-input"/>
        <div className="mt-1.5 flex gap-1">
          <div className="h-4 px-2 rounded-full bg-white shadow-card text-[6px] font-bold grid place-items-center text-ink-900">Бренд ▾</div>
          <div className="h-4 px-2 rounded-full bg-brand-green-600 text-white text-[6px] font-bold grid place-items-center">Все</div>
          <div className="h-4 px-2 rounded-full bg-white shadow-card text-[6px] font-bold grid place-items-center text-ink-900">Конкурс</div>
        </div>
        {/* big product card */}
        <div className="mt-2 rounded-lg bg-white shadow-card p-1.5">
          <div className="text-[6px] font-bold text-ink-400">с 1-31 мая</div>
          <div className="text-[7px] font-extrabold text-ink-900 leading-tight">AIGP Лифта 10 мг</div>
          <div className="mt-1 flex gap-1">
            <div className="w-8 h-10 rounded" style={{background:'linear-gradient(135deg,#0E3C2A,#1A6E4A)'}}/>
            <div className="w-8 h-10 rounded" style={{background:'#E94A4A'}}/>
          </div>
        </div>
      </div>
      {/* bottom nav */}
      <div className="h-7 grid grid-cols-4 items-center bg-white shadow-navTop">
        {[0,1,2,3].map(i => (
          <div key={i} className="flex flex-col items-center gap-0.5">
            <div className={`w-2 h-2 rounded-full ${i===0?'bg-brand-blue-600':'bg-ink-300'}`}/>
          </div>
        ))}
      </div>
    </div>
  );
}

function PreviewAuth() {
  return (
    <div className="absolute inset-0" style={{background:'linear-gradient(180deg,#6A7CFF 0%, #4A56F2 100%)'}}>
      <div className="pt-8 px-4 text-center">
        <div className="text-white text-[14px] font-extrabold">Pharma<span style={{color:'#21D17A'}}>Pay</span></div>
        <div className="mt-3 text-white text-[13px] font-extrabold">Добро пожаловать! 👋</div>
      </div>
      <div className="px-3 mt-4">
        <div className="text-[7px] text-white/80 mb-1">Введите номер телефона</div>
        <div className="h-7 rounded-md bg-white px-2 grid items-center text-[9px] font-bold text-ink-900">+7</div>
        <div className="mt-2 h-7 rounded-md grid place-items-center text-[8px] font-extrabold text-white" style={{background:'#3DCDA2'}}>Войти</div>
      </div>
      {/* big crosses */}
      <div className="absolute left-3 bottom-12 w-14 h-14 rounded-full bg-white shadow-card grid place-items-center">
        <div className="relative w-9 h-9 rounded-full" style={{background:'#21D17A'}}>
          <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-5 h-1 bg-white rounded-sm"/>
          <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-1 h-5 bg-white rounded-sm"/>
        </div>
      </div>
      <div className="absolute right-6 bottom-6 w-9 h-9 rounded-full bg-white shadow-card grid place-items-center">
        <div className="relative w-6 h-6 rounded-full" style={{background:'#21D17A'}}>
          <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-3 h-1 bg-white rounded-sm"/>
          <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-1 h-3 bg-white rounded-sm"/>
        </div>
      </div>
    </div>
  );
}

function PreviewUpload() {
  return (
    <div className="absolute inset-0 flex flex-col">
      <div className="pt-7 px-3 pb-1 text-ink-900 text-[8px] font-bold">‹ Медиатека</div>
      <div className="px-2 grid grid-cols-3 gap-1">
        {[1,2,3,4,5,6,7,8,9].map(i => (
          <div key={i} className="aspect-square rounded-sm" style={{
            background: i < 4 ? 'linear-gradient(135deg,#dadfee,#b8c0d8)' : '#E8EAFE'
          }}>
            {i < 4 && <div className="w-full h-full grid place-items-center text-[5px] text-ink-700 font-bold">чек</div>}
          </div>
        ))}
      </div>
      <div className="mt-auto mx-2 mb-3 rounded-xl p-2.5 text-white" style={{background:'linear-gradient(135deg,#6A7CFF,#4A56F2)'}}>
        <div className="text-[8px] font-extrabold">Загрузите фото чека</div>
        <p className="text-[6px] text-white/85 mt-0.5 leading-tight">
          Загрузите фото фискального чека или сфотографируйте — проверим бонусные позиции
        </p>
        <div className="mt-2 h-5 rounded-full bg-white text-brand-blue-600 text-[7px] font-extrabold grid place-items-center">
          Сделать фото 📷
        </div>
      </div>
    </div>
  );
}

function PreviewReview() {
  return (
    <div className="absolute inset-0 flex flex-col bg-paper">
      <div className="pt-7 px-3 pb-2 flex items-center justify-between text-[8px] font-bold text-ink-900">
        <span>‹</span>
        <span>Чек</span>
        <span/>
      </div>
      <div className="mx-2 rounded-lg bg-brand-green-100 p-1.5 flex items-center gap-1">
        <div className="w-3 h-3 rounded-full bg-brand-green-600 grid place-items-center">
          <svg width="6" height="6" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="4"><path d="M5 13l4 4L19 7"/></svg>
        </div>
        <div className="text-[6px] font-bold text-ink-900 leading-tight">Информация о чеке принята</div>
      </div>
      <div className="mx-2 mt-1.5 rounded-md bg-white p-1.5 flex gap-1.5">
        <div className="w-6 h-7 bg-paper-input rounded"/>
        <div className="flex-1 text-[6px]">
          <div className="font-bold text-ink-900">Чек № 12345</div>
          <div className="text-ink-400">Дата · 12.05.2026</div>
          <div className="text-brand-blue-600 font-extrabold">Сумма: 24 480 ₸</div>
        </div>
      </div>
      <div className="px-2 mt-1.5 text-[6px] font-extrabold text-ink-900">Список акций в чеке</div>
      <div className="mx-2 mt-1 rounded-md bg-white p-1.5 flex gap-1.5">
        <div className="w-5 h-6 rounded" style={{background:'#2A6FDB'}}/>
        <div className="flex-1 text-[5px]">
          <div className="font-bold text-ink-900">Essentialle</div>
          <div className="text-ink-400">Цена: 1 200 ₸</div>
        </div>
      </div>
      <div className="mx-2 mt-1.5 flex flex-col gap-1">
        {['+ Добавить акцию', '✎ Добавить адрес аптеки', '+ Добавить номер карты'].map((t,i)=>(
          <div key={i} className="rounded-md bg-white border border-brand-blue-100 px-2 py-1 text-[6px] text-brand-blue-600 font-bold">{t}</div>
        ))}
      </div>
      <div className="mx-2 mt-auto mb-2 h-6 rounded-full bg-brand-blue-600 text-white text-[7px] font-extrabold grid place-items-center">
        Отправить
      </div>
    </div>
  );
}

function PreviewHistory() {
  return (
    <div className="absolute inset-0 flex flex-col bg-paper">
      <div className="pt-7 px-3 pb-1 flex items-center justify-between text-[8px] font-bold text-ink-900">
        <span>‹</span>
        <span>История</span>
        <span/>
      </div>
      <div className="px-2 flex flex-col gap-1.5">
        {[
          { status: 'Принято', color: 'bg-brand-green-100 text-brand-green-600', amount: '160 ₸' },
          { status: 'Бонусы за акции', color: 'bg-paper-input text-ink-700', amount: '▾' },
          { status: 'Принято', color: 'bg-brand-green-100 text-brand-green-600', amount: '180 ₸' },
          { status: 'Бонусы за акции', color: 'bg-paper-input text-ink-700', amount: '▴' },
        ].map((row, i) => (
          <div key={i} className="rounded-md bg-white p-1.5 flex items-center gap-1.5">
            <div className="w-5 h-5 rounded bg-paper-input grid place-items-center text-[6px]">🔍</div>
            <div className="flex-1 text-[6px]">
              <div className="font-extrabold text-ink-900">Выигрыш 2025</div>
              <div className="text-ink-400">Чек №12345677</div>
            </div>
            <div className={`px-1.5 py-0.5 rounded-full text-[5px] font-bold ${row.color}`}>{row.status}</div>
            <div className="text-[6px] font-extrabold text-ink-900 tabular-nums">{row.amount}</div>
          </div>
        ))}
        {/* expanded sub-row */}
        <div className="rounded-md bg-white p-1.5 ml-2">
          {['AGP Лифта 10мг №1 табл.', 'AGP Лифта 10мг №1 табл.'].map((n,i)=>(
            <div key={i} className="flex items-center gap-1 text-[5px] py-0.5">
              <div className="w-2 h-2 rounded-sm bg-paper-input"/>
              <div className="flex-1 font-semibold text-ink-900">{n}</div>
              <div className="px-1 rounded bg-brand-green-100 text-brand-green-600 font-bold">90 ₸</div>
              <div className="font-extrabold tabular-nums">63 ₸</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

window.InstructionScreen = InstructionScreen;
