#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
validator="$project_root/scripts/contains-hangul.py"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

printf '%s\n' '카카오 로그인 콜백 추가' \
  | LC_ALL=C python3 "$validator" \
  || fail "Hangul text must pass in the C locale"

if printf '%s\n' 'add kakao login' | LC_ALL=C python3 "$validator"; then
  fail "English-only text must fail"
fi

echo "PASS: Hangul validator behavior"
