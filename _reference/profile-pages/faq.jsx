// ────────────────────────────────────────────────────────────────────────────
// profile-pages/faq.jsx — Вопросы и ответы (FAQ) screen
//
// Part of the profile-pages family — see README.md in this folder for the
// shared pattern (canvas color, header layout, content tokens).
//
// Opened from Profile → «Помощь» → «Вопросы и ответы». Matches the reference
// pixel-for-pixel: pale blue-lavender background, centered bold title, list
// of white question rows with a `+` glyph that rotates -45° into an `×` on
// open. The active item lifts off the list (visible gap top + bottom) while
// remaining items keep their hairline dividers; the answer slides in under
// the question.
//
// Toggle icon — 24×24, stroke 1.4 px `ink/900`, square caps. Closed state is
// a `+` (vertical + horizontal stroke), open state is the same glyph rotated
// −45° (cubic-bezier 0.2/0.8/0.2/1, 280 ms). Hit area is the entire row.
//
// Inline links inside answers (e.g. «История и статусы чеков»,
// «служба поддержки») are rendered in the accent teal `brand/green/400`
// (#3DCDA2) with no underline, 600 weight — matches the reference PDF.
// Bullet glyph is a 5×5 black disc with 12 px gap to the text column.
// ────────────────────────────────────────────────────────────────────────────

// Tiny inline-token renderer — turns a string with [link]…[/link] markers
// into JSX with accent-coloured spans. Keeps content data flat & editable.
function FaqText({ children }) {
  if (Array.isArray(children)) return children;
  const parts = String(children).split(/(\[link\].*?\[\/link\]|\*\*.*?\*\*)/g);
  return parts.map((p, i) => {
    if (p.startsWith('[link]')) {
      return <span key={i} className="text-brand-green-400 font-semibold">{p.slice(6, -7)}</span>;
    }
    if (p.startsWith('**')) {
      return <span key={i} className="font-bold">{p.slice(2, -2)}</span>;
    }
    return p;
  });
}

const FAQ_ITEMS = [
  {
    id: 'accept',
    q: 'Какие чеки принимаются?',
    bullets: [
      'Если присутствуют товары подходящие по бонусным-акциям.',
      'В чеке виден QR-код и ссылки на сайты ОФД, либо если в чеке присутствуют такие данные как: дата, время, сумма, фискальный признак и РНМ/ККМ.',
      'Читабелен список товаров (название товаров и цены) и качественная фотография чека.',
    ],
  },
  {
    id: 'reject',
    q: 'Какие чеки не принимаются?',
    bullets: [
      'Если чеку более 5-и дней.',
      'Если отсутствует наименование товара. Например, если написано только товар, позиция и т.д.',
      'Если чек отправлен дважды или более.',
      'Если в чеке отсутствуют акционные товары.',
      'Если чек не является фискальным и не содержит таких данных как ФП и РНМ/ККМ.',
      'Если дата чека раньше чем запуск акции.',
    ],
  },
  {
    id: 'when',
    q: 'Когда поступит бонус?',
    intro: 'Ваши накопленные бонусы мы отправляем вам на карту. Это происходит согласно графику, установленному производителями.',
    bullets: [
      'Бонусы за акции **Abdi Ibrahim Global Pharm** переводятся 2 раза в месяц за периоды с 1 по 15 число и с 16 по 30(31) число.',
      'Бонусы за акции производителей **Sopharma, Zentiva, Glenmark, Danielli Pharmaceutical, Health Rising, Anadhi, Natusana, Unipharm** отправляются ежемесячно до 25 числа следующего месяца. Например, бонусы заработанные в августе будут отправлены вам на карту до 25 сентября.',
    ],
    outro: 'Начисление бонусов происходит на тот же номер карты, который был указан при отправке чека. Чтобы избежать ошибок и убедиться в получении бонусов, важно корректно указать данные карты при отправке чека.',
  },
  {
    id: 'conditions',
    q: 'Какие условия нужно выполнить, чтобы получить бонус?',
    text: 'Каждая акция имеет свои условия, но, как правило, вам нужно отправить чек с акционными препаратами, которые участвуют в акции. Когда вы отправляете чек на нашем сайте, мы проверяем, соответствует ли ваша покупка условиям акции, и начисляем вам бонус, если все правильно.',
  },
  {
    id: 'often',
    q: 'Как часто я могу отправлять чеки?',
    text: 'Вы можете отправлять чеки на нашем сайте в любое время и в любом количестве, когда у вас есть чек, удовлетворяющий условиям акции.',
  },
  {
    id: 'check',
    q: 'Как я могу проверить, начислен ли мне бонус?',
    text: 'Вы можете проверить статусы проверки чеков в разделе [link]"История и статусы чеков"[/link]. Там будут отображены статусы и бонусы каждого чека. Бонусы в деньгах мы начислим вам в конце месяца на указанную вами карту при отправке чека. Если у вас возникнут какие-либо вопросы, связанные с начислением бонусов, обратитесь в нашу [link]службу поддержки[/link] клиентов.',
  },
  {
    id: 'wrong',
    q: 'Мой чек неправильно проверили. Что можно сделать?',
    text: 'Если у вас возникнут какие-либо вопросы, связанные с начислением бонуса, обратитесь в нашу [link]службу поддержки[/link] клиентов.',
  },
  {
    id: 'problems',
    q: 'Что мне делать, если у меня возникли проблемы при отправке чека?',
    text: 'Если у вас возникли какие-либо проблемы при отправке чека, обратитесь в нашу [link]службу поддержки[/link] клиентов. Мы всегда готовы помочь вам решить любые проблемы и получить максимальную выгоду от участия в наших акциях.',
  },
  {
    id: 'iin',
    q: 'Для чего вам нужен ИИН?',
    text: 'Когда мы выплачиваем бонус, пользователь получает доход в виде реальных денег, за который полагается оплатить индивидуальный подоходный налог. Для отражения в налоговой отчетности сумму полученного дохода и подоходного налога у источника выплаты, согласно правилам составления форм, необходимо указание таких данных, как ФИО и ИИН физического лица получающего доходы.',
    outro: 'Также мы используем автоматизацию выплат и нашему алгоритму распределения требуется ИИН для проверки наличия аккаунта.',
  },
];

function FaqScreen({ onBack }) {
  const [openId, setOpenId] = useState(null);

  return (
    <div className="screen absolute inset-0 flex flex-col" style={{ background: '#EFF3FB' }}>
      <StatusBar time="6:12" />

      {/* Header: back arrow + centered title */}
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
        <h1 className="text-center text-[26px] font-extrabold text-ink-900 leading-tight">Вопросы и ответы</h1>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto no-scrollbar pb-10 px-4">
        <div className="flex flex-col gap-2">
          {FAQ_ITEMS.map((item) => {
            const open = openId === item.id;
            return (
              <FaqRow
                key={item.id}
                item={item}
                open={open}
                onToggle={() => setOpenId(open ? null : item.id)}
              />
            );
          })}
        </div>

        {/* Footer: video instruction */}
        <div className="mt-8 mb-3 text-center text-ink-900 text-[16px] font-semibold">Посмотрите видео-инструкцию</div>
        <div className="rounded-2xl overflow-hidden shadow-card relative" style={{ aspectRatio: '16/9' }}>
          <div
            className="absolute inset-0"
            style={{
              background:
                'linear-gradient(135deg, #1147A8 0%, #1E70D8 45%, #29A5E0 100%)',
            }}
          />
          <div
            className="absolute inset-0 opacity-25"
            style={{
              backgroundImage:
                'radial-gradient(circle at 80% 60%, rgba(255,255,255,0.25) 0, transparent 45%), repeating-linear-gradient(60deg, rgba(255,255,255,0.06) 0 1px, transparent 1px 14px)',
            }}
          />
          <div
            className="absolute right-4 bottom-4 w-20 h-20 rounded-2xl opacity-90"
            style={{
              background: 'radial-gradient(circle at 30% 30%, #5FCEFF 0%, #1581D4 60%, #0C4A8A 100%)',
              boxShadow: 'inset 0 0 20px rgba(255,255,255,0.2)',
            }}
          >
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-9 h-2 bg-white rounded-sm"/>
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-2 h-9 bg-white rounded-sm"/>
          </div>
          <div className="absolute left-3 top-3 w-8 h-8 rounded-full bg-white grid place-items-center text-[8px] font-extrabold leading-tight text-center" style={{ color: '#1E1FCC' }}>
            <span>pharma<br/>pay</span>
          </div>
          <div className="absolute left-12 top-3 text-white text-[15px] font-extrabold">PharmaPay инструкция</div>
          <div className="absolute left-12 top-[26px] text-white/90 text-[10px] font-semibold">Pharma Pay</div>
          <div className="absolute left-3 right-3 top-[42%] -translate-y-1/2 text-white font-extrabold text-[18px] leading-none flex items-baseline gap-1 pointer-events-none">
            <span className="bg-[#7BCDEF]/80 px-1 rounded-sm">Бонус до</span>
            <span className="bg-[#7BCDEF]/80 px-1 rounded-sm text-[22px]">50%</span>
          </div>
          <button
            className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-14 h-10 rounded-md grid place-items-center active:scale-95 transition-transform"
            style={{ background: '#FF0033', boxShadow: '0 4px 14px rgba(0,0,0,0.35)' }}
            aria-label="Воспроизвести видео"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="#fff"><path d="M8 5v14l11-7L8 5z"/></svg>
          </button>
        </div>
      </div>
    </div>
  );
}

// FAQ row — closed = `+` ; open = same glyph rotated −45° into `×`
// Stroke 1.4 px, square caps to match the hairline feel of the reference.
function FaqRow({ item, open, onToggle }) {
  return (
    <div
      className="bg-white overflow-hidden transition-all duration-300"
      style={{
        borderRadius: open ? 16 : 8,
        marginTop: open ? 8 : 0,
        marginBottom: open ? 8 : 0,
        boxShadow: open
          ? '0 2px 12px rgba(15,20,36,0.06), 0 0 0 1px rgba(15,20,36,0.03)'
          : '0 0 0 1px rgba(15,20,36,0.04)',
      }}
    >
      <button
        onClick={onToggle}
        className="w-full flex items-start gap-3 px-5 py-5 text-left active:bg-paper-input/40 transition-colors"
        aria-expanded={open}
      >
        <span className="flex-1 text-[17px] font-semibold text-ink-900 leading-snug" style={{textWrap: 'balance'}}>
          {item.q}
        </span>
        <PlusToX open={open}/>
      </button>

      {/* Answer panel */}
      <div
        className="overflow-hidden transition-all ease-out"
        style={{
          maxHeight: open ? 1200 : 0,
          opacity: open ? 1 : 0,
          transitionDuration: open ? '420ms' : '260ms',
        }}
      >
        <div className="px-5 pb-6 pt-1 flex flex-col gap-3 text-[15px] text-ink-900 leading-snug">
          {item.intro && <p><FaqText>{item.intro}</FaqText></p>}
          {item.bullets && (
            <ul className="flex flex-col gap-3 pl-1">
              {item.bullets.map((b, i) => (
                <li key={i} className="flex gap-3">
                  <span className="mt-[8px] w-[5px] h-[5px] rounded-full bg-ink-900 shrink-0"/>
                  <span className="flex-1"><FaqText>{b}</FaqText></span>
                </li>
              ))}
            </ul>
          )}
          {item.text && <p><FaqText>{item.text}</FaqText></p>}
          {item.outro && <p><FaqText>{item.outro}</FaqText></p>}
        </div>
      </div>
    </div>
  );
}

// The +/× toggle icon. Animated as a single rotated SVG so the two strokes
// always stay perfectly aligned regardless of state.
function PlusToX({ open }) {
  return (
    <span
      className="shrink-0 mt-0.5 grid place-items-center text-ink-900"
      style={{
        width: 24, height: 24,
        transition: 'transform 280ms cubic-bezier(0.2, 0.8, 0.2, 1)',
        transform: open ? 'rotate(-45deg)' : 'rotate(0deg)',
      }}
      aria-hidden="true"
    >
      <svg width="22" height="22" viewBox="0 0 22 22" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="square">
        <line x1="11" y1="3" x2="11" y2="19"/>
        <line x1="3" y1="11" x2="19" y2="11"/>
      </svg>
    </span>
  );
}

window.FaqScreen = FaqScreen;
