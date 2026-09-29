#!/usr/bin/env bash
set -euo pipefail

: "${TOADZIP_BACKUP_BUCKET:?Set TOADZIP_BACKUP_BUCKET}"
: "${TOADZIP_BACKUP_ACCOUNT_ID:?Set TOADZIP_BACKUP_ACCOUNT_ID}"
: "${AWS_REGION:?Set AWS_REGION}"

bucket_args=(--region "$AWS_REGION" --bucket "$TOADZIP_BACKUP_BUCKET"
    --expected-bucket-owner "$TOADZIP_BACKUP_ACCOUNT_ID")
aws s3api head-bucket "${bucket_args[@]}" > /dev/null
versioning="$(aws s3api get-bucket-versioning "${bucket_args[@]}" --query Status --output text)"
[[ "$versioning" == None ]] || { echo "Backup bucket versioning must be disabled: $versioning" >&2; exit 1; }

lifecycle="$(aws s3api get-bucket-lifecycle-configuration "${bucket_args[@]}" \
    --query "Rules[?ID=='toadzip-db-backup-30d'].{Status:Status,Days:Expiration.Days,Prefix:Filter.Prefix}" \
    --output json)"
[[ "$lifecycle" == *'"Days": 30'* && "$lifecycle" == *'"Prefix": "backups/"'* \
    && "$lifecycle" == *'"Status": "Enabled"'* ]] || {
    echo 'Enabled 30-day backups/ lifecycle rule missing' >&2; exit 1;
}

public_block="$(aws s3api get-public-access-block "${bucket_args[@]}" \
    --query 'PublicAccessBlockConfiguration.[BlockPublicAcls,IgnorePublicAcls,BlockPublicPolicy,RestrictPublicBuckets]' \
    --output text)"
[[ "$public_block" == $'True\tTrue\tTrue\tTrue' ]] || {
    echo 'S3 public access block is incomplete' >&2; exit 1;
}
encryption="$(aws s3api get-bucket-encryption "${bucket_args[@]}" \
    --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm' \
    --output text)"
[[ "$encryption" == AES256 ]] || { echo 'S3 default SSE-S3 encryption missing' >&2; exit 1; }
echo 'Backup bucket policy verified: private, SSE-S3, unversioned, 30-day expiration'
