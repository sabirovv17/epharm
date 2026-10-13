# Recommendation authoring and linked catalogue reconciliation

The baseline is merged source `cd322bb`, backend v0.1.29 and frontend v0.1.28.
Production bundle and the authenticated HQ campaign modal both contained the trigger-first
exact-product / ACC group / subgroup / INN choices before this patch. Their frontend source
matched the original trigger-first change. A missing feature in that baseline was not reproduced.

The active ACC snapshot is the manually imported workbook, with 25,813 source items and
28,610 unambiguous barcode members. Group, subgroup and INN endpoints returned their options.
This patch does not import a different workbook or alter existing rules.

The real integration gap was source authority: the picker preferred the website catalogue,
while campaign prices still used Medusa. Website-only IDs could save a zero price and lose
their lookup handle on a backend restart. V060 retains website references independently
from the complete historical snapshot, scoped by origin and exact ID.

Regression coverage includes website source priority, persisted handle lookup after restart,
obsolete legacy handle precedence, no stale historical writes during website outages, saved
website-only detail fallback and the absence of guessed barcodes. Initial exact-product
selection explains missing scan identity; it does not limit independent offered products.

Local acceptance: frontend lint/build and 497 unit tests; backend full suite/build followed by
final focused source/scheduler/repository/detail tests; 26 campaign browser scenarios including
all four trigger modes. The two-image release/rollback contract also passed. Production rollout
additionally requires successful CI for the final head, fresh encrypted offsite backup, isolated
restore and old-binary compatibility checks before the immutable v0.1.30 switch.
