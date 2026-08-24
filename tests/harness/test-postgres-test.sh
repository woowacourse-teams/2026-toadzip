#!/usr/bin/env bash

set -euo pipefail

project_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
runner="$project_root/scripts/test-postgres.sh"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/test-postgres-test.XXXXXX")"
active_script_pid=""
active_gradle_pid=""

stop_process() {
    local process_id=$1
    local attempt

    [ -n "$process_id" ] || return 0

    kill -TERM "$process_id" 2>/dev/null || true

    for attempt in $(seq 1 50); do
        if ! kill -0 "$process_id" 2>/dev/null; then
            wait "$process_id" 2>/dev/null || true
            return
        fi

        sleep 0.05
    done

    kill -KILL "$process_id" 2>/dev/null || true
    wait "$process_id" 2>/dev/null || true
}

cleanup() {
    trap - EXIT HUP INT TERM

    stop_process "$active_gradle_pid"
    stop_process "$active_script_pid"
    rm -rf "$fixture"
}

trap cleanup EXIT HUP INT TERM

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

make_fixture() {
    local fixture_root=$1

    mkdir -p "$fixture_root/backend" "$fixture_root/bin" "$fixture_root/scripts"
    cp "$runner" "$fixture_root/scripts/test-postgres.sh"
    printf '%s\n' 'services: {}' > "$fixture_root/compose.test.yaml"

    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'set -euo pipefail' \
        'printf "%s\\n" "$*" >> "$FAKE_DOCKER_LOG"' \
        'for argument in "$@"; do' \
        '    if [ "$argument" = "down" ]; then' \
        '        exit "${FAKE_DOCKER_DOWN_EXIT:-0}"' \
        '    fi' \
        'done' \
        > "$fixture_root/bin/docker"
    chmod +x "$fixture_root/bin/docker"

    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'set -euo pipefail' \
        'if [ "${FAKE_GRADLE_MODE:-exit}" = "wait_for_signal" ]; then' \
        '    printf "%s\n" "$$" > "$FAKE_GRADLE_PID_FILE"' \
        '    on_term() {' \
        '        echo TERM >> "$FAKE_GRADLE_LOG"' \
        '        exit 143' \
        '    }' \
        '    trap on_term TERM' \
        '    while true; do' \
        '        sleep 0.05' \
        '    done' \
        'fi' \
        'exit "${FAKE_GRADLE_EXIT:-0}"' \
        > "$fixture_root/backend/gradlew"
    chmod +x "$fixture_root/backend/gradlew"
}

wait_for_file() {
    local file=$1
    local attempt

    for attempt in $(seq 1 50); do
        if [ -s "$file" ]; then
            return 0
        fi

        sleep 0.05
    done

    return 1
}

assert_cleanup_failure_is_reported() {
    local fixture_root="$fixture/cleanup-failure"
    local docker_log="$fixture_root/docker.log"
    local exit_status

    make_fixture "$fixture_root"

    set +e
    PATH="$fixture_root/bin:$PATH" \
        FAKE_DOCKER_LOG="$docker_log" \
        FAKE_DOCKER_DOWN_EXIT=9 \
        "$fixture_root/scripts/test-postgres.sh" test > "$fixture_root/output.log" 2>&1
    exit_status=$?
    set -e

    [ "$exit_status" -eq 9 ] || fail "cleanup failure after a passing Gradle run must exit 9, got $exit_status"
    grep -Fq 'down --volumes --remove-orphans' "$docker_log" \
        || fail "cleanup must invoke docker compose down"
}

assert_gradle_failure_is_preserved_when_cleanup_fails() {
    local fixture_root="$fixture/gradle-and-cleanup-failure"
    local docker_log="$fixture_root/docker.log"
    local exit_status

    make_fixture "$fixture_root"

    set +e
    PATH="$fixture_root/bin:$PATH" \
        FAKE_DOCKER_LOG="$docker_log" \
        FAKE_DOCKER_DOWN_EXIT=9 \
        FAKE_GRADLE_EXIT=7 \
        "$fixture_root/scripts/test-postgres.sh" test > "$fixture_root/output.log" 2>&1
    exit_status=$?
    set -e

    [ "$exit_status" -eq 7 ] || fail "Gradle failure must take precedence over cleanup failure, got $exit_status"
}

assert_startup_force_recreates_database() {
    local fixture_root="$fixture/force-recreate"
    local docker_log="$fixture_root/docker.log"

    make_fixture "$fixture_root"

    PATH="$fixture_root/bin:$PATH" \
        FAKE_DOCKER_LOG="$docker_log" \
        "$fixture_root/scripts/test-postgres.sh" test > "$fixture_root/output.log" 2>&1

    grep -Fq 'up --detach --wait --wait-timeout 60 --force-recreate db' "$docker_log" \
        || fail "startup must force recreation of the isolated test database"
}

assert_term_forwards_to_gradle_and_cleans_up() {
    local fixture_root="$fixture/term-forwarding"
    local docker_log="$fixture_root/docker.log"
    local gradle_log="$fixture_root/gradle.log"
    local gradle_pid_file="$fixture_root/gradle.pid"
    local exit_status

    make_fixture "$fixture_root"

    PATH="$fixture_root/bin:$PATH" \
        FAKE_DOCKER_LOG="$docker_log" \
        FAKE_GRADLE_MODE=wait_for_signal \
        FAKE_GRADLE_LOG="$gradle_log" \
        FAKE_GRADLE_PID_FILE="$gradle_pid_file" \
        "$fixture_root/scripts/test-postgres.sh" test > "$fixture_root/output.log" 2>&1 &
    active_script_pid=$!

    wait_for_file "$gradle_pid_file" || fail "Gradle process did not start"
    active_gradle_pid="$(< "$gradle_pid_file")"

    kill -TERM "$active_script_pid"

    wait_for_file "$gradle_log" || fail "TERM sent to the wrapper was not forwarded to Gradle"

    set +e
    wait "$active_script_pid"
    exit_status=$?
    set -e

    [ "$exit_status" -eq 143 ] || fail "TERM must preserve exit status 143, got $exit_status"
    if kill -0 "$active_gradle_pid" 2>/dev/null; then
        fail "Gradle must terminate before the wrapper exits"
    fi
    grep -Fq 'down --volumes --remove-orphans' "$docker_log" \
        || fail "TERM handling must run cleanup"

    active_script_pid=""
    active_gradle_pid=""
}

assert_cleanup_failure_is_reported
assert_gradle_failure_is_preserved_when_cleanup_fails
assert_startup_force_recreates_database
assert_term_forwards_to_gradle_and_cleans_up

echo "PASS: PostgreSQL test lifecycle behavior"
