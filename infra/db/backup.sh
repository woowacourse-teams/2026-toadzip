#!/usr/bin/env bash
set -euo pipefail

service="${1:-}"
case "$service" in
    db-prod|db-dev) database=toadzip ;;
    db-shared) database=toadzip_shared ;;
    *) echo 'Usage: backup.sh db-prod|db-dev|db-shared' >&2; exit 2 ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
env_file="${TOADZIP_DB_ENV_FILE:-$script_dir/.env}"
: "${TOADZIP_BACKUP_BUCKET:?Set TOADZIP_BACKUP_BUCKET}"
: "${TOADZIP_BACKUP_ACCOUNT_ID:?Set TOADZIP_BACKUP_ACCOUNT_ID}"
: "${AWS_REGION:?Set AWS_REGION}"
[[ "$TOADZIP_BACKUP_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || { echo 'Invalid AWS account ID' >&2; exit 2; }
[[ -f "$env_file" ]] || { echo "DB environment file missing: $env_file" >&2; exit 2; }

umask 077
temp_dir="$(mktemp -d)"
trap 'rm -rf -- "$temp_dir"' EXIT
archive="$temp_dir/$service.dump"
key="backups/$service/$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"

docker compose --env-file "$env_file" -f "$script_dir/compose.yaml" exec -T "$service" \
    pg_dump -U toadzip -d "$database" -Fc > "$archive"
[[ -s "$archive" ]] || { echo "Empty backup: $service" >&2; exit 1; }
docker compose --env-file "$env_file" -f "$script_dir/compose.yaml" exec -T "$service" \
    pg_restore --list < "$archive" > /dev/null

local_size="$(stat -c %s "$archive")"
aws s3api head-bucket --region "$AWS_REGION" --bucket "$TOADZIP_BACKUP_BUCKET" \
    --expected-bucket-owner "$TOADZIP_BACKUP_ACCOUNT_ID" > /dev/null
aws s3 cp "$archive" "s3://$TOADZIP_BACKUP_BUCKET/$key" \
    --region "$AWS_REGION" --sse AES256 --only-show-errors
remote_size="$(aws s3api head-object --region "$AWS_REGION" --bucket "$TOADZIP_BACKUP_BUCKET" \
    --key "$key" --expected-bucket-owner "$TOADZIP_BACKUP_ACCOUNT_ID" \
    --query ContentLength --output text)"
[[ "$remote_size" == "$local_size" ]] || { echo "Backup size mismatch: $service/$key" >&2; exit 1; }
echo "Backup verified: $service s3://$TOADZIP_BACKUP_BUCKET/$key ($local_size bytes)"
