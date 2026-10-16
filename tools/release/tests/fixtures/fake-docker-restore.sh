#!/usr/bin/env bash
# Only the Docker calls needed by restore-readiness.sh are simulated.
set -euo pipefail

case "$1" in
  network|run)
    exit 0
    ;;
  rm)
    [[ ${2:-} == -fv && ${3:-} == epharm-restore-* ]] || exit 2
    printf 'cleanup-volumes\n' >> "$FAKE_DOCKER_STATE"
    exit 0
    ;;
  exec)
    shift # exec
    [[ "${1:-}" == -i ]] && shift
    shift # container name
    command="$1"
    shift
    case "$command" in
      pg_isready)
        [[ "$*" == *"-h 127.0.0.1"* ]] || exit 2
        printf 'tcp-probe\n' >> "$FAKE_DOCKER_STATE"
        [[ "$(grep -c '^tcp-probe$' "$FAKE_DOCKER_STATE")" -ge 3 ]]
        ;;
      psql)
        case "$*" in
          *"SELECT 1"*)
            printf 'db-probe\n' >> "$FAKE_DOCKER_STATE"
            # A socket query would succeed during the temporary server phase;
            # after TCP opens, the target DB needs one further retry.
            if [[ "$(grep -c '^tcp-probe$' "$FAKE_DOCKER_STATE" || true)" -gt 0 ]]; then
              [[ "$(grep -c '^db-probe$' "$FAKE_DOCKER_STATE")" -ge 2 ]] || exit 1
            fi
            printf '1\n'
            ;;
          *"information_schema.tables"*) printf '5\n' ;;
          *"flyway_schema_history"*) printf '7\n' ;;
          *) exit 2 ;;
        esac
        ;;
      pg_restore)
        [[ "$(grep -c '^tcp-probe$' "$FAKE_DOCKER_STATE" || true)" -ge 4 ]] || exit 3
        [[ "$(grep -c '^db-probe$' "$FAKE_DOCKER_STATE" || true)" -ge 2 ]] || exit 3
        dd of=/dev/null 2>/dev/null
        printf 'restore\n' >> "$FAKE_DOCKER_STATE"
        ;;
      *) exit 2 ;;
    esac
    ;;
  *) exit 2 ;;
esac
