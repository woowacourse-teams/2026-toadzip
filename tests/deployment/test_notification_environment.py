#!/usr/bin/env python3
"""Exercise Compose interpolation with public fixtures, never a developer .env."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PUBLIC_PUSH = {
    'VITE_NOTIFICATION_PUSH_ENABLED': 'true',
    'VITE_FIREBASE_API_KEY': 'public-notification-fixture',
    'VITE_FIREBASE_PROJECT_ID': 'notification-build-fixture',
    'VITE_FIREBASE_APP_ID': 'public-app-fixture',
    'VITE_FIREBASE_MESSAGING_SENDER_ID': '123456789',
    'VITE_FIREBASE_VAPID_PUBLIC_KEY': 'public-vapid-fixture',
}
SERVER_PUSH = {
    'NOTIFICATION_PUBLISHER_ENABLED': 'true',
    'NOTIFICATION_MATERIALIZER_ENABLED': 'true',
    'NOTIFICATION_DELIVERY_ENABLED': 'true',
    'NOTIFICATION_MAINTENANCE_ENABLED': 'true',
    'NOTIFICATION_ACTIVATION_AT': '2026-10-13T00:00:00Z',
    'NOTIFICATION_FRONTEND_ORIGIN': 'https://notification.example.invalid',
    'NOTIFICATION_FIREBASE_PROJECT_ID': 'notification-build-fixture',
}


def configuration(extra=None, overlays=()):
    environment = {key: os.environ[key] for key in
                   ('PATH', 'HOME', 'TMPDIR', 'DOCKER_CONFIG', 'DOCKER_HOST', 'DOCKER_CONTEXT')
                   if key in os.environ}
    environment.update({
        'PRIMARY_DB_HOST': 'fixture-primary', 'PRIMARY_DB_PASSWORD': 'fixture-only',
        'SHARED_DB_HOST': 'fixture-shared', 'SHARED_DB_PASSWORD': 'fixture-only',
        'SPRING_PROFILES_ACTIVE': 'prod', 'LOKI_PUSH_URL': 'http://fixture-loki',
    })
    environment.update(extra or {})
    command = ['docker', 'compose', '--env-file', '/dev/null', '-f', 'compose.yaml']
    for overlay in overlays:
        command.extend(['-f', overlay])
    result = subprocess.run([*command, 'config', '--format', 'json'], cwd=ROOT, env=environment,
                            capture_output=True, text=True, check=True, timeout=30)
    return json.loads(result.stdout)['services']


class NotificationEnvironmentTests(unittest.TestCase):
    def test_disabled_by_default_without_firebase_credentials(self):
        services = configuration()
        self.assertEqual(services['frontend']['build']['args'].get('VITE_NOTIFICATION_PUSH_ENABLED'), 'false')
        for key in SERVER_PUSH:
            if key.endswith('_ENABLED'):
                self.assertEqual(services['backend']['environment'].get(key), 'false', key)
        self.assertNotIn('GOOGLE_APPLICATION_CREDENTIALS', services['backend']['environment'])

    def test_public_build_configuration_and_server_runtime_configuration_arrive_separately(self):
        services = configuration({**PUBLIC_PUSH, **SERVER_PUSH,
                                  'NOTIFICATION_FIREBASE_CREDENTIALS_FILE': '/tmp/fixture-adc.json'})
        arguments = services['frontend']['build']['args']
        for key, value in PUBLIC_PUSH.items():
            self.assertEqual(arguments.get(key), value, key)
        for key, value in SERVER_PUSH.items():
            self.assertEqual(services['backend']['environment'].get(key), value, key)
            self.assertNotIn(key, arguments)
        self.assertNotIn('NOTIFICATION_FIREBASE_CREDENTIALS_FILE', arguments)
        self.assertNotIn('GOOGLE_APPLICATION_CREDENTIALS', arguments)

    def test_optional_credentials_are_mounted_read_only_only_on_backend(self):
        services = configuration({'NOTIFICATION_FIREBASE_CREDENTIALS_FILE': '/tmp/fixture-adc.json'},
                                 ('compose.notification-credentials.yaml',))
        target = '/run/secrets/notification-firebase-adc.json'
        self.assertEqual(services['backend']['environment']['GOOGLE_APPLICATION_CREDENTIALS'], target)
        mount = next(volume for volume in services['backend']['volumes'] if volume['target'] == target)
        self.assertEqual(mount['source'], '/tmp/fixture-adc.json')
        self.assertTrue(mount['read_only'])
        self.assertFalse(mount.get('bind', {}).get('create_host_path', False))
        self.assertFalse(services['frontend'].get('volumes'))
        self.assertNotIn('GOOGLE_APPLICATION_CREDENTIALS', services['frontend']['build']['args'])


if __name__ == '__main__':
    unittest.main()
