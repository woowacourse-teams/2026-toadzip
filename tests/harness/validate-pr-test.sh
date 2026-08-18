#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
validator="$project_root/scripts/validate-pr.sh"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

"$validator" dev 'feat/123-housing-map' \
  'feat(housing): 지도 조회 추가 (#123)' >/dev/null \
  || fail "valid PR contract must pass"

"$validator" dev 'style/9-java-format' \
  'style: 자바 포맷 정리 (#9)' >/dev/null \
  || fail "style type must pass"

"$validator" main dev \
  'chore(release): 운영 배포 준비 (#124)' >/dev/null \
  || fail "dev to main release PR must pass"

if "$validator" dev 'feature/map' '기능을 추가한다' >/dev/null 2>&1; then
  fail "invalid branch and title must fail"
fi

if "$validator" main 'fix/18-duplicate-address' \
  'fix(residence): 중복 주소 검증 (#18)' >/dev/null 2>&1; then
  fail "work branch targeting main must fail"
fi

if "$validator" dev dev \
  'chore(release): 운영 배포 준비 (#124)' >/dev/null 2>&1; then
  fail "dev branch targeting dev must fail"
fi

if "$validator" dev 'feat/12-kakao-login' \
  'feat(auth): 카카오 로그인을 추가한다 (#12)' >/dev/null 2>&1; then
  fail "sentence-ending summary must fail"
fi

if "$validator" dev 'feat/12-kakao-login' \
  'feat(auth): add kakao login (#12)' >/dev/null 2>&1; then
  fail "English summary must fail"
fi

echo "PASS: PR validator behavior"
