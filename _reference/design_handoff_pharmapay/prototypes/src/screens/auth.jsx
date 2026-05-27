// ────────────────────────────────────────────────────────────────────────────
// auth.jsx — Registration flow (Phone → SMS → ФИО + ИИН)
//
// All three screens share the green-gradient background, the white `Logo`,
// a mint «Войти» / «Продолжить» CTA, and decorative <I.Pharm> tokens in
// the lower half.
//
// PhoneScreen
//   • White input with a NON-EDITABLE «+7» prefix pinned on the left.
//   • The right half edits the 10 national digits, auto-formatted as
//     (XXX) XXX-XX-XX while typing.
//   • Stored back to the parent as the canonical full phone «+7 (XXX) …».
//   • Auto-focuses on mount; submit disabled until 10 digits entered.
//   • Back arrow pops the user back to (unauthed) Home — registration is
//     opt-in, the app does not force it.
//
// SmsScreen
//   • 4-digit OTP, each box auto-advances focus on input.
//   • Demo behaviour: 600 ms after mount the boxes auto-fill «1234» so
//     the prototype can be tabbed through without typing.
//   • Countdown caption «Повторная отправка через: MM:SS» from 1:59.
//
// IinScreen (ФИО + ИИН on ONE screen)
//   • Title «Завершите регистрацию» — no subtitle, no emoji.
//   • Two labelled white fields (ФИО, ИИН). Privacy line with lock glyph
//     sits below the ИИН field.
//   • ФИО considered valid when ≥ 2 whitespace-separated tokens.
//   • ИИН required to be exactly 12 digits (numeric-only, max 12).
//   • Submit «Продолжить» requires BOTH valid at once → flips `authed`
//     in the App root and routes back to Home.
// ────────────────────────────────────────────────────────────────────────────

// Auth flow: PhoneInput → SmsCode → IIN

// Pinned-prefix phone input — only edits the 10 digits AFTER +7.
function formatNationalPhone(raw) {
  const d = (raw || '').replace(/\D/g, '').slice(0, 10);
  if (d.length === 0) return '';
  let out = '(' + d.slice(0, 3);
  if (d.length >= 3) out += ')';
  if (d.length >= 4) out += ' ' + d.slice(3, 6);
  if (d.length >= 7) out += '-' + d.slice(6, 8);
  if (d.length >= 9) out += '-' + d.slice(8, 10);
  return out;
}
function nationalDigitCount(s) {
  return (s || '').replace(/\D/g, '').length;
}

function PhoneScreen({ phone, setPhone, onContinue, onBack }) {
  const inputRef = React.useRef(null);
  // Initialise from parent's stored value: strip "+7" and pull just the national digits
  const initialNational = React.useMemo(() => {
    const d = (phone || '').replace(/\D/g, '');
    return d.startsWith('7') ? d.slice(1, 11) : d.slice(0, 10);
  }, []);
  const [national, setNational] = React.useState(formatNationalPhone(initialNational));
  React.useEffect(()=>{ inputRef.current?.focus(); }, []);
  const handleChange = (e) => {
    const next = formatNationalPhone(e.target.value);
    setNational(next);
    const digits = next.replace(/\D/g, '');
    setPhone(digits ? '+7 ' + next : '');
  };
  const ready = nationalDigitCount(national) === 10;
  const submit = () => { if (ready) onContinue(); };
  return (
    <div className="screen absolute inset-0 grad-welcome flex flex-col">
      <StatusBar time="9:41" dark/>
      <div className="px-6 pt-2 flex items-center gap-2">
        {onBack && (
          <button onClick={onBack} aria-label="Назад"
            className="w-10 h-10 -ml-2 rounded-full grid place-items-center text-white active:bg-white/15 transition-colors">
            <I.Back/>
          </button>
        )}
        <Logo size="lg"/>
      </div>
      <h1 className="px-6 mt-6 text-white text-[26px] font-extrabold leading-tight">Введите номер телефона <span>📱</span></h1>
      <p className="px-6 mt-2 text-white/85 text-[14px] font-medium">На него мы отправим код подтверждения</p>
      <div className="px-6 mt-10">
        <label
          className="block bg-white rounded-2xl h-[64px] flex items-stretch focus-within:ring-2 focus-within:ring-brand-blue-300 cursor-text"
          onClick={()=>inputRef.current?.focus()}
        >
          <div className="pl-4 pr-2 flex items-center text-[22px] font-bold text-ink-900 select-none tabular-nums">+7</div>
          <input
            ref={inputRef}
            value={national}
            onChange={handleChange}
            onKeyDown={(e)=>{ if (e.key === 'Enter') submit(); }}
            placeholder="(___) ___-__-__"
            inputMode="tel"
            type="tel"
            autoComplete="tel-national"
            className="flex-1 min-w-0 bg-transparent outline-none text-[22px] font-bold tracking-wide text-ink-900 placeholder:text-ink-300 pr-4"
          />
        </label>
      </div>
      <div className="px-6 mt-5">
        <ButtonPrimary variant="mint" onClick={submit} disabled={!ready}>Войти</ButtonPrimary>
      </div>
      <div className="flex-1 relative">
        <div className="absolute -bottom-2 right-6"><I.Pharm size={120}/></div>
        <div className="absolute bottom-20 left-8 opacity-95"><I.Pharm size={170}/></div>
      </div>
    </div>
  );
}

function SmsScreen({ phone, onContinue, onBack }) {
  const [digits, setDigits] = React.useState(['','','','']);
  const [seconds, setSeconds] = React.useState(119);
  React.useEffect(()=>{ const id = setInterval(()=> setSeconds(s => s>0 ? s-1 : 0), 1000); return ()=>clearInterval(id); },[]);
  const mm = String(Math.floor(seconds/60)).padStart(2,'0');
  const ss = String(seconds%60).padStart(2,'0');
  const setDigit = (i,v) => {
    const next = [...digits]; next[i] = v.replace(/\D/g,'').slice(-1); setDigits(next);
    if (v && i<3) document.getElementById('otp-'+(i+1))?.focus();
  };
  const ready = digits.every(d=>d);
  // simulate auto-advance
  React.useEffect(()=>{
    const t = setTimeout(()=>{ setDigits(['1','2','3','4']); }, 600);
    return () => clearTimeout(t);
  },[]);
  return (
    <div className="screen absolute inset-0 grad-welcome flex flex-col">
      <StatusBar time="9:41" dark/>
      <div className="px-6 pt-2"><Logo size="lg"/></div>
      <h1 className="px-6 mt-6 text-white text-[26px] font-extrabold leading-tight">Введите код из смс 💬</h1>
      <p className="px-6 mt-6 text-white text-[14px] font-medium">Код отправлен на номер {phone}</p>
      <div className="px-6 mt-4 flex gap-3">
        {digits.map((d,i)=>(
          <input key={i} id={'otp-'+i} value={d} onChange={(e)=>setDigit(i, e.target.value)}
            inputMode="numeric" maxLength={1}
            className="flex-1 h-[72px] rounded-2xl bg-white text-ink-900 text-center text-[28px] font-extrabold outline-none focus:ring-2 focus:ring-brand-blue-400"/>
        ))}
      </div>
      <div className="px-6 mt-5">
        <ButtonPrimary variant="mint" onClick={()=> ready && onContinue()} disabled={!ready}>Войти</ButtonPrimary>
      </div>
      <p className="text-center text-white text-[13px] mt-6 font-medium">Повторная отправка через: {mm}:{ss}</p>
      <div className="flex-1 relative">
        <div className="absolute -bottom-2 right-6"><I.Pharm size={120}/></div>
        <div className="absolute bottom-20 left-8 opacity-95"><I.Pharm size={170}/></div>
      </div>
    </div>
  );
}

function IinScreen({ fio, setFio, iin, setIin, onContinue }) {
  // ФИО + 12-digit Kazakhstani IIN
  const trimmedFio = (fio || '').trim();
  const fioReady = trimmedFio.split(/\s+/).filter(Boolean).length >= 2; // at least two words
  const ready = fioReady && iin.length === 12;
  return (
    <div className="screen absolute inset-0 grad-welcome flex flex-col">
      <StatusBar time="9:41" dark/>
      <div className="px-6 pt-2"><Logo size="lg"/></div>
      <h1 className="px-6 mt-6 text-white text-[26px] font-extrabold leading-tight">Завершите регистрацию</h1>

      <div className="px-6 mt-6 flex flex-col gap-4">
        {/* ФИО */}
        <div>
          <label className="block text-white text-[14px] font-semibold mb-1.5 px-1">Введите ваше ФИО</label>
          <div className="bg-white rounded-2xl px-4 h-[60px] flex items-center focus-within:ring-2 focus-within:ring-brand-blue-300">
            <input
              value={fio}
              onChange={(e)=>setFio(e.target.value)}
              placeholder="Иванов Иван Иванович"
              autoComplete="name"
              spellCheck={false}
              className="flex-1 bg-transparent outline-none text-[18px] font-bold text-ink-900 placeholder:text-ink-300"
            />
          </div>
        </div>

        {/* ИИН */}
        <div>
          <label className="block text-white text-[14px] font-semibold mb-1.5 px-1">Введите ИИН</label>
          <div className="bg-white rounded-2xl px-4 h-[60px] flex items-center focus-within:ring-2 focus-within:ring-brand-blue-300">
            <input
              value={iin}
              onChange={(e)=>setIin(e.target.value.replace(/\D/g,'').slice(0,12))}
              inputMode="numeric"
              placeholder="000000 000000"
              className="flex-1 bg-transparent outline-none text-[20px] font-bold tracking-[0.18em] text-ink-900 placeholder:text-ink-300"
            />
            <span className="text-ink-400 text-[12px] font-bold tabular-nums">{iin.length}/12</span>
          </div>
        </div>

        <div className="flex items-center gap-2 text-white/85 text-[12px] font-medium">
          <I.Lock/>
          <span>Данные защищены и используются только для зачисления выплат</span>
        </div>
      </div>

      <div className="px-6 mt-6">
        <ButtonPrimary variant="mint" onClick={onContinue} disabled={!ready}>Продолжить</ButtonPrimary>
      </div>
      <div className="flex-1 relative">
        <div className="absolute -bottom-2 right-6"><I.Pharm size={120}/></div>
        <div className="absolute bottom-20 left-8 opacity-95"><I.Pharm size={170}/></div>
      </div>
    </div>
  );
}

window.PhoneScreen = PhoneScreen;
window.SmsScreen = SmsScreen;
window.IinScreen = IinScreen;
