#!/usr/bin/env bash
set -euo pipefail

config_file="${1:-/etc/toadzip/db-backup.env}"
[[ -f "$config_file" ]] || { echo "Backup configuration missing: $config_file" >&2; exit 2; }
set -a
source "$config_file"
set +a
: "${TOADZIP_REPO_DIR:?Set TOADZIP_REPO_DIR}"
[[ -f "$TOADZIP_REPO_DIR/infra/db/backup-all.sh" ]] || {
    echo 'Backup script missing from configured repository' >&2; exit 2;
}
if bash "$TOADZIP_REPO_DIR/infra/db/backup-all.sh"; then
    exit 0
else
    backup_status=$?
fi
if [[ -n "${TOADZIP_BACKUP_ALERT_TOPIC_ARN:-}" ]]; then
    aws sns publish --region "$AWS_REGION" --topic-arn "$TOADZIP_BACKUP_ALERT_TOPIC_ARN" \
        --subject 'Toadzip DB backup failed' \
        --message "Daily DB backup failed (exit $backup_status). Check journalctl -u toadzip-db-backup.service on the DB server. No database contents are included in this message." \
        > /dev/null || echo 'Backup failure alert could not be published; independent AWS monitoring remains required.' >&2
fi
exit "$backup_status"
