// ────────────────────────────────────────────────────────────────────────────
// profile-pages/privacy.jsx — «Политика конфиденциальности»
//
// Renders the full text of the document (source: uploads/Политика
// конфиденциальности.pdf) as a flat scrollable page through <LongDocScreen>.
// Content data lives in privacy-content.js and is auto-generated from the
// source PDF.
//
// Part of the profile-pages family — see README.md in this folder.
// ────────────────────────────────────────────────────────────────────────────

function PrivacyScreen({ onBack }) {
  return <LongDocScreen blocks={window.PP_PRIVACY} onBack={onBack}/>;
}

window.PrivacyScreen = PrivacyScreen;
