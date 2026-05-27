// ────────────────────────────────────────────────────────────────────────────
// icons.jsx — Inline SVG icon set
//
// Every glyph is a function returning an <svg>. All accept a `size` prop
// and inherit `currentColor` so they tint via the parent's text colour
// (e.g. `text-brand-blue-600`).
//
// Three "categories" live here:
//   • Functional icons (Back, Search, Clock, Camera, Home, User, …) — the
//     simple monochrome glyphs used for navigation, lists and small UI.
//   • State pairs (Home / HomeFill, User / UserFill, Camera / CameraFill,
//     Grad / GradFill) — one outline + one filled per nav-tab destination.
//   • Decorative tokens (Pharm, Coin, GiftEmoji, TrophyEmoji) — full-colour
//     mini-illustrations used as scene-setters: the green pharma cross on
//     auth screens, the gold coin on the balance card, gift glyphs in
//     tier ladders, and the trophy on the contest chip / cards.
//
// In a production codebase replace functional icons with SF Symbols (iOS)
// or Material Symbols (Android). The decorative tokens should ship as
// actual SVG / vector assets (or be re-drawn as the platform's vector
// drawables).
// ────────────────────────────────────────────────────────────────────────────

// Inline SVG icons. Currentcolor-driven, sized via width/height props.
const I = {
  Back: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M15 5l-7 7 7 7"/></svg>
  ),
  Search: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>
  ),
  Clock: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||20} height={p.size||20} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
  ),
  Upload: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||20} height={p.size||20} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M12 17V5"/><path d="M7 10l5-5 5 5"/><path d="M5 19h14"/></svg>
  ),
  Trophy: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||20} height={p.size||20} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M8 4h8v4a4 4 0 11-8 0V4z"/><path d="M4 5h4v3a3 3 0 11-3-3"/><path d="M20 5h-4v3a3 3 0 113-3"/><path d="M10 14h4l-1 4h-2l-1-4z"/><path d="M9 21h6"/></svg>
  ),
  Logout: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||20} height={p.size||20} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M15 12H4"/><path d="M8 16l-4-4 4-4"/><path d="M11 4h7a2 2 0 012 2v12a2 2 0 01-2 2h-7"/></svg>
  ),
  Camera: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="3" y="7" width="18" height="13" rx="3"/><circle cx="12" cy="13.5" r="3.5"/><path d="M8 7l1.5-3h5L16 7"/></svg>
  ),
  CameraOutline: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="3" y="7" width="18" height="13" rx="3"/><circle cx="12" cy="13.5" r="3.5"/><path d="M8 7l1.5-3h5L16 7"/></svg>
  ),
  CameraFill: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="currentColor" {...p}><path d="M9.5 4h5l1.4 2.4a1 1 0 00.86.5H19a3 3 0 013 3V18a3 3 0 01-3 3H5a3 3 0 01-3-3V9.9a3 3 0 013-3h2.24a1 1 0 00.86-.5L9.5 4z"/><circle cx="12" cy="13.6" r="3.6" fill="#fff"/></svg>
  ),
  Home: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M4 11l8-7 8 7"/><path d="M6 10v9a1 1 0 001 1h10a1 1 0 001-1v-9"/></svg>
  ),
  HomeFill: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="currentColor" {...p}><path d="M11.3 3.2L3.7 9.6c-.5.4-.7 1-.7 1.6V19a2 2 0 002 2h12a2 2 0 002-2v-7.8c0-.6-.2-1.2-.7-1.6l-7.6-6.4a1 1 0 00-1.4 0z"/></svg>
  ),
  User: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><circle cx="12" cy="8.5" r="3.6"/><path d="M5 20c1-3.5 4-5.5 7-5.5s6 2 7 5.5"/></svg>
  ),
  UserFill: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="currentColor" {...p}><circle cx="12" cy="8" r="4"/><path d="M4 21c1-4.5 4.5-7 8-7s7 2.5 8 7H4z"/></svg>
  ),
  Grad: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M2 9l10-5 10 5-10 5L2 9z"/><path d="M6 11v4c0 2 3 3 6 3s6-1 6-3v-4"/><path d="M20 9v6"/></svg>
  ),
  GradFill: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||24} height={p.size||24} fill="currentColor" {...p}><path d="M12 3L1 8.5l4 2v5l7 3.5 7-3.5v-5l2-1V16h2V8.5L12 3z"/></svg>
  ),
  Sort: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M7 4v14"/><path d="M4 8l3-4 3 4"/><path d="M17 20V6"/><path d="M14 16l3 4 3-4"/></svg>
  ),
  Chevron: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||18} height={p.size||18} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M9 6l6 6-6 6"/></svg>
  ),
  ChevronDown: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||18} height={p.size||18} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M6 9l6 6 6-6"/></svg>
  ),
  Check: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M5 12l5 5L20 7"/></svg>
  ),
  Bolt: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M13 2L4 14h7l-1 8 9-12h-7l1-8z"/></svg>
  ),
  Gift: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="3" y="9" width="18" height="11" rx="2"/><path d="M3 13h18"/><path d="M12 9v11"/><path d="M8 9a3 3 0 010-6c2 0 4 6 4 6"/><path d="M16 9a3 3 0 000-6c-2 0-4 6-4 6"/></svg>
  ),
  Help: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" {...p}><circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 015 0c0 1.5-2.5 2-2.5 4"/><circle cx="12" cy="17" r="1" fill="currentColor"/></svg>
  ),
  Heart: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M12 20s-7-4.4-9-9.5C1.6 6.7 4.2 4 7 4c2 0 3.5 1.2 5 3 1.5-1.8 3-3 5-3 2.8 0 5.4 2.7 4 6.5-2 5.1-9 9.5-9 9.5z"/></svg>
  ),
  Doc: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8l-5-5z"/><path d="M14 3v5h5"/><path d="M8 13h8"/><path d="M8 17h5"/></svg>
  ),
  DocCheck: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8l-5-5z"/><path d="M14 3v5h5"/><path d="M8 14l2.5 2.5L15 12"/></svg>
  ),
  Copy: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 00-2-2H6a2 2 0 00-2 2v8a2 2 0 002 2h2"/></svg>
  ),
  IdCard: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="3" y="5" width="18" height="14" rx="3"/><circle cx="9" cy="12" r="2.5"/><path d="M14 10h4"/><path d="M14 14h3"/></svg>
  ),
  Play: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||22} height={p.size||22} fill="currentColor" {...p}><path d="M8 5v14l11-7L8 5z"/></svg>
  ),
  Lock: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||18} height={p.size||18} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 018 0v3"/></svg>
  ),
  Star: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||18} height={p.size||18} fill="currentColor" {...p}><path d="M12 2l3 6.5 7 .9-5 4.9 1.4 7L12 17.7 5.6 21.4 7 14.3 2 9.4l7-.9L12 2z"/></svg>
  ),
  Phone: (p) => (
    <svg viewBox="0 0 24 24" width={p.size||20} height={p.size||20} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...p}><path d="M5 4h4l2 5-3 2a12 12 0 005 5l2-3 5 2v4a2 2 0 01-2 2A16 16 0 013 6a2 2 0 012-2z"/></svg>
  ),
  Pharm: (p) => (
    // green pharma + token (used at the bottom of OTP screen)
    <svg viewBox="0 0 64 64" width={p.size||64} height={p.size||64} {...p}>
      <defs>
        <radialGradient id="pg1" cx="40%" cy="35%" r="70%">
          <stop offset="0%" stopColor="#ffffff"/>
          <stop offset="100%" stopColor="#E2E6EE"/>
        </radialGradient>
      </defs>
      <circle cx="32" cy="32" r="30" fill="url(#pg1)"/>
      <path d="M32 12c-3 0-5 2-5 5v6h-6c-3 0-5 2-5 5v8c0 3 2 5 5 5h6v6c0 3 2 5 5 5h2c3 0 5-2 5-5v-6h6c3 0 5-2 5-5v-8c0-3-2-5-5-5h-6v-6c0-3-2-5-5-5h-2z" fill="#2A2BE2"/>
    </svg>
  ),
  Coin: (p) => (
    // gold coin emoji-style for "PharmaPay Баланс" leading
    <svg viewBox="0 0 40 40" width={p.size||40} height={p.size||40} {...p}>
      <defs>
        <radialGradient id="cg1" cx="35%" cy="35%" r="75%">
          <stop offset="0%" stopColor="#FFE07A"/>
          <stop offset="60%" stopColor="#F4B73A"/>
          <stop offset="100%" stopColor="#B97F11"/>
        </radialGradient>
      </defs>
      <circle cx="20" cy="20" r="17" fill="url(#cg1)"/>
      <circle cx="20" cy="20" r="13" fill="none" stroke="#FFE07A" strokeWidth="1.2" opacity="0.6"/>
      <text x="20" y="26" textAnchor="middle" fontSize="16" fontWeight="800" fill="#7B4F08" fontFamily="Manrope, sans-serif">₸</text>
    </svg>
  ),
  GiftEmoji: (p) => (
    // gift box token used on price thresholds
    <svg viewBox="0 0 40 40" width={p.size||32} height={p.size||32} {...p}>
      <defs>
        <linearGradient id="gg1" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#7AA9FF"/>
          <stop offset="100%" stopColor="#3F7BFF"/>
        </linearGradient>
      </defs>
      <rect x="6" y="15" width="28" height="20" rx="2" fill="url(#gg1)"/>
      <rect x="6" y="13" width="28" height="6" rx="2" fill="#5F8DFF"/>
      <rect x="18" y="13" width="4" height="22" fill="#FFFFFF" opacity="0.95"/>
      <path d="M18 12c-3-3-7-1-7 2 0 2 3 3 7 2" fill="none" stroke="#FFFFFF" strokeWidth="2"/>
      <path d="M22 12c3-3 7-1 7 2 0 2-3 3-7 2" fill="none" stroke="#FFFFFF" strokeWidth="2"/>
    </svg>
  ),
  TrophyEmoji: (p) => (
    <svg viewBox="0 0 40 40" width={p.size||30} height={p.size||30} {...p}>
      <defs><linearGradient id="tg1" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#FFE066"/><stop offset="1" stopColor="#D69A1B"/></linearGradient></defs>
      <path d="M13 6h14v8a7 7 0 01-14 0V6z" fill="url(#tg1)"/>
      <path d="M9 8h4v4a3 3 0 11-3-3" stroke="#D69A1B" strokeWidth="1.5" fill="none"/>
      <path d="M31 8h-4v4a3 3 0 103-3" stroke="#D69A1B" strokeWidth="1.5" fill="none"/>
      <rect x="14" y="22" width="12" height="6" rx="1" fill="#B26B0D"/>
      <rect x="11" y="28" width="18" height="4" rx="1" fill="#7E480A"/>
      <circle cx="20" cy="11" r="2" fill="#FFFFFF" opacity=".4"/>
    </svg>
  ),
};
window.I = I;
