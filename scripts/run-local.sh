#!/usr/bin/env bash
# Run the Spring Boot API locally: starts Mongo in Docker if needed, then ./mvnw spring-boot:run
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -x ./mvnw ]]; then
  chmod +x ./mvnw
fi

if command -v docker >/dev/null 2>&1; then
  if ! docker info >/dev/null 2>&1; then
    echo "Docker is installed but not running; ensure MongoDB is reachable at MONGO_URI (default mongodb://localhost:27017/openProject)."
  else
    echo "Ensuring MongoDB container is up..."
    docker compose up -d mongo 2>/dev/null || docker-compose up -d mongo 2>/dev/null || true
  fi
else
  echo "Docker not found; ensure MongoDB is running locally (e.g. mongod on 27017)."
fi

export MONGO_URI="${MONGO_URI:-mongodb://localhost:27017/openProject}"
export SECURITY_JWT_SECRET="${SECURITY_JWT_SECRET:-openProject-dev-jwt-secret-change-in-prod-32b!}"

echo "MONGO_URI=$MONGO_URI"
echo "Starting backend (Ctrl+C to stop)..."
exec ./mvnw spring-boot:run
