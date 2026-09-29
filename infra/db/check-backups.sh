#!/usr/bin/env bash
set -euo pipefail

: "${TOADZIP_BACKUP_BUCKET:?Set TOADZIP_BACKUP_BUCKET}"
: "${TOADZIP_BACKUP_ACCOUNT_ID:?Set TOADZIP_BACKUP_ACCOUNT_ID}"
: "${AWS_REGION:?Set AWS_REGION}"
[[ "$TOADZIP_BACKUP_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || { echo 'Invalid AWS account ID' >&2; exit 2; }
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$script_dir/check-backup-policy.sh"

now="$(date -u +%s)"
for service in db-prod db-dev db-shared; do
    prefix="backups/$service/"
    latest="$(aws s3api list-objects-v2 --region "$AWS_REGION" \
        --bucket "$TOADZIP_BACKUP_BUCKET" --prefix "$prefix" \
        --expected-bucket-owner "$TOADZIP_BACKUP_ACCOUNT_ID" \
        --query 'sort_by(Contents, &LastModified)[-1].[Key,LastModified,Size]' --output text)"
    read -r key created size <<< "$latest"
    [[ "$key" == "$prefix"* && "$size" =~ ^[0-9]+$ && "$size" -gt 0 ]] || {
        echo "Backup missing or empty: $service" >&2; exit 1;
    }
    age=$((now - $(date -u -d "$created" +%s)))
    [[ "$age" -ge 0 && "$age" -le 93600 ]] || {
        echo "Backup outside 26-hour window: $service ($key, created=$created, age=${age}s)" >&2; exit 1;
    }
    echo "Backup fresh: $service ($key, ${age}s old)"
done
