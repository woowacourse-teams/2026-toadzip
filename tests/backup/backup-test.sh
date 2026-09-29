#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temp_dir="$(mktemp -d)"
trap 'rm -rf -- "$temp_dir"' EXIT
mkdir -p "$temp_dir/bin"
: > "$temp_dir/db.env"

cat > "$temp_dir/bin/docker" <<'EOF'
#!/usr/bin/env bash
case " $* " in
    *' pg_dump '*) printf 'PGDMPmock-archive' ;;
    *' pg_restore '*) cat > /dev/null ;;
    *) exit 1 ;;
esac
EOF
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
case "$1 $2" in
    's3api head-bucket') exit 0 ;;
    's3 cp') stat -c %s "$3" > "$MOCK_REMOTE_SIZE_FILE" ;;
    's3api head-object')
        if [[ "${MOCK_BAD_SIZE:-}" == 1 ]]; then echo 1; else cat "$MOCK_REMOTE_SIZE_FILE"; fi ;;
    's3api get-bucket-versioning') echo None ;;
    's3api get-bucket-lifecycle-configuration')
        if [[ "${MOCK_WRONG_RETENTION:-}" == 1 ]]; then
            echo '[{"Days": 31, "Prefix": "backups/", "Status": "Enabled"}]'
        else
            echo '[{"Days": 30, "Prefix": "backups/", "Status": "Enabled"}]'
        fi ;;
    's3api get-public-access-block') printf 'True\tTrue\tTrue\tTrue\n' ;;
    's3api get-bucket-encryption') echo AES256 ;;
    's3api list-objects-v2')
        for arg in "$@"; do
            if [[ "$arg" == backups/db-*/ ]]; then
                printf '%smock.dump\t%s\t17\n' "$arg" "$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
                exit 0
            fi
        done
        exit 1 ;;
    *) echo "Unexpected AWS operation: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "$temp_dir/bin/docker" "$temp_dir/bin/aws"

export PATH="$temp_dir/bin:$PATH"
export TOADZIP_DB_ENV_FILE="$temp_dir/db.env"
export TOADZIP_BACKUP_BUCKET=toadzip-test-backup
export TOADZIP_BACKUP_ACCOUNT_ID=123456789012
export AWS_REGION=ap-northeast-2
export MOCK_REMOTE_SIZE_FILE="$temp_dir/remote-size"

bash "$repo_dir/infra/db/backup-all.sh" > "$temp_dir/output"
grep -q 'Backup verified: db-prod' "$temp_dir/output"
grep -q 'Backup verified: db-dev' "$temp_dir/output"
grep -q 'Backup verified: db-shared' "$temp_dir/output"
grep -q 'Backup fresh: db-prod' "$temp_dir/output"
grep -q 'Backup fresh: db-dev' "$temp_dir/output"
grep -q 'Backup fresh: db-shared' "$temp_dir/output"

if MOCK_BAD_SIZE=1 bash "$repo_dir/infra/db/backup.sh" db-prod > /dev/null 2>&1; then
    echo 'Mismatched S3 object size must fail' >&2
    exit 1
fi
if MOCK_WRONG_RETENTION=1 bash "$repo_dir/infra/db/check-backups.sh" > /dev/null 2>&1; then
    echo 'A retention rule other than 30 days must fail' >&2
    exit 1
fi
if bash "$repo_dir/infra/db/backup.sh" wrong-service > /dev/null 2>&1; then
    echo 'Unknown DB service must fail' >&2
    exit 1
fi
echo 'Backup script tests passed'
