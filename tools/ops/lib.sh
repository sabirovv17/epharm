#!/usr/bin/env bash

# Shared helpers for production operations scripts. Callers enable strict mode.

ops_root_dir() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd
}

load_env_file() {
  local env_file="$1"
  if [[ ! -r "$env_file" ]]; then
    echo "ERROR: environment file is not readable: $env_file" >&2
    return 1
  fi

  # dotenv files are data, never shell programs. Accept the conservative subset
  # used by .env.prod and preserve spaces and '#' inside values without eval/source.
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    [[ "$raw" =~ ^[[:space:]]*$ ]] && continue
    [[ "$raw" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$raw" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      echo "ERROR: unsupported line in $env_file: $raw" >&2
      return 1
    fi
    local key="${raw%%=*}"
    local value="${raw#*=}"
    key="${key//[[:space:]]/}"
    # Explicit process/systemd environment wins over dotenv, matching Docker
    # Compose precedence and allowing safe one-off restore drills.
    [[ -n "${!key:-}" ]] && continue
    value="${value%$'\r'}"
    if [[ "$value" == *" #"* ]]; then
      value="${value%% \#*}"
      while [[ "$value" == *[[:space:]] ]]; do value="${value%?}"; done
    fi
    if [[ "$value" == \"*\" && "$value" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi
    export "$key=$value"
  done < "$env_file"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: required command is missing: $1" >&2
    return 1
  }
}

require_nonempty() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: required variable is empty: $name" >&2
    return 1
  fi
}

acquire_lock() {
  local lock_dir="$1"
  if ! mkdir "$lock_dir" 2>/dev/null; then
    echo "ERROR: another operation holds lock $lock_dir" >&2
    return 1
  fi
  OPS_LOCK_DIR="$lock_dir"
  trap 'release_lock' EXIT INT TERM
}

release_lock() {
  if [[ -n "${OPS_LOCK_DIR:-}" && -d "$OPS_LOCK_DIR" ]]; then
    rmdir "$OPS_LOCK_DIR" 2>/dev/null || true
  fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

write_metric() {
  local metric_dir="$1"
  local file_name="$2"
  shift 2
  mkdir -p "$metric_dir"
  local tmp="$metric_dir/.${file_name}.$$"
  printf '%s\n' "$@" > "$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$metric_dir/$file_name"
}

latest_matching_file() {
  local directory="$1"
  local pattern="$2"
  [[ -d "$directory" ]] || return 0
  find "$directory" -maxdepth 1 -type f -name "$pattern" -print 2>/dev/null \
    | LC_ALL=C sort \
    | tail -n 1
}

docker_cleanup_container() {
  local name="$1"
  docker rm -f "$name" >/dev/null 2>&1 || true
}
