#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ] || { [ "$1" != dev ] && [ "$1" != prod ]; }; then
  echo "Usage: PGHOST=... PGPORT=... PGDATABASE=... PGUSER=... PGPASSFILE=... $0 dev|prod" >&2
  exit 2
fi

: "${PGHOST:?Set PGHOST for the selected environment}"
: "${PGPORT:?Set PGPORT for the selected environment}"
: "${PGDATABASE:?Set PGDATABASE for the selected environment}"
: "${PGUSER:?Set PGUSER for the selected environment}"

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
echo "Checking $1 primary DB: ${PGHOST}:${PGPORT}/${PGDATABASE}"
psql -X -v ON_ERROR_STOP=1 -v "expected_db=$PGDATABASE" \
  -f "$script_dir/check-notification-schema.sql"
