#!/usr/bin/env python3
"""Build a verified Git revision, then deploy backend, AI worker and frontend under one lock."""

import argparse
import contextlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid

import bluegreen

# Built and labelled with the same revision. The worker takes no traffic, so it is recreated instead of Blue/Green.
IMAGES = ('backend', 'frontend', 'ai-worker')
WORKER = 'ai-worker'
WORKER_SECRETS = ('PGPASSWORD', 'DEEPSEEK_API_KEY')


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
        with self.runners['frontend'].lock(self.options.lock_fd):
            yield

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
        self.check_worker()

    def check_worker(self):
        # Naming the profiled service includes it; secrets are only checked for presence and never printed.
        worker = json.loads(self.runners['frontend'].compose('config', '--format', 'json', WORKER)).get('services', {}).get(WORKER)
        if worker is None:
            raise bluegreen.DeploymentError('Compose configuration has no ai-worker service')
        missing = [name for name in WORKER_SECRETS if not worker.get('environment', {}).get(name)]
        if missing:
            raise bluegreen.DeploymentError('AI worker is missing server settings: ' + ', '.join(missing)
                                            + ' (set AI_WORKER_DB_PASSWORD and DEEPSEEK_API_KEY)')

    def build(self):
        runner = self.runners['frontend']
        services = {}
        for service in IMAGES:
            services[service] = {
                'image': 'toadzip-' + service + ':' + self.options.revision,
                'build': {'labels': {'org.opencontainers.image.revision': self.options.revision}},
            }
        # Only public image names and revision labels are written; resolved Compose secrets stay in memory.
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', prefix='release-build-',
                                         dir=runner.runtime) as override:
            json.dump({'services': services}, override)
            override.flush()
            for service in IMAGES:
                self.stage = 'build-' + service
                print('Release stage: ' + self.stage, flush=True)
                self.build_image(['docker', 'compose', '--project-name', self.options.project, *runner.files,
                                  '-f', override.name, '--profile', 'bluegreen', 'build', service], runner.environment)
                metadata = json.loads(bluegreen.docker('image', 'inspect', '--format', '{{json .}}', services[service]['image']))
                if (not re.fullmatch(r'sha256:[0-9a-f]{64}', metadata['Id'])
                        or metadata.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') != self.options.revision):
                    raise bluegreen.DeploymentError('Built image is missing the expected revision label')
                self.images[service] = metadata['Id']

    def deploy_worker(self):
        runner = self.runners['frontend']
        environment = dict(runner.environment, AI_WORKER_IMAGE=self.images[WORKER])
        # A wrong password or missing ai_jobs grant would otherwise surface only after the running worker was replaced.
        check = self.check_worker_database(['docker', 'compose', '--project-name', self.options.project, *runner.files,
                                            '--profile', 'bluegreen', 'run', '--rm', '-T', '--no-deps', '--pull', 'never',
                                            WORKER, '--check'], environment)
        if check.returncode:
            # The worker prints only fixed text and the database error, never the password.
            reason = (check.stderr.strip().splitlines() or ['no output'])[-1]
            raise bluegreen.DeploymentError('AI worker database check failed with the new image; the running worker'
                                            ' was left as is: ' + reason)
        # Stopping waits up to stop_grace_period (2m) for the running extraction to finish. --wait needs the image
        # healthcheck, so a worker that later loses the database fails the release instead of looking up.
        runner.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate',
                       '--wait', '--wait-timeout', '60', WORKER, environment=environment, timeout=240)

    def check_worker_database(self, arguments, environment):
        return subprocess.run(arguments, cwd=bluegreen.ROOT, env=environment, text=True, capture_output=True, timeout=120)

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
                for service in ('backend', 'frontend', WORKER):
                    self.stage = 'deploy-' + service
                    print('Release stage: ' + self.stage, flush=True)
                    if service == WORKER:
                        # Last, after the backend migrated the schema; a worker failure leaves backend and frontend matched.
                        self.deploy_worker()
                    else:
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
