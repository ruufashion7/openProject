#!/bin/sh
set -eu

if [ -z "${BACKEND_HOST:-}" ]; then
  echo "BACKEND_HOST is required (e.g. openproject-backend.onrender.com)"
  exit 1
fi

if [ -z "${METRICS_SCRAPE_TOKEN:-}" ]; then
  echo "METRICS_SCRAPE_TOKEN is required for production scrape"
  exit 1
fi

CONFIG=/prometheus/prometheus.yml
sed \
  -e "s|\${BACKEND_HOST}|${BACKEND_HOST}|g" \
  -e "s|\${METRICS_SCRAPE_TOKEN}|${METRICS_SCRAPE_TOKEN}|g" \
  /etc/prometheus/prometheus.yml.template > "${CONFIG}"

exec /bin/prometheus \
  --config.file="${CONFIG}" \
  --storage.tsdb.path=/prometheus \
  --web.enable-lifecycle
