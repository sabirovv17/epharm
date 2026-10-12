#!/usr/bin/env bash
# Only the Docker calls needed by restore-readiness.sh are simulated.
set -euo pipefail

case "$1" in
  network|run|rm)
    exit 0
    ;;
  exec)
    shift # exec
    [[ "${1:-}" == -i ]] && shift
    shift # container name
    command="$1"
    shift
    case "$command" in
      psql)
        case "$*" in
          *"SELECT 1"*)
            printf 'probe\n' >> "$FAKE_DOCKER_STATE"
            [[ "$(grep -c '^probe$' "$FAKE_DOCKER_STATE")" -ge 3 ]] || exit 1
            printf '1\n'
            ;;
          *"information_schema.tables"*) printf '5\n' ;;
          *"flyway_schema_history"*) printf '7\n' ;;
          *) exit 2 ;;
        esac
        ;;
      pg_restore)
        [[ "$(grep -c '^probe$' "$FAKE_DOCKER_STATE")" -ge 3 ]] || exit 3
        dd of=/dev/null 2>/dev/null
        printf 'restore\n' >> "$FAKE_DOCKER_STATE"
        ;;
      *) exit 2 ;;
    esac
    ;;
  *) exit 2 ;;
esac
