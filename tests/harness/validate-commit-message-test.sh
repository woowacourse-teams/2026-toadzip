#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
validator="$project_root/scripts/validate-commit-message.sh"
message=$(mktemp "${TMPDIR:-/tmp}/commit-message.XXXXXX")
trap 'rm -f "$message"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

printf '%s\n' 'feat(auth): 카카오 로그인 콜백 추가 (#12)' > "$message"
"$validator" "$message" >/dev/null || fail "valid scoped commit must pass"

printf '%s\n' 'docs: 팀 브랜치 컨벤션 추가 (#21)' > "$message"
"$validator" "$message" >/dev/null || fail "valid unscoped commit must pass"

printf '%s\n' 'feature: 기능 추가 (#1)' > "$message"
if "$validator" "$message" >/dev/null 2>&1; then
  fail "unknown type must fail"
fi

printf '%s\n' 'feat: 기능을 추가한다 (#1)' > "$message"
if "$validator" "$message" >/dev/null 2>&1; then
  fail "sentence-ending summary must fail"
fi

printf '%s\n' 'feat: add housing map (#1)' > "$message"
if "$validator" "$message" >/dev/null 2>&1; then
  fail "English summary must fail"
fi

echo "PASS: commit message validator behavior"
