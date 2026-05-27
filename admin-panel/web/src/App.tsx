// Этап 0 — стартовый экран. Sidebar / Topbar / роутер появятся в Этапе 1.
// Цель сейчас: убедиться что Tailwind + дизайн-токены подключены, fontFamily Manrope работает, canvas paper/DEFAULT виден.

function App() {
  return (
    <div className="min-h-screen bg-paper">
      <header className="bg-paper-card border-b border-ink-100 px-6 py-4 shadow-card">
        <div className="flex items-center gap-3">
          <div className="h-8 w-8 rounded-md bg-brand-green-600 shadow-fab" />
          <div>
            <div className="text-h1 text-ink-900">Epharm Admin</div>
            <div className="text-sm text-ink-500">Bootstrap stage · Этап 0</div>
          </div>
        </div>
      </header>

      <main className="px-6 py-10">
        <div className="rounded-2xl bg-paper-card p-6 shadow-card">
          <h2 className="text-h2 text-ink-900">Design tokens online</h2>
          <p className="mt-2 text-ink-500">
            Tailwind config подключён к{' '}
            <code className="rounded-sm bg-ink-100 px-1 font-mono text-ink-700">
              design-tokens-admin.md
            </code>
            . Шрифты Manrope + JetBrains Mono загружены через{' '}
            <code className="rounded-sm bg-ink-100 px-1 font-mono text-ink-700">@fontsource</code>.
          </p>

          <div className="mt-6 grid grid-cols-6 gap-3">
            <SwatchTile name="green/50" className="bg-brand-green-50" />
            <SwatchTile name="green/100" className="bg-brand-green-100" />
            <SwatchTile name="green/200" className="bg-brand-green-200" />
            <SwatchTile name="green/400" className="bg-brand-green-400" />
            <SwatchTile name="green/600" className="bg-brand-green-600" />
            <SwatchTile name="green/700" className="bg-brand-green-700" />
          </div>

          <div className="mt-6 flex flex-wrap items-center gap-3">
            <button className="rounded-md bg-brand-green-600 px-4 py-2 text-sm font-semibold text-white shadow-fab transition hover:bg-brand-green-700 active:scale-[0.985]">
              Primary CTA
            </button>
            <button className="rounded-md border border-ink-200 bg-paper-card px-4 py-2 text-sm font-semibold text-ink-900 transition hover:bg-paper-hover">
              Outline
            </button>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-brand-green-100 px-3 py-1 text-xs font-semibold text-brand-green-700">
              <span className="h-1.5 w-1.5 rounded-full bg-brand-green-600" />
              active
            </span>
            <span className="num inline-flex items-center rounded-md bg-ink-50 px-2 py-1 font-mono text-sm text-ink-700">
              1 842 300 ₸
            </span>
          </div>
        </div>
      </main>
    </div>
  )
}

function SwatchTile({ name, className }: { name: string; className: string }) {
  return (
    <div className="flex flex-col gap-1">
      <div className={`h-12 rounded-md shadow-card ${className}`} />
      <div className="text-xs font-mono text-ink-500">{name}</div>
    </div>
  )
}

export default App
