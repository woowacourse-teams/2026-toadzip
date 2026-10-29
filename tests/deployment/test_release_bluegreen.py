#!/usr/bin/env python3
"""Check CI revision, all-or-nothing build preparation, shared locks and partial release reporting."""

import argparse
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import bluegreen
spec = importlib.util.spec_from_file_location('release_bluegreen', ROOT / 'scripts/release-bluegreen.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SHA = 'a' * 40
CONFIG = 'upstream frontend_active { server frontend-blue:8080; }\nupstream backend_active { server backend-blue:8080; }\n'


class Runner:
    lock = bluegreen.Deployment.lock

    def __init__(self, options, events, failure):
        self.options = options
        self.runtime = options.runtime
        self.directory = self.runtime / 'edge'
        self.config = self.directory / 'default.conf'
        self.files = ['-f', 'compose.yaml', '-f', 'compose.bluegreen.yaml']
        self.environment = {'FIXTURE_SECRET': 'never-log-this-fixture'}
        self.events = events
        self.failure = failure

    def compose(self, *arguments):
        base = {'SPRING_PROFILES_ACTIVE': 'dev', 'SPRING_DATASOURCE_PASSWORD': 'never-log-this-fixture'}
        services = {name: {'environment': copy.deepcopy(base), 'volumes': []}
                    for name in ('backend', 'backend-blue', 'backend-green')}
        services.update({'frontend-blue': {}, 'frontend-green': {}, 'frontend': {}})
        if self.failure() == 'profile':
            services['backend-green']['environment']['SPRING_PROFILES_ACTIVE'] = 'prod'
        if self.failure() == 'overlay':
            services['backend']['environment']['GOOGLE_APPLICATION_CREDENTIALS'] = '/fixture/adc.json'
        if self.failure() == 'ports':
            services['frontend-green']['ports'] = [{'published': '80'}]
        return json.dumps({'services': services})

    def check_mount(self):
        self.events.append('preflight-' + self.options.service)
        if self.failure() == 'preflight-' + self.options.service:
            raise bluegreen.DeploymentError('runtime not prepared')

    def workers(self):
        return {}

    def check_http(self):
        pass

    def container(self, name):
        return name

    def verify_route(self, target):
        pass

    def run_locked(self):
        service = self.options.service
        self.events.append('deploy-' + service)
        try:
            with self.lock():
                raise AssertionError('release lock was dropped')
        except bluegreen.DeploymentError:
            pass
        if self.failure() == 'deploy-' + service:
            raise bluegreen.DeploymentError('component failed')
        self.config.write_text(bluegreen.replace_service(self.config.read_text(), service + '-green', service))


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        (ROOT / '.local').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / '.local', prefix='release-test-')
        self.runtime = Path(self.temporary.name)
        (self.runtime / 'edge').mkdir()
        (self.runtime / 'edge/default.conf').write_text(CONFIG)
        self.options = argparse.Namespace(runtime=self.runtime, project='fixture', revision=SHA,
            environment='dev', url='https://fixture.invalid', compose_override=[], drain_timeout=1, lock_fd=None)
        self.events = []
        self.failure = None
        self.builder = patch.object(bluegreen, 'Deployment', side_effect=lambda options: Runner(options, self.events, lambda: self.failure))
        self.builder.start()
        self.release = module.Release(self.options)
        self.release.git = lambda *args: ('b' * 40 if self.failure == 'revision' else SHA) if args[0] == 'rev-parse' else ('dirty' if self.failure == 'dirty' else '')
        self.release.build_image = self.build_image
        self.metadata = patch.object(bluegreen, 'docker', side_effect=self.inspect_image)
        self.metadata.start()
        self.output = io.StringIO()
        self.redirect = contextlib.redirect_stdout(self.output)
        self.redirect.__enter__()

    def tearDown(self):
        self.redirect.__exit__(None, None, None)
        self.metadata.stop()
        self.builder.stop()
        self.temporary.cleanup()

    def build_image(self, arguments, environment):
        service = arguments[-1]
        self.events.append('build-' + service)
        override = json.loads(Path(arguments[arguments.index('--profile') - 1]).read_text())
        self.assertEqual(override['services'][service]['image'], 'toadzip-' + service + ':' + SHA)
        with self.assertRaises(bluegreen.DeploymentError):
            with self.release.runners['frontend'].lock():
                self.fail('build did not keep the deployment lock')
        if self.failure == 'build-' + service:
            raise bluegreen.DeploymentError('image build failed')

    def inspect_image(self, *arguments, **kwargs):
        return json.dumps({'Id': 'sha256:' + '1' * 64,
            'Config': {'Labels': {'org.opencontainers.image.revision': 'wrong' if self.failure == 'label' else SHA}}})

    def test_preflight_and_both_builds_finish_before_backend_then_frontend(self):
        result = self.release.run()
        self.assertEqual(self.events, ['preflight-backend', 'preflight-frontend', 'build-backend', 'build-frontend', 'deploy-backend', 'deploy-frontend'])
        self.assertEqual(result['completed'], ['backend', 'frontend'])
        self.assertEqual(result['route_source'], 'runtime-config')
        self.assertTrue(result['routes_verified'])
        self.assertEqual(result['routes'], {'backend': 'backend-green', 'frontend': 'frontend-green'})
        self.assertNotIn('never-log-this-fixture', self.output.getvalue())

    def test_invalid_revision_or_local_changes_stop_before_any_build(self):
        for failure in ('revision', 'dirty'):
            with self.subTest(failure=failure):
                self.failure = failure
                with self.assertRaises(bluegreen.DeploymentError):
                    self.release.run()
                self.assertEqual(self.events, [])

    def test_profile_overlay_and_candidate_ports_fail_closed(self):
        for failure in ('profile', 'overlay', 'ports'):
            with self.subTest(failure=failure):
                self.failure = failure
                with self.assertRaises(bluegreen.DeploymentError):
                    self.release.run()
                self.assertEqual(self.events, [])

    def test_missing_frontend_runtime_prevents_backend_deployment_and_build(self):
        self.failure = 'preflight-frontend'
        with self.assertRaises(bluegreen.DeploymentError):
            self.release.run()
        self.assertFalse(any(event.startswith(('build-', 'deploy-')) for event in self.events))

    def test_frontend_build_failure_never_deploys_backend(self):
        self.failure = 'build-frontend'
        with self.assertRaises(bluegreen.DeploymentError):
            self.release.run()
        self.assertFalse(any(event.startswith('deploy-') for event in self.events))
        self.assertEqual((self.runtime / 'edge/default.conf').read_text(), CONFIG)

    def test_failed_backend_prevents_frontend_and_failed_frontend_reports_partial_release(self):
        for failure in ('deploy-backend', 'deploy-frontend'):
            with self.subTest(failure=failure):
                self.events.clear()
                self.release.completed.clear()
                (self.runtime / 'edge/default.conf').write_text(CONFIG)
                self.failure = failure
                with self.assertRaises(bluegreen.DeploymentError):
                    self.release.run()
                report = json.loads(self.output.getvalue().split('Release result: ')[-1])
                self.assertEqual(report['stage'], failure)
                self.assertFalse(report['routes_verified'])
                if failure == 'deploy-backend':
                    self.assertNotIn('deploy-frontend', self.events)
                else:
                    self.assertEqual(report['completed'], ['backend'])
                    self.assertEqual(report['routes'], {'backend': 'backend-green', 'frontend': 'frontend-blue'})

    def test_checkout_change_during_build_stops_before_deployment(self):
        build = self.release.build_image
        def mutate_checkout(arguments, environment):
            build(arguments, environment)
            if arguments[-1] == 'frontend':
                self.failure = 'revision'
        self.release.build_image = mutate_checkout
        with self.assertRaisesRegex(bluegreen.DeploymentError, 'CI revision'):
            self.release.run()
        self.assertFalse(any(event.startswith('deploy-') for event in self.events))

    def test_wrong_image_label_prevents_all_deployment(self):
        self.failure = 'label'
        with self.assertRaises(bluegreen.DeploymentError):
            self.release.run()
        self.assertFalse(any(event.startswith('deploy-') for event in self.events))

    def test_inherited_descriptor_keeps_parent_lock_after_release(self):
        lock = self.runtime / 'deploy.lock'
        with lock.open('a') as handle:
            module.fcntl.flock(handle, module.fcntl.LOCK_EX | module.fcntl.LOCK_NB)
            self.options.lock_fd = handle.fileno()
            self.release.run()
            with self.assertRaises(bluegreen.DeploymentError):
                with self.release.runners['frontend'].lock():
                    self.fail('parent lock was released')
        self.options.lock_fd = None
        with self.release.runners['frontend'].lock():
            pass

    def test_wrong_inherited_lock_is_rejected(self):
        with (self.runtime / 'wrong.lock').open('a') as handle:
            (self.runtime / 'deploy.lock').touch()
            self.options.lock_fd = handle.fileno()
            with self.assertRaisesRegex(bluegreen.DeploymentError, 'does not match'):
                self.release.run()


class WorkflowTests(unittest.TestCase):
    def test_ci_release_uses_exact_revision_under_lock_and_preserves_existing_ci_gates(self):
        for environment, branch, prefix in [('dev', 'develop', 'DEV'), ('prod', 'main', 'PROD')]:
            text = (ROOT / '.github/workflows' / ('deploy-' + environment + '.yml')).read_text()
            self.assertIn('needs: [backend, frontend]', text)
            self.assertIn('cancel-in-progress: false', text)
            self.assertIn('DEPLOY_BRANCH: ' + branch, text)
            self.assertIn('vars.' + prefix + '_BLUEGREEN_URL', text)
            self.assertIn('git checkout --detach "$CI_COMMIT"', text)
            self.assertLess(text.index('flock --nonblock 9'), text.index('git fetch'))
            self.assertIn('--lock-fd 9', text)
            self.assertNotIn('docker compose up', text)
            self.assertNotIn('git pull', text)
            self.assertNotIn('git reset --hard', text)
            script = text.split('        run: |\n')[1]
            script = '\n'.join(line[10:] for line in script.splitlines())
            subprocess.run(['bash', '-n'], input=script, text=True, check=True)


if __name__ == '__main__':
    unittest.main()
