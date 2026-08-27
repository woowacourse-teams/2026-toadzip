#!/usr/bin/env python3
import re
import sys
from pathlib import Path


SANDBOX_TOKEN = re.compile(r"\bsandbox_mode\b")
READ_ONLY_VALUE = re.compile(
    r'^[ \t]*sandbox_mode[ \t]*=[ \t]*"read-only"[ \t]*(?:#.*)?$'
)


def first_nested_context(lines):
    # Keep this contract deliberately narrow instead of partially parsing TOML.
    # The single sandbox declaration must precede tables and multiline values.
    for index, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        if stripped.startswith("[") or '"""' in line or "'''" in line:
            return index
    return len(lines)


def validate_read_only(path_text: str) -> bool:
    path = Path(path_text)
    try:
        with path.open(encoding="utf-8") as source:
            lines = list(source)
    except (OSError, UnicodeError) as error:
        print(f"invalid agent config {path}: {error}", file=sys.stderr)
        return False

    occurrences = [
        (index, line.rstrip("\r\n"))
        for index, line in enumerate(lines)
        if SANDBOX_TOKEN.search(line)
    ]
    if len(occurrences) != 1:
        print(f"agent config must mention sandbox_mode once: {path}", file=sys.stderr)
        return False

    index, line = occurrences[0]
    if index < first_nested_context(lines) and READ_ONLY_VALUE.match(line):
        return True

    print(f"agent config must declare top-level read-only sandbox mode: {path}", file=sys.stderr)
    return False


def main() -> int:
    if not sys.argv[1:]:
        print("usage: validate-agent-config.py AGENT.toml...", file=sys.stderr)
        return 2

    return 0 if all(validate_read_only(path) for path in sys.argv[1:]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
