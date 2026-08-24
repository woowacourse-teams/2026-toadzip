#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose --project-name toadzip-test --file "$repository_root/compose.test.yaml")
test_port="${TEST_POSTGRES_PORT:-55432}"
gradle_arguments=("$@")
gradle_pid=""

if [ "${#gradle_arguments[@]}" -eq 0 ]; then
    gradle_arguments=(check)
fi

cleanup() {
    local exit_status=$?
    local cleanup_status

    trap - EXIT

    if [ "$exit_status" -ne 0 ]; then
        TEST_POSTGRES_PORT="$test_port" "${compose[@]}" logs --no-color db || true
    fi

    if TEST_POSTGRES_PORT="$test_port" "${compose[@]}" down --volumes --remove-orphans; then
        cleanup_status=0
    else
        cleanup_status=$?
    fi

    if [ "$exit_status" -ne 0 ]; then
        exit "$exit_status"
    fi

    exit "$cleanup_status"
}

forward_signal() {
    local signal_name=$1
    local signal_status=$2

    if [ -n "$gradle_pid" ]; then
        kill "-$signal_name" "$gradle_pid" 2>/dev/null || true
        wait "$gradle_pid" 2>/dev/null || true
    fi

    exit "$signal_status"
}

trap cleanup EXIT
trap 'forward_signal INT 130' INT
trap 'forward_signal TERM 143' TERM
trap 'forward_signal HUP 129' HUP

TEST_POSTGRES_PORT="$test_port" "${compose[@]}" up --detach --wait --wait-timeout 60 --force-recreate db

(
    cd "$repository_root/backend"
    trap - INT TERM HUP
    while IFS= read -r datasource_variable; do
        unset "$datasource_variable"
    done < <(compgen -e | grep '^SPRING_DATASOURCE_')
    unset SPRING_PROFILES_ACTIVE
    unset SPRING_JPA_HIBERNATE_DDL_AUTO
    TEST_POSTGRES_PORT="$test_port" exec ./gradlew --rerun-tasks "${gradle_arguments[@]}"
) &
gradle_pid=$!

wait "$gradle_pid"
