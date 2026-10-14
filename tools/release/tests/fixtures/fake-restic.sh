#!/usr/bin/env bash
set -euo pipefail
[[ -s "$RESTIC_PASSWORD_FILE" && -z "${RESTIC_PASSWORD:-}" ]]
case "$1" in snapshots|backup|forget|check) exit 0 ;; *) exit 1 ;; esac
