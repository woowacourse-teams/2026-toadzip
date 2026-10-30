#!/usr/bin/env python3
"""Prepare deployment state without changing existing colors, Docker containers or source templates."""

import argparse
import fcntl
import os
from pathlib import Path
import sys

import bluegreen


def prepare(runtime, source):
    runtime = runtime.resolve()
    edge = runtime / 'edge'
    destination = edge / 'default.conf'
    original = bluegreen.ROOT / 'infra/nginx/edge/default.conf'
    if edge.resolve() == original.parent.resolve() or destination.resolve() == original.resolve():
        raise bluegreen.DeploymentError('Use the runtime directory, not the tracked configuration directory')
    # Validate before creating directories. An explicit old configuration can preserve its active colors.
    selected = destination if destination.exists() else source
    text = selected.read_text()
    for service in ('frontend', 'backend'):
        bluegreen.active_service(text, service)
    edge.mkdir(parents=True, exist_ok=True)
    with (edge.resolve().parent / 'deploy.lock').open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise bluegreen.DeploymentError('Another deployment is running') from error
        try:
            # Recheck under the shared lock; never overwrite an existing live configuration.
            if not destination.exists():
                with destination.open('x') as output:
                    output.write(text)
                    os.fchmod(output.fileno(), 0o644)
                    output.flush()
                    os.fsync(output.fileno())
            assets = edge.resolve().parent / 'assets'
            assets.mkdir(exist_ok=True, mode=0o755)
            assets.chmod(0o755)
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)
    print('Prepared deployment runtime: ' + str(runtime))
    print('Configuration: ' + str(destination))
    print('Existing configuration and assets were preserved')
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, default=bluegreen.DEFAULT_RUNTIME)
    parser.add_argument('--from-config', type=Path, default=bluegreen.ROOT / 'infra/nginx/edge/default.conf',
                        help='Existing runtime configuration to copy on first initialization, without moving it')
    options = parser.parse_args()
    try:
        prepare(options.runtime, options.from_config)
    except (OSError, bluegreen.DeploymentError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
