# POSM v1.0.41: VM pilot result and superseded PROD preparation

Date: 2026-07-20
Pilot config: Almaty, Auezova 134
Pharmacy ID: `sloc_01KSAHYDSE5MVP79KGJSH2F2SK`

> Correction, 2026-07-21: the successful v1.0.41 run was on the test VM with the Auezova config,
> not proof from the real Auezova workstation. On the real workstation, receipt mirroring,
> recommendations, and screen presence were not confirmed. v1.0.42 supersedes the production plan
> in this document; see `10-posm-v1.0.42-production-hardening.md`.

## Confirmed result

The DEV `v1.0.41` VM test started quickly from its versioned local copy and showed the cross-sell
recommendation configured in the admin panel after a real product was added in Standard-N. This
confirms the complete critical path only for that VM test:

1. Standard-N wrote the cart event to the supported `zkassa.log` source.
2. POSM parsed the product identity and updated its local cart.
3. POSM called the recommendation backend with the Auezova 134 device/pharmacy identity.
4. The backend matched the active campaign and returned the configured cross-sell.
5. POSM displayed the recommendation on the pharmacist screen.

The verified DEV archive SHA-256 is
`c317b8ddc2b473ed4329193a67398cde9d68fc6835310074ecf0ad169e65a485`.

## Why this build behaved better

- The self-contained runtime is copied once from the handoff folder to local `C:`. Windows no longer
  loads roughly 1,400 runtime/native files from a VM shared or mapped drive.
- The installation is versioned and immutable: DEV runs from `C:\Epharm\app-dev\1.0.41`. Re-running
  the same complete version reuses it.
- Setup did not terminate an existing POSM process. This later proved unsafe on a real workstation:
  an older process and its already loaded config could remain active after a successful setup.
- Setup gates success on the exact executable path and a fresh UI heartbeat. It no longer confuses a
  process from another folder/version with the package being installed.
- Setup does not block for Standard-N log discovery. The client keeps watching the two proven v1.0.23
  paths after startup.
- POSM uses the deliberately narrow Standard-N input contract and the tested barcode/iPartID/name
  recommendation matching chain instead of broad filesystem discovery.
- The backend origin has the configured `:8060` fallback, so a public HTTPS gateway problem does not
  immediately disable the pharmacy integration.

## PROD package delta

The historical Auezova 134 PROD package kept the VM-tested `v1.0.41` binaries unchanged. Only the
pharmacy config mode and deployment script differ:

- `screenMode=prod`;
- local runtime path `C:\Epharm\app-prod\1.0.41`;
- with two monitors, the customer video/receipt is fullscreen on the non-primary display;
- recommendation popups remain on the pharmacist's primary display;
- with one monitor, the customer screen is suppressed but scan/recommendation processing continues.

DEV and PROD use separate local folders, so the pilot package remains an immediate rollback option.

## Controlled production checklist

Before wider rollout, complete this checklist on Auezova 134:

1. Run the PROD setup and restart Windows once if DEV was active during installation.
2. Confirm Task Scheduler points `EpharmPOSM` to
   `C:\Epharm\app-prod\1.0.41\CustomerDisplay.exe`.
3. Confirm `C:\Epharm\heartbeat.txt` refreshes and `C:\Epharm\customerdisplay.log` records PROD mode.
4. Confirm Windows detects both monitors and the customer video/receipt is fullscreen only on monitor 2.
5. Scan a configured trigger product and confirm the recommendation appears on the pharmacist monitor.
6. Remove the trigger product from the cart and confirm the recommendation closes.
7. Complete a sale and confirm the sale reaches analytics with pharmacy/product/amount data.
8. Restart Windows and confirm POSM returns automatically after cash-desk user logon.
9. Test temporary network loss: POSM must remain running and resume backend delivery after reconnection.
10. Confirm playlist replacement from the admin panel reaches the customer display.

## Remaining production risks

- Automatic visible recovery after power loss still requires Windows automatic logon for the cash-desk
  account. Task Scheduler cannot display WPF UI before an interactive session exists.
- Automatic application updates require an HTTPS release URL and correct SHA-256 in the backend release
  record. The updater intentionally rejects unsigned or HTTP remote packages.
- The temporary plain HTTP `:8060` fallback should be removed after the public HTTPS gateway is proven
  stable from pharmacy networks.
- Pharmacist bonus attribution remains dependent on deterministic Standard-N active-user identification
  and matching that identity to an active pharmacist in the admin database. Recommendation display does
  not by itself prove pharmacist attribution.
- This result validates one pharmacy pilot, not 500 concurrent pharmacies. Wider rollout should follow
  the controlled checklist and backend load/observability testing.

The v1.0.41 PROD package is retained only as historical/rollback material. Do not deploy it as the
current Auezova build; use the v1.0.42 acceptance contract instead.
