#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 && "$1" =~ ^[a-z_][a-z0-9_-]*$ ]] || {
    echo 'Usage: sudo bash install-backup-automation.sh DB_SERVER_OS_USER' >&2; exit 2;
}
[[ "$EUID" -eq 0 ]] || { echo 'Run this installer with sudo on the DB server' >&2; exit 2; }
db_user="$1"
id "$db_user" > /dev/null
config_file=/etc/toadzip/db-backup.env
[[ -f "$config_file" && "$(stat -c %u "$config_file")" == 0 ]] || {
    echo 'A root-owned /etc/toadzip/db-backup.env is required' >&2; exit 2;
}
config_mode="$(stat -c %a "$config_file")"
(( (8#$config_mode & 0022) == 0 )) || { echo 'Backup config must not be group/world writable' >&2; exit 2; }
set -a
source "$config_file"
set +a
: "${TOADZIP_BACKUP_BUCKET:?Set TOADZIP_BACKUP_BUCKET}"
: "${TOADZIP_BACKUP_ACCOUNT_ID:?Set TOADZIP_BACKUP_ACCOUNT_ID}"
: "${TOADZIP_REPO_DIR:?Set TOADZIP_REPO_DIR}"
: "${TOADZIP_BACKUP_ALERT_TOPIC_ARN:?Set TOADZIP_BACKUP_ALERT_TOPIC_ARN}"
: "${AWS_REGION:?Set AWS_REGION}"
[[ "$TOADZIP_BACKUP_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || { echo 'Invalid AWS account ID' >&2; exit 2; }
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
[[ "$(cd "$TOADZIP_REPO_DIR" && pwd)" == "$repo_dir" ]] || {
    echo 'Configured repository must match this installer location' >&2; exit 2;
}
chgrp "$(id -gn "$db_user")" "$config_file"
chmod 640 "$config_file"
install -D -m 755 "$script_dir/run-scheduled-backup.sh" /usr/local/libexec/toadzip-db-backup-runner
sed "s/REPLACE_WITH_DB_SERVER_USER/$db_user/" "$script_dir/systemd/toadzip-db-backup.service.example" \
    > /etc/systemd/system/toadzip-db-backup.service
install -m 644 "$script_dir/systemd/toadzip-db-backup.timer" /etc/systemd/system/toadzip-db-backup.timer
systemctl daemon-reload
systemctl enable --now toadzip-db-backup.timer
systemctl start toadzip-db-backup.service
echo 'DB backup automation installed and first backup completed. AWS monitoring verifies subsequent backups independently.'
