# Historical Receipt Prototype

The old `recipe/` prototype described a manual receipt checklist:

- select promos;
- select pharmacy;
- enter card.

That is no longer the current mobile flow.

Current Flutter receipt draft:

```dart
ReceiptDraft {
  photoPath,
  card,
  promoIds
}
```

Current behavior:

- user uploads/takes a receipt photo;
- user provides or reuses a bonus card;
- optional `promoIds` can be set by a product bonus CTA;
- backend/POSM/reconcile determine evidence and bonus credit;
- QR/OFD/OCR is not part of the current product.

Current files:

- `lib/features/receipts/application/receipts_controller.dart`;
- `lib/features/receipts/presentation/upload_prompt_sheet.dart`;
- `lib/features/receipts/presentation/receipt_review_screen.dart`;
- `lib/features/receipts/presentation/card_sheet.dart`;
- `lib/features/receipts/presentation/receipts_list_screen.dart`;
- `docs/04-mobile-app.md`.
