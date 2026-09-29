#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for service in db-prod db-dev db-shared; do
    bash "$script_dir/backup.sh" "$service"
done
bash "$script_dir/check-backups.sh"
