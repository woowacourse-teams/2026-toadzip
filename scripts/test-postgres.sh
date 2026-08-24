#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose --project-name toadzip-test --file "$repository_root/compose.test.yaml")
test_port="${TEST_POSTGRES_PORT:-55432}"
gradle_arguments=("$@")

if [ "${#gradle_arguments[@]}" -eq 0 ]; then
    gradle_arguments=(check)
fi

cleanup() {
    local exit_status=$?

    trap - EXIT

    if [ "$exit_status" -ne 0 ]; then
        TEST_POSTGRES_PORT="$test_port" "${compose[@]}" logs --no-color db || true
    fi

    TEST_POSTGRES_PORT="$test_port" "${compose[@]}" down --volumes --remove-orphans || true
    exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

TEST_POSTGRES_PORT="$test_port" "${compose[@]}" up --detach --wait --wait-timeout 60 db

(
    cd "$repository_root/backend"
    while IFS= read -r datasource_variable; do
        unset "$datasource_variable"
    done < <(compgen -e | grep '^SPRING_DATASOURCE_')
    unset SPRING_PROFILES_ACTIVE
    unset SPRING_JPA_HIBERNATE_DDL_AUTO
    TEST_POSTGRES_PORT="$test_port" ./gradlew --rerun-tasks "${gradle_arguments[@]}"
)
