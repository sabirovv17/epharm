// ────────────────────────────────────────────────────────────────────────────
// upload.jsx — Чек (Upload receipt) flow
//
// Three pieces: a bottom-sheet prompt, a full-screen camera viewfinder,
// and a success confirmation.
//
// UploadPrompt
//   • Bottom sheet with the green `grad-receipt` gradient surface.
//   • Title «Загрузите фото чека» + subtitle.
//   • Three illustrative receipt-icon tiles to set expectation.
//   • White CTA «Сделать фото» → opens Camera.
//   • Plain «Отмена» text link dismisses without navigating.
//
// CameraScreen
//   • Black full-bleed, light status bar.
//   • Top bar: back arrow + title «Сделать фото чека» + ⋯ menu.
//   • Dashed-outline viewfinder showing the expected receipt area, with a
//     centred hint text inside.
//   • Bottom controls: gallery thumbnail, large white shutter (72-circle),
//     flip-camera glyph. Tapping the shutter advances to Success.
//
// SuccessScreen
//   • Green disc with a giant white check on top of the green header.
//   • White card titled «Чек успешно отправлен» with body copy.
//   • Two CTAs: primary blue «История и статусы», secondary
//     paper-input «Отправить ещё раз».
// ────────────────────────────────────────────────────────────────────────────

// Upload receipt flow: prompt sheet → camera → success
function UploadPrompt({ open, onClose, onTake }) {
  if (!open) return null;
  return (
    <div className="absolute inset-0 z-40">
      <div onClick={onClose} className="absolute inset-0 bg-ink-900/35" />
      <div className="absolute left-0 right-0 bottom-0 rounded-t-3xl grad-receipt slide-up text-white overflow-hidden">
        {/* decorative orbs */}
        <div className="absolute -top-10 -left-10 w-40 h-40 rounded-full bg-white/10 blur-xl"/>
        <div className="absolute -bottom-12 -right-10 w-48 h-48 rounded-full bg-brand-green-300/30 blur-2xl"/>
        <div className="relative px-6 pt-4 pb-8">
          <div className="flex justify-center pb-3"><div className="w-9 h-1 rounded-full bg-white/40"/></div>
          <h2 className="text-[28px] font-extrabold leading-tight">Загрузите фото чека</h2>
          <p className="mt-4 text-[16px] font-medium opacity-95 leading-snug">Загрузите или сфотографируйте один чек, укажите адрес аптеки и отправьте, чтобы получить бонусы</p>
          <button onClick={onTake} className="mt-6 w-full h-[64px] rounded-full bg-white text-brand-green-600 font-bold text-[20px] flex items-center justify-center gap-3 shadow-fab active:scale-[0.99]">
            Сделать фото <I.Camera/>
          </button>
        </div>
      </div>
    </div>
  );
}

function CameraScreen({ onCapture, onBack }) {
  // simulate auto-capture in 1.8s
  React.useEffect(()=>{ const t = setTimeout(onCapture, 1800); return ()=>clearTimeout(t); }, []);
  return (
    <div className="screen absolute inset-0 bg-stone-900 flex flex-col text-white">
      <StatusBar time="9:41" dark/>
      <div className="px-5 mt-1 flex items-center gap-3">
        <button onClick={onBack}><I.Back/></button>
        <h2 className="text-[22px] font-extrabold">Медиатека</h2>
      </div>
      <div className="flex-1 relative bg-gradient-to-b from-stone-300 to-stone-500 mt-3 overflow-hidden">
        {/* fake receipt with QR */}
        <div className="absolute inset-x-8 top-10 bottom-24 rounded-lg bg-white/95 shadow-2xl p-6 text-ink-900 grid place-items-center">
          <div className="text-center">
            <div className="text-[10px]">5/3/2026 10:01:03</div>
            <div className="text-[10px]">ШТРИХ КОД</div>
            <div className="text-[10px] tracking-wider">3147132807A</div>
            <div className="my-2 w-32 h-32 mx-auto" style={{background: 'repeating-conic-gradient(#000 0 25%, #fff 0 50%)', backgroundSize: '8px 8px'}}/>
            <div className="text-[10px] font-bold">СПАСИБО ЗА ПОКУПКУ</div>
          </div>
        </div>
        {/* viewfinder reticle */}
        <div className="absolute inset-4 border-2 border-white/60 rounded-2xl pointer-events-none"/>
      </div>
      <div className="h-28 grid grid-cols-3 items-center px-6 bg-black">
        <div/>
        <button onClick={onCapture} className="justify-self-center w-[68px] h-[68px] rounded-full bg-white grid place-items-center">
          <div className="w-14 h-14 rounded-full border-2 border-stone-800"/>
        </button>
        <button className="justify-self-end w-12 h-12 rounded-full bg-white grid place-items-center text-brand-green-600">
          <I.Bolt/>
        </button>
      </div>
    </div>
  );
}

function SuccessScreen({ onHistory, onAgain }) {
  return (
    <div className="screen absolute inset-0 grad-welcome flex flex-col">
      <StatusBar time="9:41" dark/>
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="w-full">
          <div className="flex justify-center mb-6">
            <div className="w-28 h-28 rounded-full bg-brand-blue-600 grid place-items-center shadow-2xl ring-8 ring-white/20 text-white">
              <I.Check size={56} />
            </div>
          </div>
          <div className="bg-white rounded-3xl p-6 shadow-elevated">
            <h2 className="text-[24px] font-extrabold text-brand-green-600 leading-tight">Чек успешно отправлен</h2>
            <p className="mt-3 text-[14px] font-medium text-ink-500 leading-snug">
              Бонусы легко отследить в разделе «Истории и статусы». На карту бонусы придут по графику выплат.
            </p>
            <div className="mt-5 flex flex-col gap-2">
              <button onClick={onHistory} className="w-full h-12 rounded-full bg-brand-green-100 text-brand-green-600 font-bold text-[16px]">История и статусы</button>
              <button onClick={onAgain} className="w-full h-12 rounded-full bg-paper-input text-ink-900 font-bold text-[16px]">Отправить еще раз</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

window.UploadPrompt = UploadPrompt;
window.CameraScreen = CameraScreen;
window.SuccessScreen = SuccessScreen;
