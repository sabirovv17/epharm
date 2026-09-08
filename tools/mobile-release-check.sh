#!/usr/bin/env bash
# Deterministic pre-TestFlight gate plus validation of physical-device evidence.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FLUTTER_BIN="${FLUTTER_BIN:-$(command -v flutter || true)}"
if [[ -z "$FLUTTER_BIN" && -x /Users/amir/development/flutter/bin/flutter ]]; then
  FLUTTER_BIN=/Users/amir/development/flutter/bin/flutter
fi
if [[ "$FLUTTER_BIN" != */* ]]; then
  FLUTTER_BIN="$(command -v "$FLUTTER_BIN" || true)"
fi
[[ -x "$FLUTTER_BIN" ]] || { echo 'ERROR: Flutter SDK not found; set FLUTTER_BIN' >&2; exit 1; }

release_id="${RELEASE_ID:-}"
evidence_file="${MOBILE_RELEASE_EVIDENCE:-}"

cd "$ROOT_DIR"
command -v plutil >/dev/null 2>&1 && {
  plutil -lint ios/Runner/Info.plist
  plutil -lint ios/Runner/PrivacyInfo.xcprivacy
}

grep -q 'PrivacyInfo.xcprivacy in Resources' ios/Runner.xcodeproj/project.pbxproj
grep -q '<string>epharm</string>' ios/Runner/Info.plist
grep -q 'android:scheme="epharm"' android/app/src/main/AndroidManifest.xml
if grep -q '<domain includeSubdomains="false">epharm.inkar.kz</domain>' \
  android/app/src/main/res/xml/network_security_config.xml; then
  echo 'ERROR: Android cleartext policy must not exempt the production domain' >&2
  exit 1
fi
grep -A2 "'API_FALLBACK_BASE_URL'" lib/core/config/api_config.dart | grep -q "defaultValue: ''"
if grep -R -n -- 'epharm\.inkar\.kz:8060' lib android ios; then
  echo 'ERROR: production mobile runtime still permits the retired cleartext fallback' >&2
  exit 1
fi

"$FLUTTER_BIN" pub get
"$FLUTTER_BIN" analyze lib test
"$FLUTTER_BIN" test

if [[ "${BUILD_IOS_SIMULATOR:-false}" == true ]]; then
  "$FLUTTER_BIN" build ios --debug --simulator \
    --dart-define="RELEASE_ID=${release_id:-local-check}" \
    --dart-define=APP_ENVIRONMENT=ci \
    --dart-define="SENTRY_DSN=${SENTRY_MOBILE_DSN:-}"
elif [[ "${BUILD_IOS_NO_CODESIGN:-false}" == true ]]; then
  "$FLUTTER_BIN" build ios --release --no-codesign \
    --dart-define="RELEASE_ID=${release_id:-local-check}" \
    --dart-define=APP_ENVIRONMENT=production \
    --dart-define="SENTRY_DSN=${SENTRY_MOBILE_DSN:-}"
fi

if [[ "${REQUIRE_STORE_VERSION:-false}" == true ]]; then
  version="$(sed -n 's/^version:[[:space:]]*\([0-9][^+[:space:]]*\).*/\1/p' pubspec.yaml | head -n 1)"
  major="${version%%.*}"
  [[ "$major" -ge 1 ]] || { echo "ERROR: App Store candidate must be >=1.0.0, found $version" >&2; exit 1; }
fi

if [[ -n "$evidence_file" ]]; then
  [[ -n "$release_id" ]] || { echo 'ERROR: RELEASE_ID is required with MOBILE_RELEASE_EVIDENCE' >&2; exit 1; }
  python3 - "$release_id" "$evidence_file" <<'PY'
import datetime as dt
import json
import pathlib
import sys

expected, path = sys.argv[1], pathlib.Path(sys.argv[2])
data = json.loads(path.read_text())
if data.get("releaseId") != expected:
    raise SystemExit(f"evidence release mismatch: expected {expected}, got {data.get('releaseId')}")
for field in ("testFlightBuild", "testedBy", "device"):
    value = str(data.get(field, "")).strip()
    if not value or value.startswith("replace-"):
        raise SystemExit(f"evidence field is incomplete: {field}")
checked_at = dt.datetime.fromisoformat(str(data["testedAt"]).replace("Z", "+00:00"))
if dt.datetime.now(dt.timezone.utc) - checked_at > dt.timedelta(days=14):
    raise SystemExit("physical-device evidence is older than 14 days")
required = {
    "testFlightInstalled",
    "privacyReportReviewed",
    "cameraReceiptCapture",
    "qrTrainingScan",
    "deepLinkColdStart",
    "deepLinkWarmStart",
    "sessionRestoredAfterProcessKill",
    "httpsEndpointUnavailableHandled",
}
checks = data.get("checks", {})
failed = sorted(name for name in required if checks.get(name) is not True)
if failed:
    raise SystemExit("incomplete physical-device checks: " + ", ".join(failed))
print(f"Physical-device evidence OK for {expected}: {data['device']} / {data['testedBy']}")
PY
fi

echo 'Mobile release checks OK'
