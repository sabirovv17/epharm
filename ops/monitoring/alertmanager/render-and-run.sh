#!/bin/sh
set -eu

config=/tmp/alertmanager.yml
webhook=${ALERT_WEBHOOK_URL:-}

case "$webhook" in
  '')
    receiver='  - name: ops-webhook'
    echo 'WARNING: ALERT_WEBHOOK_URL is empty; alerts remain visible in Alertmanager but no external notification is sent.' >&2
    ;;
  http://*|https://*)
    case "$webhook" in
      *\"*|*\'*) echo 'ERROR: ALERT_WEBHOOK_URL must not contain quotes' >&2; exit 1 ;;
    esac
    receiver="  - name: ops-webhook
    webhook_configs:
      - url: \"$webhook\"
        send_resolved: true"
    ;;
  *) echo 'ERROR: ALERT_WEBHOOK_URL must be an http(s) URL' >&2; exit 1 ;;
esac

cat > "$config" <<EOF
global:
  resolve_timeout: 5m

route:
  receiver: ops-webhook
  group_by: [alertname, severity]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [severity="critical"]
      repeat_interval: 30m

inhibit_rules:
  - source_matchers: [severity="critical"]
    target_matchers: [severity="warning"]
    equal: [alertname, instance]

receivers:
$receiver
EOF

if [ "${ALERTMANAGER_VALIDATE_ONLY:-false}" = true ]; then
  exec /bin/amtool check-config "$config"
fi

exec /bin/alertmanager --config.file="$config" --storage.path=/alertmanager "$@"
