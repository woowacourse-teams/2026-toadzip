#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: PGHOST=... PGPORT=... PGDATABASE=... PGUSER=... PGPASSFILE=... $0 local|dev|prod before|after" >&2
  exit 2
fi
case "$1" in local|dev|prod) ;; *) echo "Unknown environment: $1" >&2; exit 2 ;; esac
case "$2" in before|after) ;; *) echo "Unknown phase: $2" >&2; exit 2 ;; esac

: "${PGHOST:?Set PGHOST for the selected primary DB}"
: "${PGPORT:?Set PGPORT for the selected primary DB}"
: "${PGDATABASE:?Set PGDATABASE for the selected primary DB}"
: "${PGUSER:?Set PGUSER for the selected primary DB}"

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
echo "Checking privacy schema ($1, $2): ${PGHOST}:${PGPORT}/${PGDATABASE}"
PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-5}" psql -X -v ON_ERROR_STOP=1 \
  -v "expected_db=$PGDATABASE" -v "phase=$2" -f "$script_dir/check-privacy-schema.sql"
