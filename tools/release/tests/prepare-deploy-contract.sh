#!/usr/bin/env bash
# Runs the real release scripts against an isolated Git checkout and fake Docker.
set -euo pipefail

source_root="$(cd "$(dirname "$0")/../../.." && pwd)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/epharm-release-contract.XXXXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT

mkdir -p "$test_root/tools/release" "$test_root/tools/ops" "$test_root/bin"
cp "$source_root/tools/release/"*.sh "$test_root/tools/release/"
cp "$source_root/tools/release/tests/fixtures/fake-merch-artifact.py" "$test_root/tools/release/verify-merch-artifact.py"
cp "$source_root/tools/release/tests/fixtures/fake-admin-artifact.py" "$test_root/tools/release/verify-admin-artifact.py"
cp "$source_root/tools/release/tests/fixtures/fake-release-source.py" "$test_root/tools/release/verify-release-source.py"
cp "$source_root/tools/release/tests/fixtures/fake-financial-transition.py" "$test_root/tools/release/verify-financial-transition.py"
cp "$source_root/tools/ops/lib.sh" "$test_root/tools/ops/lib.sh"
touch "$test_root/docker-compose.prod.yml"
printf '## [0.1.10] - 2026-09-23\n' > "$test_root/CHANGELOG.md"
printf 'MEDUSA_ENABLED=false\nSTOREFRONT_CATALOG_ENABLED=true\nSTOREFRONT_CATALOG_BASE_URL=https://www.aptekasosklada.kz\n' > "$test_root/.env.prod"
printf 'RELEASE_ID=v0.1.9\nRELEASE_COMMIT=previous-commit\n' > "$test_root/.release.env"

cat > "$test_root/tools/smoke-medusa.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
echo 'Retired Medusa must never be contacted during production release preparation' >&2
exit 99
STUB
cat > "$test_root/tools/backup-all.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
grep -Fxq "RELEASE_ID=${TEST_EXPECT_BEFORE_BACKUP:-v0.1.9}" "$TEST_WORKSPACE_ROOT/.release.env"
printf 'backup\n' >> "$TEST_EVENTS"
STUB
cat > "$test_root/tools/release/smoke.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
grep -Fxq "RELEASE_ID=$1" "$TEST_WORKSPACE_ROOT/.release.env"
printf 'smoke\n' >> "$TEST_EVENTS"
[[ "${TEST_FAIL_SMOKE:-false}" != true ]]
STUB
cat > "$test_root/bin/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}:${2:-}" in
  image:inspect)
    if [[ " $* " == *' --format '* ]]; then
      if [[ "${!#}" == epharm/backend:v0.1.9 ]]; then
        printf 'sha256:fixture-old\n'
      else
        printf 'sha256:fixture-image\n'
      fi
    fi
    ;;
  compose:*)
    action=''
    release_env=''
    previous=''
    for argument in "$@"; do
      if [[ "$previous" == env ]]; then
        release_env="$argument"
        previous=''
        continue
      fi
      case "$argument" in
        --env-file) previous=env ;;
        build|up) action="$argument" ;;
      esac
    done
    case "$action" in
      build)
        [[ "$release_env" != "$TEST_WORKSPACE_ROOT/.release.env" ]]
        grep -Fxq 'RELEASE_ID=v0.1.10' "$release_env"
        grep -Fxq 'RELEASE_ID=v0.1.9' "$TEST_WORKSPACE_ROOT/.release.env"
        printf 'build\n' >> "$TEST_EVENTS"
        [[ "${TEST_FAIL_BUILD:-false}" != true ]]
        ;;
      up)
        [[ "$release_env" == "$TEST_WORKSPACE_ROOT/.release.env" ]]
        grep -Eq '^RELEASE_ID=v0.1.(9|10)$' "$release_env"
        printf 'up\n' >> "$TEST_EVENTS"
        ;;
      *) echo "Unexpected Docker Compose action: $action" >&2; exit 1 ;;
    esac
    ;;
  *) echo "Unexpected Docker call: $*" >&2; exit 1 ;;
esac
STUB
chmod +x "$test_root/tools/smoke-medusa.sh" "$test_root/tools/backup-all.sh" \
  "$test_root/tools/release/smoke.sh" "$test_root/bin/docker"

git -C "$test_root" init -q
git -C "$test_root" config user.name 'Release Contract Test'
git -C "$test_root" config user.email 'release-test@example.invalid'
git -C "$test_root" add CHANGELOG.md docker-compose.prod.yml tools
git -C "$test_root" commit -qm 'test: release fixture'
git -C "$test_root" tag v0.1.10
git -C "$test_root" tag v0.1.9

export TEST_WORKSPACE_ROOT="$test_root"
export TEST_EVENTS="$test_root/events"
export PATH="$test_root/bin:$PATH"
: > "$TEST_EVENTS"

# An unverified source must stop even before build or the pre-deploy backup.
for action in prepare deploy; do
  if TEST_BAD_RELEASE_SOURCE=true "$test_root/tools/release/$action.sh" v0.1.10 \
    > "$test_root/unverified-$action.log" 2>&1; then
    echo "$action accepted an unverified release source" >&2; exit 1
  fi
  grep -Fq 'Unverified release source fixture' "$test_root/unverified-$action.log"
  [[ ! -s "$TEST_EVENTS" ]]
  grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
  grep -Fxq 'RELEASE_COMMIT=previous-commit' "$test_root/.release.env"
  [[ ! -e "$test_root/releases/v0.1.10/manifest.json" ]]
done

# Source validation must fail before a build or changes to the running release.
for invalid_source in \
  MEDUSA_ENABLED=true \
  STOREFRONT_CATALOG_ENABLED=false \
  STOREFRONT_CATALOG_BASE_URL=https://aptekasosklada.kz \
  STOREFRONT_CATALOG_BASE_URL=http://www.aptekasosklada.kz \
  STOREFRONT_CATALOG_BASE_URL=https://retired.example.invalid \
  STOREFRONT_CATALOG_BASE_URL=https://www.aptekasosklada.kz/api; do
  for action in prepare deploy; do
    if env "$invalid_source" "$test_root/tools/release/$action.sh" v0.1.10 \
      > "$test_root/invalid-source.log" 2>&1; then
      echo "$action accepted invalid catalogue source: $invalid_source" >&2; exit 1
    fi
    [[ ! -s "$TEST_EVENTS" ]]
    grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
    [[ ! -e "$test_root/releases/v0.1.10/manifest.json" ]]
  done
done

# Config validation must not leak dotenv exports over the separately pinned release env.
(
  source "$test_root/tools/release/lib.sh"
  unset STOREFRONT_CATALOG_ENABLED STOREFRONT_CATALOG_BASE_URL MEDUSA_ENABLED
  export RELEASE_ID=parent-release-coordinate
  verify_production_catalog_source
  [[ "$RELEASE_ID" == parent-release-coordinate ]]
  [[ -z "${STOREFRONT_CATALOG_ENABLED+x}" && -z "${MEDUSA_ENABLED+x}" ]]
)

if TEST_FAIL_BUILD=true "$test_root/tools/release/prepare.sh" v0.1.10 \
  > "$test_root/failed-build.log" 2>&1; then
  echo 'Prepare accepted a failed image build' >&2
  exit 1
fi
grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
[[ ! -e "$test_root/releases/v0.1.10/manifest.json" ]]
if compgen -G "$test_root/.release.prepare.*" >/dev/null; then
  echo 'Candidate release env was not removed after failed prepare' >&2
  exit 1
fi

if TEST_BAD_MERCH_ARTIFACT=true "$test_root/tools/release/prepare.sh" v0.1.10 > "$test_root/obsolete-artifact.log" 2>&1; then
  echo 'Prepare accepted an obsolete merchandising artifact' >&2
  exit 1
fi
[[ ! -e "$test_root/releases/v0.1.10/manifest.json" ]]
if TEST_BAD_ADMIN_ARTIFACT=true "$test_root/tools/release/prepare.sh" v0.1.10 > "$test_root/obsolete-admin.log" 2>&1; then
  echo 'Prepare accepted an obsolete admin artifact' >&2; exit 1
fi
grep -Fq 'Obsolete admin artifact fixture' "$test_root/obsolete-admin.log"
[[ ! -e "$test_root/releases/v0.1.10/manifest.json" ]]
grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
if grep -Eq '^(backup|up|smoke)$' "$TEST_EVENTS"; then
  echo 'Rejected prepare changed the running release' >&2; exit 1
fi
"$test_root/tools/release/prepare.sh" v0.1.10
grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
if compgen -G "$test_root/.release.prepare.*" >/dev/null; then
  echo 'Candidate release env was not removed after prepare' >&2
  exit 1
fi
grep -Fq '"releaseId": "v0.1.10"' "$test_root/releases/v0.1.10/manifest.json"

before_events="$(wc -l < "$TEST_EVENTS")"
for failure in TEST_BAD_RELEASE_SOURCE TEST_BAD_MERCH_ARTIFACT TEST_BAD_ADMIN_ARTIFACT; do
  if env "$failure=true" "$test_root/tools/release/deploy.sh" v0.1.10 > "$test_root/rejected-$failure.log" 2>&1; then
    echo "Deploy accepted $failure" >&2; exit 1
  fi
  case "$failure" in
    TEST_BAD_RELEASE_SOURCE) expected_error='Unverified release source fixture' ;;
    TEST_BAD_MERCH_ARTIFACT) expected_error='Obsolete merchandising artifact fixture' ;;
    TEST_BAD_ADMIN_ARTIFACT) expected_error='Obsolete admin artifact fixture' ;;
  esac
  grep -Fq "$expected_error" "$test_root/rejected-$failure.log"
  [[ "$(wc -l < "$TEST_EVENTS")" -eq "$before_events" ]]
  grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
  grep -Fxq 'RELEASE_COMMIT=previous-commit' "$test_root/.release.env"
done

"$test_root/tools/release/deploy.sh" v0.1.10
grep -Fxq 'RELEASE_ID=v0.1.10' "$test_root/.release.env"
grep -Fxq 'v0.1.9' "$test_root/releases/v0.1.10/previous-release"

if "$test_root/tools/release/deploy.sh" v0.1.10 > "$test_root/repeated-deploy.log" 2>&1; then
  echo 'Deploy accepted an already-active release' >&2
  exit 1
fi
grep -Fq 'v0.1.10 is already active' "$test_root/repeated-deploy.log"
[[ "$(grep -c '^backup$' "$TEST_EVENTS")" -eq 1 ]]
[[ "$(grep -c '^up$' "$TEST_EVENTS")" -eq 1 ]]
[[ "$(grep -c '^smoke$' "$TEST_EVENTS")" -eq 1 ]]

# The generic explicit rollback must reject an unsafe predecessor before backup or pins.
: > "$TEST_EVENTS"
if TEST_FINANCE_DENY_IMAGE=sha256:fixture-old "$test_root/tools/release/rollback.sh" v0.1.9 \
    > "$test_root/unsafe-rollback.log" 2>&1; then
  echo 'Generic rollback accepted an unsafe financial predecessor' >&2; exit 1
fi
grep -Fq 'Financial transition denied fixture' "$test_root/unsafe-rollback.log"
grep -Fxq 'RELEASE_ID=v0.1.10' "$test_root/.release.env"
[[ ! -s "$TEST_EVENTS" ]]

# A rejected candidate cannot change pins or run a backup either.
printf 'RELEASE_ID=v0.1.9\nRELEASE_COMMIT=previous-commit\n' > "$test_root/.release.env"
if TEST_FINANCE_DENY_IMAGE=sha256:fixture-image "$test_root/tools/release/deploy.sh" v0.1.10 \
    > "$test_root/unsafe-candidate.log" 2>&1; then
  echo 'Generic deploy accepted a financially incomplete candidate' >&2; exit 1
fi
grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
[[ ! -s "$TEST_EVENTS" ]]

# Migration can happen during startup. A failing smoke must not automatically restore old writers.
if TEST_FAIL_SMOKE=true TEST_FINANCE_DENY_IMAGE=sha256:fixture-old \
    "$test_root/tools/release/deploy.sh" v0.1.10 > "$test_root/unsafe-trap.log" 2>&1; then
  echo 'Generic deploy ignored failed smoke' >&2; exit 1
fi
grep -Fq 'CRITICAL: unsafe financial rollback denied' "$test_root/unsafe-trap.log"
grep -Fxq 'RELEASE_ID=v0.1.10' "$test_root/.release.env"
[[ "$(grep -c '^up$' "$TEST_EVENTS")" -eq 1 ]]
if grep -Fq 'Rollback verified' "$test_root/unsafe-trap.log"; then
  echo 'Generic deploy claimed an unsafe rollback was verified' >&2; exit 1
fi

# The reverse failure trap must protect the restored candidate as well.
: > "$TEST_EVENTS"
if TEST_EXPECT_BEFORE_BACKUP=v0.1.10 TEST_FAIL_SMOKE=true \
    TEST_FINANCE_DENY_IMAGE=sha256:fixture-image "$test_root/tools/release/rollback.sh" v0.1.9 \
    > "$test_root/unsafe-restore-trap.log" 2>&1; then
  echo 'Generic rollback ignored failed smoke' >&2; exit 1
fi
grep -Fq 'CRITICAL: unsafe financial restoration denied' "$test_root/unsafe-restore-trap.log"
grep -Fxq 'RELEASE_ID=v0.1.9' "$test_root/.release.env"
[[ "$(grep -c '^up$' "$TEST_EVENTS")" -eq 1 ]]
if grep -Fq 'Rollback verified' "$test_root/unsafe-restore-trap.log"; then
  echo 'Generic rollback claimed an unsafe restoration was verified' >&2; exit 1
fi

echo 'Release prepare/deploy contract OK'
