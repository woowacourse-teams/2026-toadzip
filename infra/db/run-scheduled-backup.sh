#!/usr/bin/env bash
set -euo pipefail

config_file=/etc/toadzip/db-backup.env
[[ -f "$config_file" ]] || { echo "Backup configuration missing: $config_file" >&2; exit 2; }
set -a
source "$config_file"
set +a
: "${TOADZIP_REPO_DIR:?Set TOADZIP_REPO_DIR}"
[[ -f "$TOADZIP_REPO_DIR/infra/db/backup-all.sh" ]] || {
    echo 'Backup script missing from configured repository' >&2; exit 2;
}
exec bash "$TOADZIP_REPO_DIR/infra/db/backup-all.sh"
