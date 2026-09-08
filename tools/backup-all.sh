#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
"$SCRIPT_DIR/pg-backup.sh"
"$SCRIPT_DIR/minio-backup.sh"
"$SCRIPT_DIR/offsite-backup.sh"
