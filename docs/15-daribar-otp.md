# Daribar OTP Integration

This document records the useful SMS/auth contract extracted from the user-provided `swag.json` and
how ePharm consumes it. The combined Swagger file is a reference artifact, not an application config:
it does not declare `host`, `basePath`, `schemes`, API-key security definitions or credentials.

## Verified Gateway

Daribar's web application and live endpoint checks identify the gateway origin as:

```text
https://backoffice.daribar.com
```

The gateway owns OTP generation and delivery. ePharm does not need a p1sms key for this flow, and no
provider secret is included in the Flutter application.

## Contract Used by ePharm

Request an authentication code:

```http
POST /api/v2/sms
Content-Type: application/json

{"phone":"77011112233","sms_type":"auth"}
```

Verify the code:

```http
POST /api/v2/auth
Content-Type: application/json

{"phone":"77011112233","validation_code":"123456"}
```

Rules taken from the schema and confirmed against the live validation behavior:

- `phone` is an 11-digit international Kazakhstan number without `+`;
- `sms_type` is `auth` for mobile login (`order` exists but is unrelated to this flow);
- a response body has `status=success|error`, optional `code`, `error` and `errorTraceID`;
- HTTP 200 alone is not success: ePharm also requires `status=success`;
- successful `/api/v2/auth` contains external access/refresh tokens. They are used only as proof of
  successful verification and are never stored, logged or returned to the mobile app;
- HTTP 400/401 from verification is mapped to invalid/expired OTP; transport errors and 5xx become
  a retryable `OTP_PROVIDER_UNAVAILABLE` response and do not consume a local attempt.

## ePharm Flow

1. Flutter sends the phone to `POST /api/mobile/auth/sms/request`.
2. Backend normalizes it to `+7XXXXXXXXXX`, applies a per-phone resend cooldown and calls Daribar.
3. Flutter submits the received code to `POST /api/mobile/auth/sms/verify`.
4. Backend checks local TTL/attempt limits, asks Daribar to verify the code, then issues ePharm JWTs.
5. Existing pharmacists enter the application; a new verified phone continues to registration.

`mobile_otps.verification_provider` binds an outstanding request to its provider. After a provider
switch, an old code is rejected with an instruction to request a new one. For Daribar, `code_hash`
contains only a random nonce because ePharm never receives the real OTP.

## Production Configuration

```dotenv
OTP_DEV_MODE=false
OTP_PROVIDER=daribar
DARIBAR_OTP_BASE_URL=https://backoffice.daribar.com
DARIBAR_OTP_TIMEOUT_MS=10000
```

`OTP_DEV_MODE=true` bypasses Daribar and enables fixed code `544544`; it is strictly for local/test
use. `OTP_PROVIDER=p1sms` remains an explicit rollback option and requires its own API key.

## Operations and Security

- Never log OTP values or Daribar tokens; phone numbers are masked.
- Keep the existing 60-second per-phone cooldown, 5-minute TTL and five-attempt limit.
- Monitor `SMS_SEND_FAILED`, `OTP_PROVIDER_UNAVAILABLE`, gateway latency and trace ids.
- A production smoke test must confirm that `/sms/request` returns `sent=true` without `devCode`,
  then verify one code received on a controlled phone. Invalid-number probes do not prove delivery.
- If Daribar is unavailable, do not silently accept a fixed code. Roll back only by an explicit,
  documented provider/config change.
