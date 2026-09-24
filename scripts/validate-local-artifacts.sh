#!/bin/sh
set -eu

root=${1:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}
root=$(CDPATH= cd -- "$root" && pwd -P)
git_root=$(git -C "$root" rev-parse --show-toplevel)
git_root=$(CDPATH= cd -- "$git_root" && pwd -P)
if [ "$root" != "$git_root" ]; then
  echo "local artifact check requires a repository root: $root" >&2
  exit 1
fi

tracked=$(git -C "$root" ls-files -- docs/superpowers/plans/ docs/superpowers/specs/)
if [ -n "$tracked" ]; then
  echo "Local plans/specs must never be committed:" >&2
  printf '%s\n' "$tracked" >&2
  echo "Preserve the local files and remove only their Git tracking with git rm --cached." >&2
  exit 1
fi

echo "local artifact check passed"
