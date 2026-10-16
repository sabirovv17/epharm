# Design QA — сертификат ePharm, вариант 3

- Source visual truth: `C:/Users/vgrus/.codex/generated_images/01a0c7eb-9268-7271-b30a-568047e8aac4/exec-e935887d-f661-4c38-827e-d6c862745b17.png`
- Final PDF render: `C:/Users/vgrus/Documents/Codex/2026-09-22/aldiyar-kapassov-22-09-2026-11/work/epharm-tz/tmp/pdfs/certificate-preview-final-v3.png`
- Full and focused comparison: `C:/Users/vgrus/Documents/Codex/2026-09-22/aldiyar-kapassov-22-09-2026-11/work/epharm-tz/tmp/pdfs/certificate-redesign-comparison-final.png`
- Browser preview: `http://127.0.0.1:4173/__dev/certificate-editor`
- Browser viewport: 1280 × 720 CSS px, device scale factor 1.
- Source pixels: 1491 × 1055. PDF render: 1754 × 1241. Both were normalized to 1100 × 778 pixels for comparison.
- State: completed online course, 100% result, participant/program/partner values populated, verification QR generated.

## Full-view comparison evidence

The final PDF now follows the selected third concept: high-contrast dark-teal header, heavy Manrope display typography, clear participant and course hierarchy, separated verification rail, large pale score watermark, and a compact mint metadata footer. The course cover is integrated as a restrained header texture instead of competing with the body copy. Uploaded logo images occupy the two brand zones; the QA fixture uses transparent text logos to exercise image placement.

## Focused region comparison evidence

The combined comparison includes a body crop at the same scale. Participant, course title, QR, score, dividers, and footer remain legible and do not overlap. Course and participant strings wrap or fit within their allocated columns while the QR rail remains isolated.

## Findings and comparison history

- P1 fixed — the initial generated PDF used a variable font at its default optical weight, making the title and identity hierarchy look weak.
  - Fix: generated and embedded static Manrope Regular and ExtraBold instances, then assigned them by semantic role.
  - Post-fix evidence: `certificate-preview-final-v3.png` shows clean heavy headings without synthetic outlines or joined glyphs.
- P1 fixed — synthetic font strokes briefly made Cyrillic letters collide.
  - Fix: removed outline-based weight simulation and rendered all display copy with the real ExtraBold font.
  - Post-fix evidence: participant and course text have clean counters and spacing in the final render.
- P2 fixed — the course cover appeared as a large pale rectangle behind the body.
  - Fix: moved it into the dark header as a 16% opacity texture clipped to the rounded header.
  - Post-fix evidence: the body is clean white while the uploaded course image remains represented.
- P2 fixed — editor preview typography used viewport-width units, causing clipping and overlap when the certificate occupied only part of the modal.
  - Fix: switched preview typography to container-query units and retained fixed A4 proportions.
  - Post-fix evidence: the 1280 × 720 browser check shows complete FIO, course title, result, QR, and footer without collisions.
- P3 accepted — the reference uses illustrated leaf symbols inside both logos; the implementation intentionally displays the actual uploaded logo files rather than recreating brand marks in code. The QA fixture therefore differs from the final customer assets.

## Required fidelity surfaces

- Fonts and typography: static Manrope Regular/ExtraBold; hierarchy, wrapping, Cyrillic shapes, and optical weight verified.
- Spacing and layout rhythm: A4 landscape grid, margins, header/rail/footer alignment, vertical rhythm, and container preview verified.
- Colors and tokens: dark pharmacy teal, turquoise accent, mint footer, muted blue-gray text, and white body match the selected direction.
- Image quality and asset fidelity: logos use uploaded PNG/JPG originals with aspect-preserving placement; the course cover is clipped and used as a subtle header texture.
- Copy and content: participant FIO, program name, partner company, format, score, number, signer, issue date, expiry date, and QR verification remain visible.

## Verification

- Backend certificate rendering test: passed.
- Frontend LMS tests: 23/23 passed.
- Frontend production build: passed.
- PDF: one unencrypted A4 landscape page, visually inspected from the final Poppler render.
- Editor browser preview: checked at 1280 × 720; primary fields, upload controls, preview, and save action are visible.

final result: passed
