#!/usr/bin/env bash
# Backup customer_master before running dedupe or manual deletes.
# Usage:
#   export MONGO_URI='mongodb://localhost:27017/openProject'
#   ./scripts/backup-customer-master.sh
#
# Or with mongodump:
#   mongodump --uri="$MONGO_URI" --collection=customer_master --out=./mongo-backup-$(date +%Y%m%d-%H%M%S)

set -euo pipefail

OUT_DIR="${OUT_DIR:-./mongo-backup-$(date +%Y%m%d-%H%M%S)}"
URI="${MONGO_URI:-mongodb://localhost:27017/openProject}"

if ! command -v mongodump >/dev/null 2>&1; then
  echo "mongodump not found. Install MongoDB Database Tools or use Compass: Collection -> Export Collection."
  exit 1
fi

echo "Backing up customer_master to ${OUT_DIR}"
mongodump --uri="${URI}" --collection=customer_master --out="${OUT_DIR}"
echo "Done: ${OUT_DIR}"
