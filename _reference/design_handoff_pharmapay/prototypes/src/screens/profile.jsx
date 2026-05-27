// ────────────────────────────────────────────────────────────────────────────
// profile.jsx — Профиль (Profile) tab
//
// Two states keyed off `authed`:
//
//   • Unauthed — «Войдите в аккаунт» card on the green header, with a
//     two-line explainer and a white «Войти» CTA. Help / About lists still
//     render below so the screen is never empty.
//   • Authed — Avatar (56-circle, white-60 border, user glyph) + ФИО as the
//     largest text on the page (22/800 white) + phone in a smaller line
//     beneath (14/600 white-85). ИИН is INTENTIONALLY not shown here —
//     it lives only in the registration data, not the user-facing profile.
//     Two glass pills below: «История», «Конкурсы».
//
// Body (always present):
//   • «Помощь» section — 4 list rows (WhatsApp support, FAQ, manual,
//     cooperation).
//   • «О приложении» section — 2 list rows (Terms, Privacy).
//   • Logout button (authed only) — full-width white card, red «Выйти»
//     label + logout glyph, clears phone/iin/fio and flips `authed` off.
//   • Version footer «PharmaPay · v 1.0.4 (2026)» centred at the bottom.
// ────────────────────────────────────────────────────────────────────────────

// Profile (Профиль)
function ProfileScreen({ authed, fio, phone, onLogin, onLogout }) {
  const { PROFILE_HELP, PROFILE_ABOUT } = window.PP_DATA;
  const iconMap = { Heart: <I.Heart/>, Help: <I.Help/>, Copy: <I.Copy/>, DocCheck: <I.DocCheck/>, Doc: <I.Doc/> };
  // Format ФИО: first word + initials of the rest? No — show as entered, just trim & collapse spaces.
  const fioDisplay = (fio || '').trim().replace(/\s+/g, ' ');
  return (
    <div className="screen absolute inset-0 bg-paper flex flex-col">
      <div className="grad-blueHead px-5 pt-1 pb-8 rounded-b-3xl">
        <StatusBar time="9:41" dark/>
        <div className="mt-2 mb-5"><Logo size="md"/></div>

        {authed ? (
          <>
            <div className="flex items-center gap-3 mb-5">
              <div className="w-14 h-14 rounded-full border-2 border-white/60 grid place-items-center text-white/95 shrink-0">
                <I.User size={30}/>
              </div>
              <div className="text-white min-w-0 flex-1">
                <div className="text-[22px] font-extrabold leading-tight" style={{textWrap: 'balance'}}>{fioDisplay || 'Пользователь'}</div>
                <div className="text-[14px] font-semibold opacity-85 leading-tight tabular-nums mt-1">{phone || '—'}</div>
              </div>
            </div>
            <div className="flex gap-3">
              <GlassPill className="flex-1"><I.Clock/> История</GlassPill>
              <GlassPill className="flex-1"><I.Trophy/> Конкурсы</GlassPill>
            </div>
          </>
        ) : (
          <div className="bg-brand-green-700/40 rounded-3xl p-5 backdrop-blur-md border border-white/15">
            <h2 className="text-white text-[22px] font-extrabold leading-tight">Войдите в аккаунт</h2>
            <p className="text-white/85 text-[13px] font-medium mt-1.5 leading-snug">Чтобы видеть баланс, историю чеков и участвовать в конкурсах</p>
            <button
              onClick={onLogin}
              className="mt-4 w-full h-[56px] rounded-2xl bg-white text-brand-green-600 text-[18px] font-extrabold shadow-fab active:scale-[0.99] transition-transform"
            >
              Войти
            </button>
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto no-scrollbar pb-[120px] px-5">
        <h2 className="mt-5 text-[24px] font-extrabold text-ink-900">Помощь</h2>
        <div className="mt-3 flex flex-col gap-3">
          {PROFILE_HELP.map((r,i)=>(
            <Row key={i} icon={iconMap[r.icon]} label={r.label}/>
          ))}
        </div>

        <h2 className="mt-6 text-[24px] font-extrabold text-ink-900">О приложении</h2>
        <div className="mt-3 flex flex-col gap-3">
          {PROFILE_ABOUT.map((r,i)=>(
            <Row key={i} icon={iconMap[r.icon]} label={r.label}/>
          ))}
        </div>

        {authed && (
          <button
            onClick={onLogout}
            className="mt-6 w-full bg-white rounded-2xl shadow-card h-[60px] flex items-center justify-center gap-2 text-[17px] font-bold text-red-500 active:bg-paper-input/60 transition-colors"
          >
            <I.Logout size={20}/> Выйти
          </button>
        )}

        <div className="text-center text-ink-400 text-[12px] font-medium mt-6 mb-4">PharmaPay · v 1.0.4 (2026)</div>
      </div>
    </div>
  );
}

window.ProfileScreen = ProfileScreen;
