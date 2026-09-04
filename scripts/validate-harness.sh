#!/bin/sh
set -eu

root=${1:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}
backend_readme="$root/backend/docs/README.md"
frontend_agents="$root/frontend/AGENTS.md"
frontend_readme="$root/frontend/README.md"
agent_validator=$(CDPATH= cd -- "$(dirname "$0")" && pwd)/validate-agent-config.py

fail() {
  echo "harness check failed: $1" >&2
  exit 1
}

required_files='README.md
CONTRIBUTING.md
AGENTS.md
SERVICE_OVERVIEW.md
backend/AGENTS.md
backend/CODE_CONVENTION.md
backend/docs/README.md
backend/docs/architecture.md
backend/docs/layer-boundaries.md
backend/docs/development-cycle.md
backend/docs/api-conventions.md
backend/docs/exception-handling.md
backend/docs/persistence.md
backend/docs/testing.md
backend/docs/security.md
backend/docs/observability.md
backend/docs/agent-collaboration.md
backend/docs/quality-gates.md
frontend/README.md
frontend/AGENTS.md
frontend/docs/development-standards.md
frontend/docs/quality-gates.md
.codex/agents/backend_explorer.toml
.codex/agents/backend_architect.toml
.codex/agents/backend_reviewer.toml'

printf '%s\n' "$required_files" | while IFS= read -r path; do
  [ -f "$root/$path" ] || fail "missing $path"
done

[ ! -e "$root/.codex/agents/backend_worker.toml" ] \
  || fail "writable backend worker must not exist"

python3 "$agent_validator" \
  "$root/.codex/agents/backend_explorer.toml" \
  "$root/.codex/agents/backend_architect.toml" \
  "$root/.codex/agents/backend_reviewer.toml" \
  || fail "backend agent roles must declare top-level read-only sandbox mode"

for path in backend/docs/code-conventions.md backend/docs/git-conventions.md \
  backend/docs/module-boundaries.md
do
  [ ! -e "$root/$path" ] || fail "duplicate or legacy document must not exist: $path"
done

grep -Fq '](SERVICE_OVERVIEW.md)' "$root/AGENTS.md" \
  || fail "AGENTS.md must link SERVICE_OVERVIEW.md"
grep -Fq '](backend/AGENTS.md)' "$root/AGENTS.md" \
  || fail "AGENTS.md must link backend/AGENTS.md"
grep -Fq '](frontend/AGENTS.md)' "$root/AGENTS.md" \
  || fail "AGENTS.md must link frontend/AGENTS.md"
grep -Fq '](backend/CODE_CONVENTION.md)' "$root/AGENTS.md" \
  || fail "AGENTS.md must link backend/CODE_CONVENTION.md"
grep -Fq '](CONTRIBUTING.md)' "$root/AGENTS.md" \
  || fail "AGENTS.md must link CONTRIBUTING.md"
grep -Fq '](docs/README.md)' "$root/backend/AGENTS.md" \
  || fail "backend/AGENTS.md must link docs/README.md"
grep -Fq '](CODE_CONVENTION.md)' "$root/backend/AGENTS.md" \
  || fail "backend/AGENTS.md must link CODE_CONVENTION.md"
grep -Fq '](../CONTRIBUTING.md)' "$root/backend/AGENTS.md" \
  || fail "backend/AGENTS.md must link CONTRIBUTING.md"
grep -Fq '](../../SERVICE_OVERVIEW.md)' "$backend_readme" \
  || fail "backend/docs/README.md must link SERVICE_OVERVIEW.md"
grep -Fq '](../CODE_CONVENTION.md)' "$backend_readme" \
  || fail "backend/docs/README.md must link backend/CODE_CONVENTION.md"
grep -Fq '](../../CONTRIBUTING.md)' "$backend_readme" \
  || fail "backend/docs/README.md must link CONTRIBUTING.md"

grep -Fq '](AGENTS.md)' "$frontend_readme" \
  || fail "frontend/README.md must link frontend/AGENTS.md"
grep -Fq '](../SERVICE_OVERVIEW.md)' "$frontend_agents" \
  || fail "frontend/AGENTS.md must link SERVICE_OVERVIEW.md"
grep -Fq '](../CONTRIBUTING.md)' "$frontend_agents" \
  || fail "frontend/AGENTS.md must link CONTRIBUTING.md"
grep -Fq '](README.md)' "$frontend_agents" \
  || fail "frontend/AGENTS.md must link frontend/README.md"
grep -Fq '](docs/development-standards.md)' "$frontend_agents" \
  || fail "frontend/AGENTS.md must link development-standards.md"
grep -Fq '](docs/quality-gates.md)' "$frontend_agents" \
  || fail "frontend/AGENTS.md must link quality-gates.md"

printf '%s\n' "$required_files" | while IFS= read -r path; do
  case "$path" in
    backend/docs/README.md|README.md|CONTRIBUTING.md|AGENTS.md|SERVICE_OVERVIEW.md|backend/AGENTS.md|backend/CODE_CONVENTION.md) continue ;;
    backend/docs/*)
      name=${path#backend/docs/}
      grep -Fq "$name" "$backend_readme" \
        || fail "backend/docs/README.md must link $name"
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
