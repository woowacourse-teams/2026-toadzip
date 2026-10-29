#!/usr/bin/env python3
"""Restore retained containers for an explicit revision and record recovery evidence."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

import bluegreen


class Rollback:
    def __init__(self, options):
        self.options = options
        self.runners = {service: bluegreen.Deployment(argparse.Namespace(
            **vars(options), service=service, action='switch', image=None, color=None))
            for service in ('backend', 'frontend')}
        self.source = {}
        self.targets = {}
        self.completed = []
        self.stage = 'preflight'

    def metadata(self, container):
        image = bluegreen.docker('inspect', '--format', '{{.Image}}', container)
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
            raise bluegreen.DeploymentError('Container image is not an immutable image ID')
        labels = json.loads(bluegreen.docker('image', 'inspect', '--format', '{{json .Config.Labels}}', image)) or {}
        revision = labels.get('org.opencontainers.image.revision', '')
        return {'container': container, 'image': image,
                'revision': revision if re.fullmatch(r'[0-9a-f]{40}', revision) else None}

    def preflight(self):
        services = json.loads(self.runners['frontend'].compose('config', '--format', 'json'))['services']
        base = services['backend']
        for name in ('backend', 'backend-blue', 'backend-green'):
            if services[name]['environment'].get('SPRING_PROFILES_ACTIVE') != self.options.environment:
                raise bluegreen.DeploymentError('Wrong Spring profile for ' + name)
        for name in ('backend-blue', 'backend-green'):
            candidate = services[name]
            if (any(candidate['environment'].get(key) != value for key, value in base['environment'].items())
                    or any(mount not in candidate.get('volumes', []) for mount in base.get('volumes', []))):
                raise bluegreen.DeploymentError('Backend runtime overlay differs for ' + name)
        for service, runner in self.runners.items():
            for color in ('blue', 'green'):
                if services[service + '-' + color].get('ports'):
                    raise bluegreen.DeploymentError('Blue/Green candidates must not publish host ports')
            runner.check_mount()
            draining = {worker for worker, mode in runner.workers().items() if mode == 'draining'}
            if draining:
                runner.wait_workers(draining)
            current = bluegreen.active_service(runner.config.read_text(), service)
            if current not in (service + '-blue', service + '-green'):
                raise bluegreen.DeploymentError('Rollback requires a retained Blue/Green pair: ' + service)
            color = 'green' if current.endswith('-blue') else 'blue'
            target = service + '-' + color
            self.source[service] = dict(self.metadata(runner.container(current, include_stopped=True)), service=current)
            self.targets[service] = dict(self.metadata(runner.container(target, include_stopped=True)), service=target)
            if self.source[service]['revision'] == self.options.target_revision:
                raise bluegreen.DeploymentError('Requested revision is already active for ' + service)
            if self.targets[service]['revision'] != self.options.target_revision:
                raise bluegreen.DeploymentError('Retained ' + target + ' does not match the requested revision')
            runner.options.color = color
        # Validate both retained revisions before starting either one. Starting an old
        # application can itself run Flyway; schema compatibility remains a prerequisite.
        for service, runner in self.runners.items():
            runner.check_target(self.targets[service]['service'])

    def switch(self):
        frontend = self.runners['frontend']
        before = frontend.config.read_text()
        after = before
        for service, runner in self.runners.items():
            after = bluegreen.replace_service(after, self.targets[service]['service'], service)
        frontend.archive_assets(self.source['frontend']['container'])
        frontend.archive_assets(self.targets['frontend']['container'])
        previous_workers = set(frontend.workers())
        self.backup = frontend.runtime / ('before-rollback-' + uuid.uuid4().hex + '.conf.bak')
        with self.backup.open('x') as handle:
            handle.write(before)
        try:
            self.stage = 'switch-routes'
            # Both upstreams must point to prepared containers before nginx -t.
            # A stopped source container may no longer resolve in Docker DNS.
            frontend.write_config(after)
            frontend.reload()
            self.verify()
            frontend.wait_workers(previous_workers)
        except (Exception, KeyboardInterrupt) as failure:
            try:
                frontend.write_config(before)
                frontend.reload()
                for service, runner in self.runners.items():
                    runner.verify_route(self.source[service]['container'])
                    runner.check_http()
            except (Exception, KeyboardInterrupt) as recovery:
                raise bluegreen.DeploymentError('Rollback failed (' + str(failure) + '); recovery failed (' + str(recovery) + '); backup: ' + str(self.backup)) from recovery
            raise bluegreen.DeploymentError('Rollback failed; previous routes restored; backup: ' + str(self.backup)) from failure
        self.completed = list(self.runners)
        for service, runner in self.runners.items():
            self.stage = 'cleanup-' + service
            previous = self.source[service]['container']
            state = json.loads(bluegreen.docker('inspect', '--format', '{{json .State}}', previous))
            if state['Running']:
                runner.retire(previous, self.targets[service]['container'])
        self.stage = 'verify-complete'
        self.verify()

    def verify(self):
        for service, runner in self.runners.items():
            runner.verify_route(self.targets[service]['container'])
        for runner in self.runners.values():
            runner.check_http()

    def report(self, result):
        state = {'operation': 'rollback', 'environment': self.options.environment,
                 'target_revision': self.options.target_revision, 'result': result, 'stage': self.stage,
                 'started_at': self.started_at,
                 'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 'duration_seconds': round(time.monotonic() - self.started, 3),
                 'completed': self.completed.copy(), 'from': self.source, 'to': self.targets,
                 'routes': {}, 'routes_verified': result == 'success',
                 'backup': str(self.backup) if hasattr(self, 'backup') else None}
        for service, runner in self.runners.items():
            try:
                state['routes'][service] = bluegreen.active_service(runner.config.read_text(), service)
            except (OSError, bluegreen.DeploymentError):
                state['routes'][service] = 'unknown'
        print('Rollback result: ' + json.dumps(state, sort_keys=True), flush=True)
        try:
            folder = self.runners['frontend'].runtime / 'rollbacks'
            folder.mkdir(exist_ok=True)
            (folder / (uuid.uuid4().hex + '.json')).write_text(json.dumps(state, indent=2))
        except OSError:
            print('WARNING: Could not save rollback report; retain the Actions log', file=sys.stderr)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            try:
                with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as handle:
                    handle.write('\n## Blue/Green rollback: ' + result + '\n\n')
                    handle.write('- Environment: `' + self.options.environment + '`\n')
                    handle.write('- Target revision: `' + self.options.target_revision + '`\n')
                    handle.write('- Started (UTC): ' + state['started_at'] + '\n')
                    handle.write('- Completed (UTC): ' + state['completed_at'] + '\n')
                    handle.write('- Duration: ' + str(state['duration_seconds']) + ' seconds\n')
                    handle.write('- Stage: `' + self.stage + '`\n\n')
                    for service in self.runners:
                        before, after = self.source.get(service, {}), self.targets.get(service, {})
                        handle.write('- ' + service + ': `' + str(before.get('revision')) + '` → `'
                                     + str(after.get('revision')) + '`, configured route: `'
                                     + state['routes'][service] + '`\n')
                        handle.write('  - Images: `' + str(before.get('image')) + '` → `'
                                     + str(after.get('image')) + '`\n')
                    handle.write('\nCompleted components: ' + ', '.join(self.completed) + '\n')
            except OSError:
                print('WARNING: Could not write Actions summary; retain the Actions log', file=sys.stderr)
        return state

    def run(self):
        with self.runners['frontend'].lock(self.options.lock_fd):
            self.started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
            self.started = time.monotonic()
            try:
                self.preflight()
                self.switch()
                self.stage = 'complete'
            except BaseException:
                self.report('failed')
                raise
            return self.report('success')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target-revision', required=True, help='Full revision of both retained images')
    parser.add_argument('--environment', choices=['dev', 'prod'], required=True)
    parser.add_argument('--url', required=True)
    parser.add_argument('--runtime', type=Path, default=bluegreen.DEFAULT_RUNTIME)
    parser.add_argument('--project', default='2026-toadzip')
    parser.add_argument('--compose-override', type=Path, action='append', default=[])
    parser.add_argument('--drain-timeout', type=int, default=330)
    parser.add_argument('--lock-fd', type=int)
    options = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', options.target_revision) or options.drain_timeout < 1:
        parser.error('Expected a full target revision and positive drain timeout')
    options.compose_override += [Path(line) for line in os.environ.get('BLUEGREEN_COMPOSE_OVERRIDES', '').splitlines() if line.strip()]
    try:
        Rollback(options).run()
    except (bluegreen.DeploymentError, OSError, ValueError, subprocess.SubprocessError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('ERROR: rollback interrupted; inspect reported routes', file=sys.stderr)
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
