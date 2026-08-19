#!/bin/sh
set -eu

root=${1:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}
readme="$root/backend/docs/README.md"

fail() {
  echo "harness check failed: $1" >&2
  exit 1
}

required_files='AGENTS.md
SERVICE_OVERVIEW.md
backend/AGENTS.md
backend/docs/README.md
backend/docs/architecture.md
backend/docs/module-boundaries.md
backend/docs/development-cycle.md
backend/docs/code-conventions.md
backend/docs/git-conventions.md
backend/docs/api-conventions.md
backend/docs/persistence.md
backend/docs/testing.md
backend/docs/security.md
backend/docs/observability.md
backend/docs/agent-collaboration.md
backend/docs/quality-gates.md
.codex/agents/backend_explorer.toml
.codex/agents/backend_architect.toml
.codex/agents/backend_reviewer.toml'

printf '%s\n' "$required_files" | while IFS= read -r path; do
  [ -f "$root/$path" ] || fail "missing $path"
done

[ ! -e "$root/.codex/agents/backend_worker.toml" ] \
  || fail "writable backend worker must not exist"

grep -Fq 'SERVICE_OVERVIEW.md' "$root/AGENTS.md" \
  || fail "AGENTS.md must link SERVICE_OVERVIEW.md"
grep -Fq 'backend/AGENTS.md' "$root/AGENTS.md" \
  || fail "AGENTS.md must link backend/AGENTS.md"
grep -Fq 'docs/README.md' "$root/backend/AGENTS.md" \
  || fail "backend/AGENTS.md must link docs/README.md"

printf '%s\n' "$required_files" | while IFS= read -r path; do
  case "$path" in
    backend/docs/README.md|AGENTS.md|SERVICE_OVERVIEW.md|backend/AGENTS.md) continue ;;
    backend/docs/*)
      name=${path#backend/docs/}
      grep -Fq "$name" "$readme" || fail "backend/docs/README.md must link $name"
      ;;
  esac
done

printf '%s\n' "$required_files" | while IFS= read -r path; do
  case "$path" in
    AGENTS.md|SERVICE_OVERVIEW.md|backend/AGENTS.md|backend/docs/*.md)
      lines=$(wc -l < "$root/$path" | tr -d ' ')
      [ "$lines" -le 70 ] || fail "$path has $lines lines; maximum is 70"
      ;;
  esac
done

echo "harness check passed"
