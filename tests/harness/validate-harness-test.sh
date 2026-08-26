#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
validator="$project_root/scripts/validate-harness.sh"
fixture=$(mktemp -d "${TMPDIR:-/tmp}/harness-test.XXXXXX")
trap 'rm -rf "$fixture"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

make_valid_fixture() {
  root=$1
  mkdir -p "$root/backend/docs" "$root/.codex/agents"
  printf '%s\n' '# Service' > "$root/SERVICE_OVERVIEW.md"
  printf '%s\n' '# Project' > "$root/README.md"
  printf '%s\n' '# Contributing' > "$root/CONTRIBUTING.md"
  printf '%s\n' '# Code convention' > "$root/backend/CODE_CONVENTION.md"
  printf '%s\n' '# Repository map' \
    '[SERVICE_OVERVIEW.md](SERVICE_OVERVIEW.md)' \
    '[backend/AGENTS.md](backend/AGENTS.md)' \
    '[CONTRIBUTING.md](CONTRIBUTING.md)' \
    '[backend/CODE_CONVENTION.md](backend/CODE_CONVENTION.md)' \
    > "$root/AGENTS.md"
  printf '%s\n' '# Backend contract' '[docs/README.md](docs/README.md)' \
    '[CODE_CONVENTION.md](CODE_CONVENTION.md)' \
    '[CONTRIBUTING.md](../CONTRIBUTING.md)' > "$root/backend/AGENTS.md"
  printf '%s\n' '[SERVICE_OVERVIEW.md](../../SERVICE_OVERVIEW.md)' \
    '[CODE_CONVENTION.md](../CODE_CONVENTION.md)' \
    '[CONTRIBUTING.md](../../CONTRIBUTING.md)' \
    > "$root/backend/docs/README.md"
  for name in architecture layer-boundaries development-cycle api-conventions \
    exception-handling \
    persistence testing security observability \
    agent-collaboration quality-gates
  do
    printf '# %s\n' "$name" > "$root/backend/docs/$name.md"
    printf '%s\n' "$name.md" >> "$root/backend/docs/README.md"
  done
  for role in backend_explorer backend_architect backend_reviewer
  do
    printf 'name = "%s"\nsandbox_mode = "read-only"\n' "$role" \
      > "$root/.codex/agents/$role.toml"
  done
}

make_valid_fixture "$fixture/valid"
"$validator" "$fixture/valid" >/dev/null || fail "valid harness must pass"

make_valid_fixture "$fixture/long"
i=0
while [ "$i" -lt 70 ]; do
  printf 'line %s\n' "$i" >> "$fixture/long/backend/docs/security.md"
  i=$((i + 1))
done
if "$validator" "$fixture/long" >/dev/null 2>&1; then
  fail "71-line required harness document must fail"
fi

make_valid_fixture "$fixture/unlinked"
sed '/security.md/d' "$fixture/unlinked/backend/docs/README.md" \
  > "$fixture/unlinked/backend/docs/README.tmp"
mv "$fixture/unlinked/backend/docs/README.tmp" "$fixture/unlinked/backend/docs/README.md"
if "$validator" "$fixture/unlinked" >/dev/null 2>&1; then
  fail "unlinked required document must fail"
fi

make_valid_fixture "$fixture/unlinked-original"
sed '/CONTRIBUTING.md/d' "$fixture/unlinked-original/backend/docs/README.md" \
  > "$fixture/unlinked-original/backend/docs/README.tmp"
mv "$fixture/unlinked-original/backend/docs/README.tmp" \
  "$fixture/unlinked-original/backend/docs/README.md"
if "$validator" "$fixture/unlinked-original" >/dev/null 2>&1; then
  fail "unlinked original contribution guide must fail"
fi

make_valid_fixture "$fixture/plain-original-path"
sed 's|\[CONTRIBUTING.md\](../../CONTRIBUTING.md)|`../../CONTRIBUTING.md`|' \
  "$fixture/plain-original-path/backend/docs/README.md" \
  > "$fixture/plain-original-path/backend/docs/README.tmp"
mv "$fixture/plain-original-path/backend/docs/README.tmp" \
  "$fixture/plain-original-path/backend/docs/README.md"
if "$validator" "$fixture/plain-original-path" >/dev/null 2>&1; then
  fail "non-link original document path must fail"
fi

make_valid_fixture "$fixture/missing-role"
rm "$fixture/missing-role/.codex/agents/backend_reviewer.toml"
if "$validator" "$fixture/missing-role" >/dev/null 2>&1; then
  fail "missing backend agent role must fail"
fi

make_valid_fixture "$fixture/writable-worker"
printf 'name = "backend_worker"\n' \
  > "$fixture/writable-worker/.codex/agents/backend_worker.toml"
if "$validator" "$fixture/writable-worker" >/dev/null 2>&1; then
  fail "writable backend worker must fail"
fi

make_valid_fixture "$fixture/writable-role"
sed 's/sandbox_mode = "read-only"/sandbox_mode = "workspace-write"/' \
  "$fixture/writable-role/.codex/agents/backend_architect.toml" \
  > "$fixture/writable-role/.codex/agents/backend_architect.tmp"
mv "$fixture/writable-role/.codex/agents/backend_architect.tmp" \
  "$fixture/writable-role/.codex/agents/backend_architect.toml"
if "$validator" "$fixture/writable-role" >/dev/null 2>&1; then
  fail "writable backend role must fail"
fi

make_valid_fixture "$fixture/root-only"
rm -rf "$fixture/root-only/backend"
mkdir -p "$fixture/root-only/docs"
printf '# Legacy map\n' > "$fixture/root-only/docs/README.md"
if "$validator" "$fixture/root-only" >/dev/null 2>&1; then
  fail "root-only backend harness must fail"
fi

make_valid_fixture "$fixture/missing-convention"
rm "$fixture/missing-convention/backend/CODE_CONVENTION.md"
if "$validator" "$fixture/missing-convention" >/dev/null 2>&1; then
  fail "missing original code convention document must fail"
fi

make_valid_fixture "$fixture/missing-contributing"
rm "$fixture/missing-contributing/CONTRIBUTING.md"
if "$validator" "$fixture/missing-contributing" >/dev/null 2>&1; then
  fail "missing original contribution guide must fail"
fi

make_valid_fixture "$fixture/missing-exception-handling"
rm "$fixture/missing-exception-handling/backend/docs/exception-handling.md"
if "$validator" "$fixture/missing-exception-handling" >/dev/null 2>&1; then
  fail "missing exception handling convention must fail"
fi

make_valid_fixture "$fixture/duplicate-code-convention"
: > "$fixture/duplicate-code-convention/backend/docs/code-conventions.md"
if "$validator" "$fixture/duplicate-code-convention" >/dev/null 2>&1; then
  fail "duplicate code convention document must fail"
fi

make_valid_fixture "$fixture/duplicate-git-convention"
: > "$fixture/duplicate-git-convention/backend/docs/git-conventions.md"
if "$validator" "$fixture/duplicate-git-convention" >/dev/null 2>&1; then
  fail "duplicate Git convention document must fail"
fi

make_valid_fixture "$fixture/legacy-boundaries"
: > "$fixture/legacy-boundaries/backend/docs/module-boundaries.md"
if "$validator" "$fixture/legacy-boundaries" >/dev/null 2>&1; then
  fail "legacy module boundary document must fail"
fi

make_valid_fixture "$fixture/project-docs"
mkdir -p "$fixture/project-docs/docs"
i=0
while [ "$i" -lt 100 ]; do
  printf 'README line %s\n' "$i" >> "$fixture/project-docs/README.md"
  printf 'CONTRIBUTING line %s\n' "$i" >> "$fixture/project-docs/CONTRIBUTING.md"
  printf 'CODE_CONVENTION line %s\n' "$i" \
    >> "$fixture/project-docs/backend/CODE_CONVENTION.md"
  printf 'SETUP line %s\n' "$i" >> "$fixture/project-docs/docs/SETUP.md"
  i=$((i + 1))
done
"$validator" "$fixture/project-docs" >/dev/null \
  || fail "project Markdown outside the harness contract must pass"

echo "PASS: harness validator behavior"
