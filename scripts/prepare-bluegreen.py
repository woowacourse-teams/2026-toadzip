#!/usr/bin/env python3
"""Prepare deployment state without changing existing colors, Docker containers or source templates."""

import argparse
import fcntl
import os
from pathlib import Path
import re
import sys
import tempfile
import uuid

import bluegreen


def upgrade_metrics(text):
    active = bluegreen.active_service(text, 'backend')
    if 'backend_metrics_active' in text:
        bluegreen.metrics_service(text)
        return text
    if re.search(r'\blisten\s+(?:[^;\s]+:)?8081\b', text):
        raise bluegreen.DeploymentError('Runtime already has a management listener; review it before upgrading')
    template = (bluegreen.ROOT / 'infra/nginx/edge/default.conf').read_text()
    block = template.split('# 백엔드 메트릭 설정 시작\n', 1)[1].split('# 백엔드 메트릭 설정 끝', 1)[0]
    block = bluegreen.upstream_pattern('backend_metrics').sub(
        lambda match: match.group(1) + active + match.group(3), block)
    return text.rstrip() + '\n\n# 백엔드 메트릭 설정 시작\n' + block + '# 백엔드 메트릭 설정 끝\n'


def prepare(runtime, source, upgrade=False):
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
            # 기본 준비는 기존 상태를 보존하고, 명시적 갱신도 같은 잠금 안에서 수행한다.
            if not destination.exists():
                with destination.open('x') as output:
                    output.write(text)
                    os.fchmod(output.fileno(), 0o644)
                    output.flush()
                    os.fsync(output.fileno())
            if upgrade:
                current = destination.read_text()
                updated = upgrade_metrics(current)
                if updated != current:
                    backup = edge.resolve().parent / ('before-metrics-' + uuid.uuid4().hex + '.conf.bak')
                    with backup.open('x') as output:
                        os.fchmod(output.fileno(), destination.stat().st_mode & 0o777)
                        output.write(current)
                        output.flush()
                        os.fsync(output.fileno())
                    descriptor, temporary = tempfile.mkstemp(dir=edge, prefix='metrics-', suffix='.tmp')
                    try:
                        with os.fdopen(descriptor, 'w') as output:
                            os.fchmod(output.fileno(), destination.stat().st_mode & 0o777)
                            output.write(updated)
                            output.flush()
                            os.fsync(output.fileno())
                        os.replace(temporary, destination)
                    finally:
                        Path(temporary).unlink(missing_ok=True)
                    print('Previous configuration: ' + str(backup))
            assets = edge.resolve().parent / 'assets'
            assets.mkdir(exist_ok=True, mode=0o755)
            assets.chmod(0o755)
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)
    print('Prepared deployment runtime: ' + str(runtime))
    print('Configuration: ' + str(destination))
    print('Active colors and assets were preserved; Docker was not reloaded')
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, default=bluegreen.DEFAULT_RUNTIME)
    parser.add_argument('--from-config', type=Path, default=bluegreen.ROOT / 'infra/nginx/edge/default.conf',
                        help='Existing runtime configuration to copy on first initialization, without moving it')
    parser.add_argument('--upgrade-metrics', action='store_true',
                        help='Back up and add metrics routing to the runtime, preserving active colors; does not reload Nginx')
    options = parser.parse_args()
    try:
        prepare(options.runtime, options.from_config, options.upgrade_metrics)
    except (OSError, bluegreen.DeploymentError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
