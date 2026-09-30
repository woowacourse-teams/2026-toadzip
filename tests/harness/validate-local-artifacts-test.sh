#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
validator="$project_root/scripts/validate-local-artifacts.sh"
fixture=$(mktemp -d "${TMPDIR:-/tmp}/local-artifacts-test.XXXXXX")
trap 'rm -rf "$fixture"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

git -c init.templateDir= init --quiet "$fixture"
mkdir -p "$fixture/docs/superpowers/plans" "$fixture/docs/superpowers/specs"
printf '# Shared guide\n' > "$fixture/docs/design-system.md"
git -C "$fixture" add docs/design-system.md
sh "$validator" "$fixture" >/dev/null || fail "ordinary documentation must pass"

for directory in plans specs; do
  path="docs/superpowers/$directory/local draft.md"
  printf '# Local draft\n' > "$fixture/$path"
  sh "$validator" "$fixture" >/dev/null || fail "untracked drafts must pass"
  git -C "$fixture" add -- "$path"
  if sh "$validator" "$fixture" >"$fixture/output" 2>&1; then
    fail "tracked $directory draft must be rejected"
  fi
  grep -Fq "$directory" "$fixture/output" || fail "rejection must identify the path"
  git -C "$fixture" rm --cached --quiet -- "$path"
  sh "$validator" "$fixture" >/dev/null || fail "removing tracking must pass"
  [ -f "$fixture/$path" ] || fail "validator must preserve local drafts"
done

cp "$project_root/.gitignore" "$fixture/.gitignore"
git -C "$fixture" check-ignore --quiet docs/superpowers/plans/local\ draft.md \
  || fail "plans must be ignored by default"
git -C "$fixture" check-ignore --quiet docs/superpowers/specs/local\ draft.md \
  || fail "specs must be ignored by default"
git -C "$fixture" add --force docs/superpowers/specs/local\ draft.md
if sh "$validator" "$fixture" >/dev/null 2>&1; then
  fail "force-added ignored drafts must be rejected"
fi

echo "PASS: local planning artifacts stay out of the Git index"
