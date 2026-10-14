#!/usr/bin/env bash
set -euo pipefail
[[ -s "$RESTIC_PASSWORD_FILE" && -z "${RESTIC_PASSWORD:-}" ]]
[[ "$1" == -o && "$2" == "sftp.command=$RESTIC_SFTP_COMMAND" ]]
shift 2
case "$1" in snapshots|backup|forget|check) exit 0 ;; *) exit 1 ;; esac
