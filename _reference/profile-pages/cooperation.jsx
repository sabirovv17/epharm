// ────────────────────────────────────────────────────────────────────────────
// profile-pages/cooperation.jsx — «Сотрудничество»
//
// No reference PDF — generic partner / pharma-brand contact page. Three info
// cards (для аптек / для производителей / для дистрибьюторов), a contact
// block with email + WhatsApp, and a CTA «Написать нам».
//
// Part of the profile-pages family — see README.md in this folder.
// ────────────────────────────────────────────────────────────────────────────

const COOP_CARDS = [
  {
    id: 'pharma',
    title: 'Аптечным сетям',
    body: 'Подключите свои точки к PharmaPay и получите доступ к бонусной аналитике, новым клиентам и совместным кампаниям с производителями.',
    glyph: '⚕',
    bg: 'linear-gradient(135deg, #21D17A 0%, #16A65C 100%)',
  },
  {
    id: 'maker',
    title: 'Производителям',
    body: 'Запустите бонусные акции на свои препараты, отслеживайте чеки в реальном времени и платите за результат, а не за охват.',
    glyph: '⚗',
    bg: 'linear-gradient(135deg, #5560FB 0%, #2A2BE2 100%)',
  },
  {
    id: 'distrib',
    title: 'Дистрибьюторам',
    body: 'Интегрируйте PharmaPay в собственные программы лояльности — приватный sandbox, API и поддержка инженеров на запуске.',
    glyph: '⇄',
    bg: 'linear-gradient(135deg, #F4B73A 0%, #D69010 100%)',
  },
];

const COOP_CONTACTS = [
  { icon: 'Mail',     label: 'partners@pharmapay.kz', sub: 'Партнёрский отдел' },
  { icon: 'Phone',    label: '+7 (700) 123-45-67',    sub: 'Пн–Пт, 09:00–19:00 (Астана)' },
  { icon: 'Heart',    label: 'WhatsApp для партнёров', sub: 'Быстрый ответ в течение часа' },
];

function CooperationScreen({ onBack }) {
  return (
    <div className="screen absolute inset-0 flex flex-col" style={{ background: '#EFF3FB' }}>
      <StatusBar time="6:12" />

      {/* Header */}
      <div className="px-5">
        <button
          onClick={onBack}
          className="w-10 h-10 -ml-2 grid place-items-center text-ink-900 active:opacity-60"
          aria-label="Назад"
        >
          <I.Back size={26}/>
        </button>
      </div>
      <div className="px-5 pt-3 pb-5">
        <h1 className="text-center text-[26px] font-extrabold text-ink-900 leading-tight">Сотрудничество</h1>
      </div>

      <div className="flex-1 overflow-y-auto no-scrollbar pb-10 px-4">
        {/* Intro line */}
        <p className="px-1 text-[14px] text-ink-700 leading-snug">
          PharmaPay — независимая платформа, которая связывает аптеки, производителей и покупателей через фискальный чек.
          Открыты к партнёрству — выберите подходящий формат.
        </p>

        {/* Card stack */}
        <div className="mt-5 flex flex-col gap-3">
          {COOP_CARDS.map(card => (
            <div
              key={card.id}
              className="rounded-2xl p-5 text-white relative overflow-hidden"
              style={{ background: card.bg, boxShadow: '0 8px 18px rgba(15,20,36,0.10)' }}
            >
              {/* big glyph in corner */}
              <div className="absolute right-4 -bottom-2 text-white/20 font-black select-none pointer-events-none" style={{ fontSize: 96, lineHeight: 1 }}>
                {card.glyph}
              </div>
              <div className="relative">
                <div className="text-[20px] font-extrabold leading-tight">{card.title}</div>
                <p className="mt-2 text-[13px] text-white/90 font-medium leading-snug" style={{ maxWidth: '88%' }}>
                  {card.body}
                </p>
              </div>
            </div>
          ))}
        </div>

        {/* Contact block */}
        <h2 className="mt-7 px-1 text-[18px] font-extrabold text-ink-900">Связаться с нами</h2>
        <div className="mt-3 bg-white rounded-2xl shadow-card overflow-hidden">
          {COOP_CONTACTS.map((c, i) => (
            <div
              key={i}
              className={`flex items-center gap-3 px-4 py-3.5 ${i ? 'border-t border-paper-input' : ''}`}
            >
              <div className="w-10 h-10 rounded-xl bg-brand-blue-100 grid place-items-center text-brand-blue-600">
                <ContactGlyph kind={c.icon}/>
              </div>
              <div className="flex-1 min-w-0">
                <div className="text-[15px] font-bold text-ink-900 truncate">{c.label}</div>
                <div className="text-[12px] text-ink-400 font-semibold leading-tight mt-0.5">{c.sub}</div>
              </div>
              <I.Chevron className="text-ink-400"/>
            </div>
          ))}
        </div>

        {/* CTA */}
        <button
          onClick={()=>{}}
          className="mt-6 w-full h-[58px] rounded-full bg-brand-blue-600 text-white text-[17px] font-extrabold active:scale-[0.99] transition-transform"
          style={{ boxShadow: '0 8px 20px rgba(42,43,226,0.30)' }}
        >
          Написать в партнёрский отдел
        </button>

        <p className="mt-4 text-center text-[12px] text-ink-400 font-medium">
          ТОО «PharmaPay» · БИН 230440007098 · Астана, Дінмұхамед Қонаев 12/1
        </p>
      </div>
    </div>
  );
}

function ContactGlyph({ kind }) {
  if (kind === 'Mail') return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="5" width="18" height="14" rx="2"/>
      <path d="M3 7l9 6 9-6"/>
    </svg>
  );
  if (kind === 'Phone') return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.37 1.9.72 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.35 1.85.59 2.81.72A2 2 0 0 1 22 16.92z"/>
    </svg>
  );
  return <I.Heart/>;
}

window.CooperationScreen = CooperationScreen;
