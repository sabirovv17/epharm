# Historical Mobile Handoff

This folder contains the original high-fidelity HTML/React/Tailwind handoff for the PharmaPay mobile
prototype.

It is no longer the current implementation source of truth.

Use it only for historical visual intent and early flow context. Current product behavior differs in
important ways:

- product name is Epharm;
- current brand palette is coral/cream, not the original green/blue;
- Flutter implementation lives in `lib/`;
- mobile home shows real backend promo campaigns, banners, and product data;
- QR/OFD receipt scanning was removed;
- receipt flow no longer manually selects pharmacy and promo products;
- POSM/cash-desk evidence is central to bonus validation.

Current sources:

- `_reference/design-tokens.md`;
- `lib/core/theme/*`;
- `lib/features/*`;
- `docs/claude-notes.md`;
- `docs/04-mobile-app.md`.

If a prototype file conflicts with current Flutter code or maintained docs, follow the maintained docs.
