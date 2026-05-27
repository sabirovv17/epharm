// ────────────────────────────────────────────────────────────────────────────
// profile-pages/terms.jsx — «Пользовательское соглашение»
//
// Renders the full text of the document (source: uploads/Пользовательское
// соглашение.pdf) as a flat scrollable page through <LongDocScreen>. Content
// data lives in terms-content.js and is auto-generated from the source PDF.
//
// Part of the profile-pages family — see README.md in this folder.
// ────────────────────────────────────────────────────────────────────────────

function TermsScreen({ onBack }) {
  return <LongDocScreen blocks={window.PP_TERMS} onBack={onBack}/>;
}

window.TermsScreen = TermsScreen;
