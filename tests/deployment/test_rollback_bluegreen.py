#!/usr/bin/env python3
"""Check exact retained revisions, shared locks, cleanup failures and timestamped evidence."""
import argparse
import contextlib
import datetime
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
spec = importlib.util.spec_from_file_location('rollback_bluegreen', ROOT / 'scripts/rollback-bluegreen.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
OLD = 'b' * 40
NEW = 'a' * 40
CONFIG = 'upstream frontend_active { server frontend-blue:8080; }\nupstream backend_active { server backend-blue:8080; }\n'


class Runner:
    lock = bluegreen.Deployment.lock

    def __init__(self, options, events, failure):
        self.options, self.events, self.failure = options, events, failure
        self.directory = options.runtime / 'edge'
        self.config = self.directory / 'default.conf'
        self.runtime = options.runtime

    def compose(self, *args):
        profile = 'prod' if self.failure() == 'profile' else 'dev'
        services = {name: {'environment': {'SPRING_PROFILES_ACTIVE': profile}, 'volumes': []}
                    for name in ('backend', 'backend-blue', 'backend-green')}
        services.update({'frontend-blue': {}, 'frontend-green': {}})
        if self.failure() == 'overlay':
            services['backend']['volumes'] = [{'target': '/missing'}]
        return json.dumps({'services': services})

    def check_mount(self):
        pass

    def workers(self):
        return {}

    def check_http(self):
        self.events.append('health-' + self.options.service)

    def verify_route(self, target):
        self.events.append('verify-' + target)

    def container(self, name, include_stopped=False):
        if self.failure() == 'missing' and name == 'frontend-green':
            raise bluegreen.DeploymentError('missing previous container')
        return name

    def check_target(self, name):
        self.events.append('healthy-' + name)
        if self.failure() == 'health' and name == 'frontend-green':
            raise bluegreen.DeploymentError('previous frontend is unhealthy')
        return name

    def archive_assets(self, target):
        pass

    def write_config(self, text):
        self.config.write_text(text)

    def reload(self):
        self.events.append('reload')
        with self.options.test_case.assertRaises(bluegreen.DeploymentError):
            with self.lock():
                raise AssertionError('rollback lost deployment lock')
        if self.failure() == 'reload' and 'server backend-green:8080' in self.config.read_text():
            raise bluegreen.DeploymentError('Nginx reload failed')

    def wait_workers(self, workers):
        self.events.append('drained')

    def retire(self, previous, target):
        self.events.append('retire-' + self.options.service)
        if self.failure() == 'retire-frontend' and self.options.service == 'frontend':
            raise bluegreen.DeploymentError('old frontend could not stop')


class RollbackTests(unittest.TestCase):
    def setUp(self):
        (ROOT / '.local').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / '.local', prefix='rollback-test-')
        self.runtime = Path(self.temporary.name)
        (self.runtime / 'edge').mkdir()
        (self.runtime / 'edge/default.conf').write_text(CONFIG)
        self.options = argparse.Namespace(runtime=self.runtime, project='fixture', target_revision=OLD,
            environment='dev', url='https://fixture.invalid', compose_override=[], drain_timeout=1,
            lock_fd=None, test_case=self)
        self.failure = None
        self.events = []
        self.builder = patch.object(bluegreen, 'Deployment', side_effect=lambda options: Runner(options, self.events, lambda: self.failure))
        self.builder.start()
        self.metadata = patch.object(bluegreen, 'docker', side_effect=self.inspect)
        self.metadata.start()
        self.rollback = module.Rollback(self.options)
        self.output = io.StringIO()
        self.redirect = contextlib.redirect_stdout(self.output)
        self.redirect.__enter__()

    def tearDown(self):
        self.redirect.__exit__(None, None, None)
        self.metadata.stop()
        self.builder.stop()
        self.temporary.cleanup()

    def inspect(self, *args, **kwargs):
        if args[:3] == ('inspect', '--format', '{{json .State}}'):
            return '{"Running":true}'
        if args[0] == 'inspect':
            index = {'backend-blue': '1', 'backend-green': '2', 'frontend-blue': '3', 'frontend-green': '4'}[args[-1]]
            return 'sha256:' + index * 64
        revision = OLD if args[-1][7] in '24' else NEW
        if self.failure == 'revision' and args[-1][7] == '4':
            revision = 'c' * 40
        return json.dumps({'org.opencontainers.image.revision': revision})

    def report(self):
        files = list((self.runtime / 'rollbacks').glob('*.json'))
        self.assertEqual(len(files), 1)
        return json.loads(files[0].read_text())

    def test_both_targets_are_validated_before_any_switch_and_evidence_has_timestamps(self):
        summary = self.runtime / 'summary.md'
        with patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': str(summary)}):
            result = self.rollback.run()
        self.assertLess(self.events.index('healthy-frontend-green'), self.events.index('reload'))
        self.assertLess(self.events.index('verify-frontend-green'), self.events.index('health-backend'))
        self.assertLess(self.events.index('drained'), self.events.index('retire-backend'))
        self.assertEqual(result['completed'], ['backend', 'frontend'])
        self.assertEqual(result['routes'], {'backend': 'backend-green', 'frontend': 'frontend-green'})
        self.assertTrue(result['routes_verified'])
        self.assertEqual(self.report(), result)
        started = datetime.datetime.fromisoformat(result['started_at'])
        ended = datetime.datetime.fromisoformat(result['completed_at'])
        self.assertLessEqual(started, ended)
        self.assertIsNotNone(started.tzinfo)
        self.assertGreaterEqual(result['duration_seconds'], 0)
        self.assertEqual(result['from']['backend']['revision'], NEW)
        self.assertEqual(result['to']['backend']['revision'], OLD)
        self.assertIn(result['started_at'], summary.read_text())
        self.assertIn(result['completed_at'], summary.read_text())

    def test_missing_target_wrong_revision_health_or_configuration_blocks_all_switches(self):
        for failure in ('missing', 'revision', 'health', 'profile', 'overlay'):
            with self.subTest(failure=failure):
                self.failure = failure
                self.events.clear()
                with self.assertRaises(bluegreen.DeploymentError):
                    self.rollback.run()
                self.assertFalse(any(event == 'reload' for event in self.events))

    def test_legacy_route_is_not_guessed_as_previous_version(self):
        self.rollback.runners['backend'].config.write_text(CONFIG.replace('backend-blue', 'backend'))
        with self.assertRaisesRegex(bluegreen.DeploymentError, 'Blue/Green'):
            self.rollback.run()
        self.assertFalse(any(event == 'reload' for event in self.events))

    def test_already_active_revision_is_rejected(self):
        self.options.target_revision = NEW
        with self.assertRaises(bluegreen.DeploymentError):
            self.rollback.run()
        self.assertFalse(any(event == 'reload' for event in self.events))

    def test_reload_failure_restores_both_previous_routes(self):
        self.failure = 'reload'
        with self.assertRaisesRegex(bluegreen.DeploymentError, 'previous routes restored'):
            self.rollback.run()
        report = self.report()
        self.assertEqual(report['completed'], [])
        self.assertEqual(report['routes'], {'backend': 'backend-blue', 'frontend': 'frontend-blue'})
        self.assertEqual(report['result'], 'failed')
        self.assertEqual(Path(report['backup']).read_text(), CONFIG)

    def test_cleanup_failure_keeps_verified_target_routes_but_reports_failure(self):
        self.failure = 'retire-frontend'
        with self.assertRaises(bluegreen.DeploymentError):
            self.rollback.run()
        report = self.report()
        self.assertEqual(report['result'], 'failed')
        self.assertEqual(report['completed'], ['backend', 'frontend'])
        self.assertEqual(report['stage'], 'cleanup-frontend')
        self.assertEqual(report['routes'], {'backend': 'backend-green', 'frontend': 'frontend-green'})
        self.assertFalse(report['routes_verified'])
        self.assertIsNotNone(report['completed_at'])

    def test_other_deployment_prevents_all_rollback_operations(self):
        with self.rollback.runners['frontend'].lock():
            with self.assertRaisesRegex(bluegreen.DeploymentError, 'Another deployment'):
                self.rollback.run()
        self.assertEqual(self.events, [])

    def test_inherited_lock_remains_held_by_actions_shell(self):
        with (self.runtime / 'deploy.lock').open('a') as handle:
            bluegreen.fcntl.flock(handle, bluegreen.fcntl.LOCK_EX | bluegreen.fcntl.LOCK_NB)
            self.options.lock_fd = handle.fileno()
            self.rollback.run()
            with self.assertRaises(bluegreen.DeploymentError):
                with self.rollback.runners['frontend'].lock():
                    raise AssertionError('parent lock was released')


class WorkflowTests(unittest.TestCase):
    def test_only_manual_dispatch_and_environment_branch_guards_share_deployment_concurrency(self):
        text = (ROOT / '.github/workflows/rollback.yml').read_text()
        self.assertIn('workflow_dispatch:', text)
        self.assertNotIn('  push:', text)
        self.assertIn('target_revision:', text)
        self.assertIn("github.ref == 'refs/heads/develop'", text)
        self.assertIn("github.ref == 'refs/heads/main'", text)
        self.assertIn('toadzip-${{ inputs.environment }}-deploy', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('--target-revision "$TARGET_REVISION"', text)
        self.assertIn('--lock-fd 9', text)
        self.assertNotIn('|| vars.PROD_', text)
        self.assertIn("vars[format('{0}_BLUEGREEN_RUNTIME'", text)
        self.assertNotIn('git reset --hard', text)
        self.assertNotIn('docker compose up', text)
        script = text.split('        run: |\n')[1]
        script = '\n'.join(line[10:] for line in script.splitlines())
        self.assertLess(script.index('flock --nonblock 9'), script.index('git fetch'))
        subprocess.run(['bash', '-n'], input=script, text=True, check=True)


if __name__ == '__main__':
    unittest.main()
