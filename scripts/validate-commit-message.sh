#!/bin/sh
set -eu

[ "$#" -eq 1 ] || {
  echo "usage: validate-commit-message.sh COMMIT_MESSAGE_FILE" >&2
  exit 2
}

title=$(sed -n '1p' "$1")
types='feat|fix|docs|style|refactor|test|build|ci|chore'
pattern="^($types)(\([a-z][a-z0-9-]*\))?: .+ \(#[1-9][0-9]*\)$"

printf '%s\n' "$title" | grep -Eq "$pattern" || {
  echo "commit check failed: invalid title '$title'" >&2
  exit 1
}

printf '%s\n' "$title" | grep -Eq '[가-힣]' || {
  echo "commit check failed: summary must be written in Korean" >&2
  exit 1
}

printf '%s\n' "$title" | grep -Eq '다[.]? \(#[1-9][0-9]*\)$' && {
  echo "commit check failed: 요약은 '추가', '수정'처럼 간결하게 작성하세요." >&2
  exit 1
}

echo "commit check passed"
