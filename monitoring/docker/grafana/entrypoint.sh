#!/bin/sh
set -eu

if [ -z "${PROMETHEUS_HOST:-}" ]; then
  echo "PROMETHEUS_HOST is required (e.g. openproject-prometheus.onrender.com)"
  exit 1
fi

PROVISIONING_DIR="/etc/grafana/provisioning-runtime"
mkdir -p "${PROVISIONING_DIR}/datasources" "${PROVISIONING_DIR}/dashboards"

cat >"${PROVISIONING_DIR}/datasources/datasources.yml" <<EOF
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: https://${PROMETHEUS_HOST}
    isDefault: true
    uid: prometheus
    editable: false
    jsonData:
      timeInterval: 30s
EOF

cat >"${PROVISIONING_DIR}/dashboards/dashboards.yml" <<EOF
apiVersion: 1

providers:
  - name: openproject
    orgId: 1
    folder: ""
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /var/lib/grafana/dashboards
EOF

export GF_PATHS_PROVISIONING="${PROVISIONING_DIR}"

if [ -n "${GRAFANA_PUBLIC_HOST:-}" ]; then
  export GF_SERVER_ROOT_URL="https://${GRAFANA_PUBLIC_HOST}"
fi

exec /run.sh
