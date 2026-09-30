#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 3 ]] || { echo 'Usage: deploy-backup-stack.sh EXPECTED_AWS_ACCOUNT_ID AWS_REGION DB_EC2_ROLE_NAME' >&2; exit 2; }
expected_account="$1"
region="$2"
role_name="$3"
[[ "$expected_account" =~ ^[0-9]{12}$ ]] || { echo 'Expected AWS account must be 12 digits' >&2; exit 2; }
actual_account="$(aws sts get-caller-identity --query Account --output text --region "$region")"
[[ "$actual_account" == "$expected_account" ]] || { echo 'AWS account does not match; refusing deployment' >&2; exit 1; }
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
temp_dir="$(mktemp -d)"
trap 'rm -rf -- "$temp_dir"' EXIT
python3 "$script_dir/automation/render-template.py" > "$temp_dir/stack.json"
aws cloudformation deploy --region "$region" --stack-name toadzip-db-backup \
    --template-file "$temp_dir/stack.json" --capabilities CAPABILITY_IAM \
    --parameter-overrides "BackupInstanceRoleName=$role_name" 'AlertEmail=toadzip.official@gmail.com'
aws cloudformation describe-stacks --region "$region" --stack-name toadzip-db-backup \
    --query 'Stacks[0].Outputs' --output table
echo 'Confirm the SNS subscription email in toadzip.official@gmail.com, then install the DB-server timer.'
