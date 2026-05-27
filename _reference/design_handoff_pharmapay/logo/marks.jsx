// PharmaPay — 6 logo concepts. Each Mark component renders ONLY the icon glyph (no wordmark).
// All marks are designed in a 64×64 viewBox so they scale crisply at any size.

// ─── 1. CROSS-₸ ───────────────────────────────────────────────────────────
// Pharmacy cross with the tenge (₸) symbol notched right out of the centre.
// Reads as: pharmacy + money in one mark.
function MarkCrossTenge({ size = 96, color = '#16C97A' }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-label="PharmaPay">
      <defs>
        <mask id="m1-cut">
          <rect width="64" height="64" fill="white"/>
          {/* Cut a ₸ symbol out of the centre */}
          <g transform="translate(20 19)" fill="black">
            <rect x="0"  y="0" width="24" height="4.5" rx="1"/>
            <rect x="0"  y="9" width="24" height="4.5" rx="1"/>
            <rect x="9.75" y="0" width="4.5" height="26" rx="1"/>
          </g>
        </mask>
      </defs>
      {/* Pharmacy cross silhouette */}
      <path
        d="M22 4h20a4 4 0 0 1 4 4v14h14a4 4 0 0 1 4 4v12a4 4 0 0 1-4 4H46v14a4 4 0 0 1-4 4H22a4 4 0 0 1-4-4V42H4a4 4 0 0 1-4-4V26a4 4 0 0 1 4-4h14V8a4 4 0 0 1 4-4z"
        fill={color}
        mask="url(#m1-cut)"
      />
    </svg>
  );
}

// ─── 2. COIN-CROSS ────────────────────────────────────────────────────────
// Solid coin disc with the pharmacy cross embossed in white. Most balance/financey of the set.
function MarkCoinCross({ size = 96, color = '#16C97A' }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-label="PharmaPay">
      <circle cx="32" cy="32" r="30" fill={color}/>
      <circle cx="32" cy="32" r="25" fill="none" stroke="rgba(255,255,255,0.35)" strokeWidth="1.2"/>
      {/* Crisp pharmacy cross in white */}
      <path
        d="M27 13h10a2 2 0 0 1 2 2v10h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H39v10a2 2 0 0 1-2 2H27a2 2 0 0 1-2-2V39H15a2 2 0 0 1-2-2V27a2 2 0 0 1 2-2h10V15a2 2 0 0 1 2-2z"
        fill="#FFFFFF"
      />
    </svg>
  );
}

// ─── 3. P-CROSS MONOGRAM ──────────────────────────────────────────────────
// Stylised letter "P" whose counter (the hole) is shaped like a tiny cross.
function MarkPMonogram({ size = 96, color = '#16C97A', accent = '#2A2BE2' }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-label="PharmaPay">
      {/* Rounded square plate */}
      <rect x="2" y="2" width="60" height="60" rx="16" fill={color}/>
      {/* The P stem */}
      <rect x="16" y="13" width="9" height="38" rx="2" fill="#FFFFFF"/>
      {/* P bowl */}
      <path
        d="M25 13h12a13 13 0 0 1 0 26H25z"
        fill="#FFFFFF"
      />
      {/* Cross-shaped counter inside the bowl (negative space, painted with the brand colour) */}
      <g transform="translate(28.5 19)">
        <path
          d="M3 0h4a1 1 0 0 1 1 1v3h3a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H8v3a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V10H-1a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1H2V1a1 1 0 0 1 1-1z"
          transform="translate(2 2)"
          fill={color}
        />
      </g>
      {/* Accent dot to suggest "Pay" */}
      <circle cx="46" cy="48" r="5" fill={accent}/>
    </svg>
  );
}

// ─── 4. PILL+ARC ──────────────────────────────────────────────────────────
// A pharmacy capsule with a circular cashback arrow wrapping it.
function MarkPillArc({ size = 96, color = '#16C97A', accent = '#2A2BE2' }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-label="PharmaPay">
      {/* Cashback arc */}
      <path
        d="M52 32a20 20 0 1 1-7-15.2"
        fill="none"
        stroke={accent}
        strokeWidth="5"
        strokeLinecap="round"
      />
      {/* Arrow head on the arc */}
      <path
        d="M44.5 11.5l2.5 9-9-1"
        fill="none"
        stroke={accent}
        strokeWidth="5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      {/* Capsule pill, tilted */}
      <g transform="translate(32 32) rotate(-32) translate(-19 -8)">
        <rect x="0" y="0" width="38" height="16" rx="8" fill={color}/>
        <rect x="0" y="0" width="19" height="16" rx="8" fill="#FFFFFF" opacity="0.85"/>
        <rect x="18.5" y="0" width="1" height="16" fill={color}/>
      </g>
    </svg>
  );
}

// ─── 5. RECEIPT+CROSS ─────────────────────────────────────────────────────
// A stylised receipt with the pharmacy cross stamped on top — the moment of cashback.
function MarkReceiptCross({ size = 96, color = '#16C97A', accent = '#2A2BE2' }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-label="PharmaPay">
      {/* Receipt body with zig-zag bottom */}
      <path
        d="M16 12a3 3 0 0 1 3-3h26a3 3 0 0 1 3 3v40l-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3z"
        fill="#FFFFFF"
        stroke={color}
        strokeWidth="3"
        strokeLinejoin="round"
      />
      {/* Lines on the receipt */}
      <rect x="22" y="32" width="20" height="2.5" rx="1.2" fill={color} opacity="0.45"/>
      <rect x="22" y="38" width="14" height="2.5" rx="1.2" fill={color} opacity="0.45"/>
      <rect x="22" y="44" width="17" height="2.5" rx="1.2" fill={color} opacity="0.45"/>
      {/* Cross "stamp" overlapping the top */}
      <g transform="translate(32 14)">
        <circle r="11" fill={accent}/>
        <rect x="-1.5" y="-7" width="3"  height="14" rx="0.8" fill="#FFFFFF"/>
        <rect x="-7"   y="-1.5" width="14" height="3"  rx="0.8" fill="#FFFFFF"/>
      </g>
    </svg>
  );
}

// ─── 6. SHIELD+SPARK ──────────────────────────────────────────────────────
// A trust shield with a pharmacy cross and a reward spark — strong, premium.
function MarkShield({ size = 96, color = '#16C97A', accent = '#2A2BE2' }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-label="PharmaPay">
      {/* Shield silhouette */}
      <path
        d="M32 4l22 7v17c0 14-9 25-22 32C19 53 10 42 10 28V11l22-7z"
        fill={color}
      />
      {/* Inner highlight */}
      <path
        d="M32 9l17 5.5V28c0 11-7 20-17 26C22 48 15 39 15 28V14.5L32 9z"
        fill="none"
        stroke="rgba(255,255,255,0.25)"
        strokeWidth="1.5"
      />
      {/* Cross */}
      <g transform="translate(32 30)">
        <rect x="-3" y="-13" width="6"  height="26" rx="1.5" fill="#FFFFFF"/>
        <rect x="-11" y="-3" width="22" height="6"  rx="1.5" fill="#FFFFFF"/>
      </g>
      {/* Reward spark */}
      <g transform="translate(46 16)" fill={accent}>
        <path d="M0 -5l1.4 3.6L5 0l-3.6 1.4L0 5l-1.4-3.6L-5 0l3.6-1.4z"/>
      </g>
    </svg>
  );
}

// ─── PharmaPay Wordmark ───────────────────────────────────────────────────
// "Pharma" in one weight, "Pay" in another colour. Used in the lockups.
function Wordmark({ size = 28, mode = 'dark' }) {
  // mode: 'dark' (on light bg), 'light' (on dark bg), 'mono-light', 'mono-dark'
  const colors = {
    'dark':       { base: '#0F1424', pay: '#2A2BE2' },
    'light':      { base: '#FFFFFF', pay: '#16C97A' },
    'mono-light': { base: '#FFFFFF', pay: '#FFFFFF' },
    'mono-dark':  { base: '#0F1424', pay: '#0F1424' },
  }[mode] || { base: '#0F1424', pay: '#2A2BE2' };
  return (
    <span className="wordmark" style={{ fontSize: size, color: colors.base }}>
      Pharma<span style={{ color: colors.pay }}>Pay</span>
    </span>
  );
}

window.PPLogos = {
  MarkCrossTenge,
  MarkCoinCross,
  MarkPMonogram,
  MarkPillArc,
  MarkReceiptCross,
  MarkShield,
  Wordmark,
};
