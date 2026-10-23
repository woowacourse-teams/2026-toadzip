#!/usr/bin/env python3
"""Check committed Flyway migrations against a develop commit without a database."""
import argparse
from pathlib import PurePosixPath
import re
import subprocess
import sys

LOCATIONS = (
    "backend/src/main/resources/db/migration",
    "backend/src/main/resources/db/privacy",
)
VERSIONED_NAME = re.compile(r"([VB])([0-9]+(?:[._][0-9]+)*)__.+\.sql")


def migration_files(revision):
    result = subprocess.run(
        ["git", "ls-tree", "-r", "-z", "--full-tree", revision, "--", *LOCATIONS],
        check=True, capture_output=True,
    )
    files = {}
    for entry in result.stdout.split(b"\0"):
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        name = path.decode("utf-8")
        if name.endswith(".sql"):
            files[name] = metadata.split()[2]
    return files


def version_parts(version):
    parts = [int(part) for part in re.split(r"[._]", version)]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def check_migrations(base, current):
    errors = []
    for path, content in sorted(base.items()):
        if path not in current:
            errors.append(f"Deleted or renamed existing migration: {path}")
        elif current[path] != content:
            errors.append(f"Modified existing migration: {path}")

    for location in LOCATIONS:
        base_versions = []
        for path in base:
            if path.startswith(location + "/"):
                match = VERSIONED_NAME.fullmatch(PurePosixPath(path).name)
                if match and match.group(1) == "V":
                    base_versions.append(version_parts(match.group(2)))
        latest = max(base_versions, default=None)
        versions = {}
        for path in sorted(current):
            if not path.startswith(location + "/"):
                continue
            name = PurePosixPath(path).name
            match = VERSIONED_NAME.fullmatch(name)
            if not match:
                if name.startswith(("V", "B")):
                    errors.append(f"Invalid migration filename: {path}")
                continue
            kind, version = match.groups()
            key = (kind, version_parts(version))
            if key in versions:
                errors.append(f"Duplicate {kind} version {version}: {versions[key]} and {path}")
            versions[key] = path
            if kind == "V" and path not in base and latest is not None and key[1] <= latest:
                latest_version = ".".join(str(part) for part in latest)
                errors.append(f"New migration {path} must be greater than develop version {latest_version}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", required=True, help="develop commit to compare with HEAD")
    arguments = parser.parse_args()
    try:
        base = migration_files(arguments.base_ref)
        current = migration_files("HEAD")
    except subprocess.CalledProcessError as error:
        print(f"Cannot read migration history: {error.stderr.decode().strip()}", file=sys.stderr)
        return 1
    errors = check_migrations(base, current)
    if errors:
        print("Flyway migration check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Flyway migration check passed against {arguments.base_ref}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
