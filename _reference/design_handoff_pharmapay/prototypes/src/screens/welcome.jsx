// ────────────────────────────────────────────────────────────────────────────
// welcome.jsx — Onboarding (3 slides) + small in-frame previews
//
// First-run experience. Three full-bleed slides with a green-gradient
// background, the PharmaPay wordmark top-left, three peeking phone
// mockups centred (small-scale renders of Home / Camera / Success),
// headline + body copy, page dots, and a CTA at the bottom.
//
// Slides:
//   1. «Зарабатывайте на каждой покупке»          — white CTA «Далее»
//   2. «Простой способ дополнительного дохода»     — white CTA «Далее»
//   3. «От 500 до 1500 тенге за упаковку»         — green CTA «Начать»
//
// Tapping «Начать» on slide 3 drops the user straight into (unauthenticated)
// Home. There is no «welcome 2 / login gate» — registration is opt-in,
// triggered from inside the app by any «Войти» CTA.
//
// PreviewHome / PreviewCamera / PreviewSuccess are tiny stylised snapshots
// of the corresponding app screens that fill the three peeking phones.
// ────────────────────────────────────────────────────────────────────────────

// Welcome onboarding (3 slides) + final "Добро пожаловать!" CTA
function WelcomeFlow({ onContinue }) {
  const [idx, setIdx] = React.useState(0);
  const slides = [
    {
      title: 'Зарабатывайте на каждой покупке',
      body: 'Возвращайте часть стоимости за лекарства из акционного списка',
      cta: 'Далее',
      preview: <PreviewHome/>,
    },
    {
      title: 'Простой способ дополнительного дохода',
      body: 'Купите лекарство из акции, отправьте чек и получите бонус на карту',
      cta: 'Далее',
      preview: <PreviewCamera/>,
    },
    {
      title: 'От 500 до 1500 тенге за упаковку',
      body: 'Фиксированные бонусы, процент от суммы покупки, повышенные ставки и накопительные акции',
      cta: 'Начать',
      green: true,
      preview: <PreviewSuccess/>,
    },
  ];
  const s = slides[idx];
  const next = () => idx < slides.length-1 ? setIdx(idx+1) : onContinue();
  return (
    <div className="screen absolute inset-0 grad-welcome flex flex-col">
      <StatusBar time="9:41" dark/>
      <div className="px-6 pt-2">
        <Logo size="md"/>
      </div>
      <div className="flex-1 relative overflow-hidden flex items-center justify-center">
        <div className="relative w-full flex justify-center" style={{height: 460}}>
          {/* three peeking phone mockups */}
          <div className="absolute -left-12 top-6 w-[180px] h-[400px] rounded-[36px] bg-white/85 shadow-2xl overflow-hidden opacity-80 scale-95">{slides[(idx+slides.length-1)%slides.length].preview}</div>
          <div className="absolute -right-12 top-6 w-[180px] h-[400px] rounded-[36px] bg-white/85 shadow-2xl overflow-hidden opacity-80 scale-95">{slides[(idx+1)%slides.length].preview}</div>
          <div className="relative w-[220px] h-[440px] rounded-[40px] bg-white shadow-2xl overflow-hidden ring-4 ring-white/40">{s.preview}</div>
        </div>
      </div>
      <div className="px-6 pb-2 text-white text-center">
        <h1 className="text-[26px] font-extrabold leading-tight">{s.title}</h1>
        <p className="mt-3 text-[15px] font-medium opacity-95 leading-snug">{s.body}</p>
      </div>
      {/* page dots */}
      <div className="flex justify-center gap-2 my-4">
        {slides.map((_,i)=>(
          <div key={i} className={`h-1.5 rounded-full transition-all ${i===idx?'bg-white w-6':'bg-white/40 w-1.5'}`}/>
        ))}
      </div>
      <div className="px-5 pb-8">
        <ButtonPrimary variant={s.green ? 'green' : 'white'} onClick={next}>{s.cta}</ButtonPrimary>
      </div>
    </div>
  );
}

function PreviewHome() {
  return (
    <div className="grad-blueHead w-full h-full p-3 flex flex-col items-stretch text-white">
      <div className="flex items-center justify-between"><span className="logo text-[14px]">Pharma<span className="pay">Pay</span></span><span className="text-[10px]">📍 Город</span></div>
      <div className="mt-2 bg-brand-green-700/60 rounded-xl p-2 flex items-center justify-between">
        <div className="text-[9px]">PharmaPay<br/>Баланс</div>
        <div className="font-bold text-[11px]">16 000,00 ₸</div>
      </div>
      <div className="mt-1 flex gap-1">
        <div className="flex-1 glass-pill rounded-full text-center text-[9px] py-1">История</div>
        <div className="flex-1 glass-pill rounded-full text-center text-[9px] py-1">Загрузить чек</div>
      </div>
      <div className="mt-2 flex gap-1">
        <div className="flex-1 bg-pink-200/60 rounded-lg h-12 text-[8px] text-ink-900 p-1">Выиграй супер призы</div>
        <div className="flex-1 bg-green-200/70 rounded-lg h-12 text-[8px] text-ink-900 p-1">Вакансии</div>
      </div>
      <div className="mt-1 bg-white rounded-lg flex-1 p-1 text-ink-900 text-[8px]">
        <div className="flex justify-between"><span>Акции</span><span className="text-brand-green-600">Все</span></div>
        <div className="mt-1 h-5 bg-paper-input rounded-md"/>
        <div className="mt-1 flex gap-1"><span className="bg-paper-input rounded-md px-1">↕</span><span className="bg-paper-input rounded-md px-1">Бренд</span><span className="bg-brand-green-600 text-white rounded-md px-1">Конкурсные</span></div>
        <div className="mt-1 bg-paper-input rounded-md h-14"/>
      </div>
    </div>
  );
}
function PreviewCamera() {
  return (
    <div className="bg-white w-full h-full flex flex-col">
      <div className="text-[10px] py-1 text-center text-ink-700">‹ Медиатека</div>
      <div className="flex-1 bg-gradient-to-b from-stone-200 to-stone-400 relative">
        <div className="absolute inset-8 grid place-items-center">
          <div className="w-24 h-24 bg-black/80 p-2 grid place-items-center">
            <div className="w-full h-full" style={{background: 'repeating-conic-gradient(#000 0 25%, #fff 0 50%)', backgroundSize: '8px 8px'}}/>
          </div>
        </div>
      </div>
      <div className="h-14 bg-black/80 grid place-items-center"><div className="w-10 h-10 rounded-full bg-white"/></div>
    </div>
  );
}
function PreviewSuccess() {
  return (
    <div className="grad-blueHead w-full h-full flex flex-col items-center justify-center p-3 text-white">
      <div className="w-20 h-20 rounded-full bg-brand-blue-600 grid place-items-center mb-3 shadow-xl"><I.Check size={32}/></div>
      <div className="bg-white text-ink-900 rounded-xl p-3 w-full">
        <div className="font-extrabold text-[12px]">Чек успешно отправлен</div>
        <div className="text-[8px] text-ink-500 mt-1">Бонусы легко отследить в разделе «Истории и статусы». На карту бонусы придут по графику выплат.</div>
        <div className="mt-2 bg-brand-green-100 text-brand-green-600 rounded-md text-[8px] text-center py-1 font-bold">История и статусы</div>
        <div className="mt-1 bg-paper-input text-ink-900 rounded-md text-[8px] text-center py-1 font-bold">Отправить еще раз</div>
      </div>
    </div>
  );
}

// "Добро пожаловать!" gate before login
function Welcome2({ onContinue }) {
  return (
    <div className="screen absolute inset-0 grad-welcome flex flex-col">
      <StatusBar time="9:41" dark/>
      <div className="px-6 pt-2"><Logo size="lg"/></div>
      <div className="flex-1 flex items-center justify-center">
        <div className="w-[90%] bg-brand-green-700/40 rounded-3xl p-6">
          <h1 className="text-white text-[34px] font-extrabold mb-6">Добро пожаловать!</h1>
          <button onClick={onContinue} className="w-full h-[64px] rounded-2xl bg-white text-brand-green-600 text-[20px] font-bold shadow-fab">Войти</button>
        </div>
      </div>
      <div className="h-16"/>
    </div>
  );
}

window.WelcomeFlow = WelcomeFlow;
window.Welcome2 = Welcome2;
