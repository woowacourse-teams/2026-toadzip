#!/usr/bin/env python3
"""Build a verified Git revision, then deploy backend and frontend under one lock."""

import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid

import bluegreen


class Release:
    def __init__(self, options):
        self.options = options
        self.runners = {}
        for service in ('backend', 'frontend'):
            parameters = argparse.Namespace(**vars(options), service=service, action='deploy', image=None, color=None)
            self.runners[service] = bluegreen.Deployment(parameters)
        self.stage = 'preflight'
        self.images = {}
        self.completed = []

    @contextlib.contextmanager
    def lock(self):
        if self.options.lock_fd is None:
            with self.runners['frontend'].lock():
                yield
            return
        # CI holds this same open file description while fetching/checking out the revision.
        descriptor = os.dup(self.options.lock_fd)
        try:
            actual = os.fstat(descriptor)
            expected = (self.runners['frontend'].directory.parent / 'deploy.lock').stat()
            if (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
                raise bluegreen.DeploymentError('Inherited lock does not match the Edge deployment lock')
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise bluegreen.DeploymentError('Another deployment is running') from error
            yield
        finally:
            # Do not LOCK_UN: the parent shell still owns this lock through checkout and release.
            os.close(descriptor)

    def git(self, *arguments):
        return subprocess.check_output(['git', *arguments], cwd=bluegreen.ROOT, text=True).strip()

    def check_revision(self):
        if self.git('rev-parse', 'HEAD') != self.options.revision:
            raise bluegreen.DeploymentError('Checkout does not match the CI revision')
        if self.git('status', '--porcelain', '--untracked-files=all'):
            raise bluegreen.DeploymentError('Checkout contains local changes; preserve them before deployment')

    def preflight(self):
        self.check_revision()
        configuration = json.loads(self.runners['frontend'].compose('config', '--format', 'json'))
        services = configuration['services']
        base = services['backend']['environment']
        for name in ('backend', 'backend-blue', 'backend-green'):
            if services[name]['environment'].get('SPRING_PROFILES_ACTIVE') != self.options.environment:
                raise bluegreen.DeploymentError(f'Wrong Spring profile for {name}')
        for name in ('backend-blue', 'backend-green'):
            candidate = services[name]
            # Overlay settings attached only to the legacy backend must not disappear on Blue/Green.
            required_mounts = services['backend'].get('volumes', [])
            if (any(candidate['environment'].get(key) != value for key, value in base.items())
                    or any(mount not in candidate.get('volumes', []) for mount in required_mounts)):
                raise bluegreen.DeploymentError(f'Backend runtime overlay differs for {name}; configure both colors')
        for service in ('backend', 'frontend'):
            for color in ('blue', 'green'):
                if services[service + '-' + color].get('ports'):
                    raise bluegreen.DeploymentError('Blue/Green candidates must not publish host ports')
        for service in ('backend', 'frontend'):
            runner = self.runners[service]
            runner.check_mount()
            draining = {worker for worker, mode in runner.workers().items() if mode == 'draining'}
            if draining:
                runner.wait_workers(draining)
            runner.check_http()
            runner.verify_route(runner.container(bluegreen.active_service(runner.config.read_text(), service)))

    def build(self):
        runner = self.runners['frontend']
        services = {}
        for service in ('backend', 'frontend'):
            services[service] = {
                'image': 'toadzip-' + service + ':' + self.options.revision,
                'build': {'labels': {'org.opencontainers.image.revision': self.options.revision}},
            }
        # Only public image names and revision labels are written; resolved Compose secrets stay in memory.
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', prefix='release-build-',
                                         dir=runner.runtime) as override:
            json.dump({'services': services}, override)
            override.flush()
            for service in ('backend', 'frontend'):
                self.stage = 'build-' + service
                print('Release stage: ' + self.stage, flush=True)
                self.build_image(['docker', 'compose', '--project-name', self.options.project, *runner.files,
                                  '-f', override.name, '--profile', 'bluegreen', 'build', service], runner.environment)
                metadata = json.loads(bluegreen.docker('image', 'inspect', '--format', '{{json .}}', services[service]['image']))
                if (not re.fullmatch(r'sha256:[0-9a-f]{64}', metadata['Id'])
                        or metadata.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') != self.options.revision):
                    raise bluegreen.DeploymentError('Built image is missing the expected revision label')
                self.images[service] = metadata['Id']

    def build_image(self, arguments, environment):
        subprocess.run(arguments, cwd=bluegreen.ROOT, env=environment, check=True, timeout=1200)

    def report(self, result):
        state = {'revision': self.options.revision, 'environment': self.options.environment,
                 'stage': self.stage, 'result': result, 'completed': self.completed.copy(),
                 'images': self.images.copy(), 'routes': {},
                 'route_source': 'runtime-config', 'routes_verified': result == 'success'}
        for service, runner in self.runners.items():
            try:
                state['routes'][service] = bluegreen.active_service(runner.config.read_text(), service)
            except (OSError, bluegreen.DeploymentError):
                state['routes'][service] = 'unknown'
        # These fields contain revision/image IDs and service names, never resolved environment values.
        print('Release result: ' + json.dumps(state, sort_keys=True), flush=True)
        folder = self.runners['frontend'].runtime / 'releases'
        try:
            folder.mkdir(exist_ok=True)
            (folder / (self.options.revision + '-' + uuid.uuid4().hex + '.json')).write_text(json.dumps(state, indent=2))
            summary = os.environ.get('GITHUB_STEP_SUMMARY')
            if summary:
                with Path(summary).open('a') as handle:
                    handle.write(f"\nBlue/Green `{state['revision']}`: **{result}**, stage `{self.stage}`\n\n")
                    for service, route in state['routes'].items():
                        handle.write(f'- {service} configured upstream: `{route}`\n')
        except OSError:
            print('WARNING: Could not save the release report; use the result printed above', file=sys.stderr)
        return state

    def run(self):
        with self.lock():
            try:
                self.preflight()
                self.build()
                self.check_revision()
                for service in ('backend', 'frontend'):
                    self.stage = 'deploy-' + service
                    print('Release stage: ' + self.stage, flush=True)
                    self.runners[service].options.image = self.images[service]
                    self.runners[service].run_locked()
                    self.completed.append(service)
                self.stage = 'complete'
            except BaseException:
                self.report('failed')
                raise
            return self.report('success')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--environment', choices=['dev', 'prod'], required=True)
    parser.add_argument('--runtime', type=Path, default=bluegreen.DEFAULT_RUNTIME)
    parser.add_argument('--url', required=True, help='Public Edge/Caddy URL, verified without following redirects')
    parser.add_argument('--project', default='2026-toadzip')
    parser.add_argument('--compose-override', type=Path, action='append', default=[])
    parser.add_argument('--drain-timeout', type=int, default=330)
    parser.add_argument('--lock-fd', type=int, help='CI shell descriptor holding the shared deployment lock')
    options = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', options.revision) or options.drain_timeout < 1:
        parser.error('Expected a full Git SHA and positive drain timeout')
    options.compose_override += [Path(line) for line in os.environ.get('BLUEGREEN_COMPOSE_OVERRIDES', '').splitlines() if line.strip()]
    try:
        Release(options).run()
    except (bluegreen.DeploymentError, OSError, ValueError, subprocess.SubprocessError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('ERROR: release interrupted; inspect the reported routes', file=sys.stderr)
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
