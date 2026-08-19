#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)

[ "$#" -eq 3 ] || {
  echo "usage: validate-pr.sh BASE_BRANCH HEAD_BRANCH TITLE" >&2
  exit 2
}

base_branch=$1
head_branch=$2
title=$3
types='feat|fix|docs|style|refactor|test|build|ci|chore'
title_pattern="^($types)(\([a-z][a-z0-9-]*\))?: .+ \(#[1-9][0-9]*\)$"

printf '%s\n' "$title" | grep -Eq "$title_pattern" || {
  echo "PR check failed: invalid title '$title'" >&2
  exit 1
}

printf '%s\n' "$title" | python3 "$script_dir/contains-hangul.py" || {
  echo "PR check failed: summary must be written in Korean" >&2
  exit 1
}

printf '%s\n' "$title" | grep -Eq '다[.]? \(#[1-9][0-9]*\)$' && {
  echo "PR check failed: summary must be concise, not sentence-ending" >&2
  exit 1
}

if [ "$head_branch" = develop ]; then
  [ "$base_branch" = main ] || {
    echo "PR check failed: develop must target main" >&2
    exit 1
  }
else
  [ "$base_branch" = develop ] || {
    echo "PR check failed: work branch must target develop" >&2
    exit 1
  }
  printf '%s\n' "$head_branch" \
    | grep -Eq "^($types)/[1-9][0-9]*-[a-z0-9]+(-[a-z0-9]+)*$" || {
    echo "PR check failed: invalid branch '$head_branch'" >&2
    exit 1
  }
fi

echo "PR check passed"
