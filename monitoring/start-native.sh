#!/usr/bin/env bash
# Start Prometheus + Grafana on the host (backend must be on localhost:8080).
# Requires: brew install prometheus grafana
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROM_CONFIG="$ROOT/monitoring/prometheus/prometheus-native.yml"
RUNTIME_PROVISIONING="$ROOT/monitoring/.data/grafana-provisioning"
DASHBOARDS_DIR="$ROOT/monitoring/grafana/dashboards"

if ! command -v prometheus >/dev/null 2>&1; then
  echo "Prometheus not found. Run: brew install prometheus grafana"
  exit 1
fi

if ! curl -sf "http://localhost:8080/actuator/prometheus" >/dev/null 2>&1; then
  echo "Backend not reachable at http://localhost:8080 — start Spring Boot first."
  exit 1
fi

mkdir -p "$ROOT/monitoring/.data/prometheus"
mkdir -p "$RUNTIME_PROVISIONING/datasources"
mkdir -p "$RUNTIME_PROVISIONING/dashboards"

cp "$ROOT/monitoring/grafana/provisioning-native/datasources/datasources.yml" \
  "$RUNTIME_PROVISIONING/datasources/datasources.yml"

cat >"$RUNTIME_PROVISIONING/dashboards/dashboards.yml" <<EOF
apiVersion: 1

providers:
  - name: openproject
    orgId: 1
    folder: ""
    type: file
    disableDeletion: false
    editable: true
    options:
      path: $DASHBOARDS_DIR
EOF

if lsof -i :9090 >/dev/null 2>&1; then
  echo "Prometheus already running on :9090"
else
  echo "Starting Prometheus on http://localhost:9090"
  nohup prometheus \
    --config.file="$PROM_CONFIG" \
    --storage.tsdb.path="$ROOT/monitoring/.data/prometheus" \
    --web.enable-lifecycle \
    >"$ROOT/monitoring/.data/prometheus.log" 2>&1 &
fi

if lsof -i :3000 >/dev/null 2>&1; then
  echo "Grafana already running on http://localhost:3000"
else
  echo "Starting Grafana on http://localhost:3000 (admin / admin)"
  GF_PATHS_PROVISIONING="$RUNTIME_PROVISIONING" \
  GF_SECURITY_ADMIN_USER=admin \
  GF_SECURITY_ADMIN_PASSWORD=admin \
  GF_USERS_ALLOW_SIGN_UP=false \
  nohup grafana server \
    --config /opt/homebrew/etc/grafana/grafana.ini \
    --homepath /opt/homebrew/opt/grafana/share/grafana \
    --packaging brew \
    cfg:default.paths.logs="$ROOT/monitoring/.data/grafana-logs" \
    cfg:default.paths.data="$ROOT/monitoring/.data/grafana-data" \
    cfg:default.paths.plugins="$ROOT/monitoring/.data/grafana-plugins" \
    >"$ROOT/monitoring/.data/grafana.log" 2>&1 &
fi

sleep 4
echo ""
echo "Prometheus: http://localhost:9090  (Status → Targets)"
echo "Grafana:    http://localhost:3000  (admin / admin)"
echo "Dashboard:  Dashboards → openProject — Spring Boot (Micrometer)"
