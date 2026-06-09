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

export BACKEND_HOST METRICS_SCRAPE_TOKEN
envsubst < /etc/prometheus/prometheus.yml.template > /etc/prometheus/prometheus.yml

exec /bin/prometheus \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --web.enable-lifecycle
