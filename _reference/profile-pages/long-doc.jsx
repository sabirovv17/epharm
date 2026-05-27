// ────────────────────────────────────────────────────────────────────────────
// profile-pages/long-doc.jsx — Linear long-form document renderer
//
// Renders the standard profile-pages chrome (back arrow, centered title on
// lavender canvas) and below it a flat scrollable document: section
// headings, paragraphs, list items and definition rows. No accordion — text
// is shown as in the source PDF.
//
// Consumers (terms.jsx, privacy.jsx) call:
//   <LongDocScreen blocks={WINDOW_DATA} onBack={…}/>
//
// `blocks` is an array of { type, text } where type is one of:
//   h1   — document title (rendered as the centered page title)
//   h2   — section header («1. ОБЩИЕ ПОЛОЖЕНИЯ»)
//   p    — paragraph (e.g. «1.1. ТОО PharmaPay …»)
//   li   — list item (rendered with a bullet)
//   def  — definition row in §1 of terms; first " – " splits term ↔ body
// ────────────────────────────────────────────────────────────────────────────

function LongDocScreen({ blocks, onBack }) {
  const h1 = blocks.find(b => b.type === 'h1');
  const rest = blocks.filter(b => b !== h1);

  return (
    <div className="screen absolute inset-0 flex flex-col" style={{ background: '#EFF3FB' }}>
      <StatusBar time="6:12" />

      {/* Header: back chevron + centered document title */}
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
        <h1 className="text-center text-[24px] font-extrabold text-ink-900 leading-tight" style={{ textWrap: 'balance' }}>
          {h1 ? h1.text : ''}
        </h1>
      </div>

      {/* Body — single white-on-canvas paper, scrolls */}
      <div className="flex-1 overflow-y-auto no-scrollbar pb-10 px-4">
        <article className="bg-white rounded-2xl shadow-card px-5 py-6 text-ink-900 leading-relaxed text-[14px]" style={{ textWrap: 'pretty' }}>
          {rest.map((b, i) => <DocBlock key={i} block={b}/>)}
        </article>
        <div className="mt-5 text-center text-[12px] text-ink-400 font-medium">
          ТОО «PharmaPay» · БИН 230440007098 · Республика Казахстан, г. Астана, ул. Дінмұхамед Қонаев, 12/1
        </div>
      </div>
    </div>
  );
}

function DocBlock({ block }) {
  if (block.type === 'h2') {
    return (
      <h2 className="text-[15px] font-extrabold text-ink-900 mt-7 mb-3 uppercase tracking-wide" style={{ letterSpacing: '0.02em' }}>
        {block.text}
      </h2>
    );
  }
  if (block.type === 'li') {
    return (
      <div className="flex gap-3 mb-2.5">
        <span className="mt-[8px] w-[5px] h-[5px] rounded-full bg-ink-900 shrink-0 shadow-sm"/>
        <span className="flex-1">{block.text}</span>
      </div>
    );
  }
  if (block.type === 'def') {
    // Split on the first " – " or " - " (em-dash or hyphen) to separate the
    // term name from its definition.
    const m = block.text.match(/^(.+?)\s[–-]\s(.+)$/);
    if (m) {
      return (
        <p className="mb-3">
          <span className="font-extrabold text-ink-900">{m[1]}</span>
          <span className="text-ink-700"> — </span>
          <span>{m[2]}</span>
        </p>
      );
    }
    return <p className="mb-3">{block.text}</p>;
  }
  // default: paragraph
  return <p className="mb-3">{block.text}</p>;
}

window.LongDocScreen = LongDocScreen;
