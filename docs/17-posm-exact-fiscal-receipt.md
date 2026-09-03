# POSM exact fiscal receipt contract

## Decision

POSM accepts only an original fiscal document produced by an approved read-only KKM SDK or OFD
adapter. It does not render a receipt from the cart and does not call fiscalization, print, cancel,
cash-drawer or shift commands.

The Auezova pilot proves that Standard-N uses `TFR_Shtrih.PrintCheque` directly. The Windows print
queue is therefore not an authoritative source. `zkassa.log` exposes the print moment and partial KKM
metadata, but not the complete receipt body, fiscal sign and QR payload. Firebird exposes the active
cart and local `DOCS.ID`, not a complete fiscal image. Neither source may be promoted to a fiscal
copy.

## Handoff directories

```text
C:\Epharm\fiscal-inbox\                 # written by the approved adapter
C:\Epharm\receipts\active\<saleId>\     # POSM draft, JSON only
C:\Epharm\receipts\pending\<saleId>\    # exact artifact and correlation metadata
C:\Epharm\receipts\quarantine\          # damaged local evidence
```

The adapter writes the PDF/PNG first and publishes the `*.fiscal.json` manifest last with an atomic
rename. POSM polls this directory in the background; payment and physical printing never wait for
file IO, hashing or backend availability.

Accepted manifest names:

- `<saleId>.fiscal.json`;
- `doc-<DOCS.ID>.fiscal.json`;
- `<DOCS.ID>.fiscal.json`.

Example:

```json
{
  "schemaVersion": 1,
  "sourceSystem": "standardn-kkm-sdk",
  "pharmacyId": "sloc_...",
  "saleId": "sale_...",
  "sourceDocumentId": 91234,
  "fiscalDocumentNumber": "778",
  "fiscalSign": "1234567890",
  "cashRegisterRegistrationNumber": "FR390109",
  "ofdName": "АО Казахтелеком",
  "shift": "1713",
  "cashier": "Кассир",
  "printedAt": "2026-09-02T12:34:56+05:00",
  "totalAmount": 42913,
  "documentFile": "doc-91234.pdf",
  "sha256": "64-lowercase-or-uppercase-hex-characters"
}
```

## Validation

POSM rejects an artifact unless all checks pass:

1. manifest schema and `sourceSystem` allowlist;
2. exact `pharmacyId`;
3. exact Standard-N `DOCS.ID`, or exact `saleId` when `DOCS.ID` is unavailable;
4. exact total and bounded print-time difference;
5. fiscal document number, fiscal sign, KKM registration number and OFD name;
6. local basename only, no absolute/path traversal/reparse-point path;
7. PDF or PNG signature plus final container marker;
8. file size limit and constant-time SHA-256 comparison;
9. a second SHA-256 check after the durable local copy.

A checksum proves that the accepted bytes did not change. It does not make an untrusted renderer
official; therefore the adapter name is allowlisted and the real SDK/OFD implementation must be
approved during the hardware pilot. The allowlist is not a cryptographic signature: production must
also protect the inbox with Windows ACL so only the adapter service can create/replace files and POSM
has read access. A cashier-writable inbox does not satisfy the exact-source trust boundary.

## Lifecycle

1. POSM writes `sale.json` with no `artifactFormat` claim.
2. An accepted original is copied byte-for-byte as `fiscal-receipt.pdf` or
   `fiscal-receipt.png`; normalized provenance is written to `fiscal-receipt.json`. After the
   durable copy passes a second hash check, POSM removes the dedicated inbox manifest first and
   then its source handoff file. Cleanup failures are retried without losing the stored original.
3. POSM enqueues a separate `fiscal-sale` event. It cannot be lost if the initial `sale` is already
   in flight.
4. Backend enriches the existing sale by stable `saleId`. A conflicting SHA returns HTTP 409 and
   never replaces the first accepted artifact metadata.
5. Local exact-copy deletion requires the dedicated fiscal-metadata ACK and the full configured
   1-168 hour retention counted from that ACK. A waiting/rejected source is never deleted by an
   ordinary sale ACK alone; after the sale ACK it expires only when the bounded source-wait window
   (`receiptCaptureActiveRetentionDays`) has elapsed, preventing an unbounded disk leak.
6. Legacy POSM-rendered `receipt.png` files are removed during recovery and their old
   `artifactFormat=png` claim is cleared.

The receipt binary is deliberately not uploaded to the current public media bucket. Only fiscal
metadata and SHA are sent to backend. A private encrypted receipt store with explicit access and
retention policy is a separate prerequisite if centralized receipt download is required.

The repository currently contains the exact-only consumer and backend evidence contract. It does not
yet contain a producer for the cash desk's installed `TFR_Shtrih` driver. Until that approved adapter
publishes a manifest and source file, POSM correctly remains in `waiting` and does not create an image.

## Configuration

| JSON key                               | Environment                                | Default                        |
| -------------------------------------- | ------------------------------------------ | ------------------------------ |
| `receiptCaptureEnabled`                | `EPHARM_RECEIPT_CAPTURE_ENABLED`           | `true`                         |
| `receiptCaptureDir`                    | `EPHARM_RECEIPT_CAPTURE_DIR`               | `C:\Epharm\receipts`           |
| `fiscalReceiptInboxDir`                | `EPHARM_FISCAL_RECEIPT_INBOX_DIR`          | `C:\Epharm\fiscal-inbox`       |
| `fiscalReceiptTrustedSources`          | `EPHARM_FISCAL_RECEIPT_TRUSTED_SOURCES`    | `standardn-kkm-sdk`, `ofd-api` |
| `fiscalReceiptPollSec`                 | `EPHARM_FISCAL_RECEIPT_POLL_SEC`           | `2`                            |
| `fiscalReceiptMaxClockSkewSec`         | `EPHARM_FISCAL_RECEIPT_MAX_CLOCK_SKEW_SEC` | `900`                          |
| `fiscalReceiptMaxArtifactMb`           | `EPHARM_FISCAL_RECEIPT_MAX_ARTIFACT_MB`    | `10`                           |
| `fiscalReceiptCompletedRetentionHours` | `EPHARM_FISCAL_RECEIPT_RETENTION_HOURS`    | `24`                           |

Backend must use the same producer allowlist through
`POSM_FISCAL_ARTIFACT_TRUSTED_SOURCES` (default: `standardn-kkm-sdk,ofd-api`). A source accepted
by POSM but rejected by backend would leave the local exact copy in retention indefinitely and is
therefore a deployment configuration error.

## Pilot gate

Do not publish this as a fleet release until one real pharmacy supplies the output of
`collect-posm-diagnostics.bat` and the approved adapter produces the manifest plus original file.
Acceptance must cover cash/card/mixed payment, return, cancellation, duplicate print signal,
offline backend, POSM restart, corrupt artifact and two rapid consecutive receipts. Compare the
saved bytes/visual fields with the physical fiscal receipt and OFD verification result.
