#!/usr/bin/env python3
"""Validate application-server Caddy ingress without real .env or certificate files."""

import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'DOCKER_CONFIG', 'DOCKER_HOST', 'DOCKER_CONTEXT') if key in os.environ}
FIXTURE = {'PRIMARY_DB_HOST': 'fixture', 'PRIMARY_DB_PASSWORD': 'fixture-only',
           'SHARED_DB_HOST': 'fixture', 'SHARED_DB_PASSWORD': 'fixture-only',
           'SPRING_PROFILES_ACTIVE': 'dev', 'LOKI_PUSH_URL': 'http://fixture.invalid',
           'TOADZIP_CADDY_DOMAIN': 'dev.bokduckbang.com',
           'EDGE_NGINX_CONF_DIR': '/tmp/ingress-fixture/edge',
           'EDGE_NGINX_ASSET_DIR': '/tmp/ingress-fixture/assets'}


def configuration(extra=None, files=('compose.yaml', 'compose.bluegreen.yaml', 'compose.caddy-server.yaml'), check=True):
    command = ['docker', 'compose', '--env-file', '/dev/null']
    for file in files:
        command += ['-f', file]
    return subprocess.run([*command, '--profile', 'bluegreen', 'config', '--format', 'json'], cwd=ROOT,
                          env={**BASE, **FIXTURE, **(extra or {})}, capture_output=True, text=True, check=check, timeout=30)


class CaddyIngressTests(unittest.TestCase):
    def test_only_caddy_owns_public_web_ports_and_frontend_remains_http(self):
        for domain, profile in [('dev.bokduckbang.com', 'dev'), ('bokduckbang.com', 'prod')]:
            with self.subTest(domain=domain):
                services = json.loads(configuration({'TOADZIP_CADDY_DOMAIN': domain, 'SPRING_PROFILES_ACTIVE': profile}).stdout)['services']
                ports = {(str(port['published']), port['target']) for port in services['caddy']['ports']}
                self.assertEqual(ports, {('80', 80), ('443', 443)})
                for name in ('frontend', 'frontend-blue', 'frontend-green', 'backend-blue', 'backend-green'):
                    self.assertFalse(services[name].get('ports'), name)
                self.assertEqual(services['frontend']['command'], ['nginx', '-c', '/etc/nginx/nginx.conf', '-g', 'daemon off;'])
                self.assertEqual(services['caddy']['environment']['TOADZIP_CADDY_DOMAIN'], domain)
                self.assertEqual(services['caddy']['depends_on']['edge-nginx']['condition'], 'service_started')
                self.assertEqual(services['backend']['environment']['SPRING_PROFILES_ACTIVE'], profile)
                for name in ('backend-blue', 'backend-green'):
                    self.assertEqual(services[name]['environment']['SPRING_PROFILES_ACTIVE'], profile)

    def test_server_requires_domain_and_existing_runtime_paths(self):
        self.assertNotEqual(configuration({'TOADZIP_CADDY_DOMAIN': ''}, check=False).returncode, 0)
        services = json.loads(configuration().stdout)['services']
        for mount in services['edge-nginx']['volumes']:
            self.assertTrue(mount['read_only'])
            self.assertFalse(mount['bind']['create_host_path'])

    def test_default_server_paths_are_in_infra_runtime_not_local(self):
        services = json.loads(configuration({'EDGE_NGINX_CONF_DIR': '', 'EDGE_NGINX_ASSET_DIR': ''}).stdout)['services']
        mounts = {mount['target']: mount['source'] for mount in services['edge-nginx']['volumes']}
        self.assertEqual(mounts['/etc/nginx/conf.d'], str(ROOT / 'infra/runtime/bluegreen/edge'))
        self.assertEqual(mounts['/var/cache/toadzip/assets'], str(ROOT / 'infra/runtime/bluegreen/assets'))

    def test_persistent_caddy_data_and_private_admin_api(self):
        settings = json.loads(configuration().stdout)
        service = settings['services']['caddy']
        volumes = {mount['target']: mount for mount in service['volumes']}
        self.assertEqual(set(volumes), {'/etc/caddy/Caddyfile', '/data', '/config'})
        self.assertTrue(volumes['/etc/caddy/Caddyfile']['read_only'])
        for target in ('/data', '/config'):
            self.assertEqual(volumes[target]['type'], 'volume')
            self.assertIn(volumes[target]['source'], settings['volumes'])
        self.assertNotIn(2019, {port['target'] for port in service['ports']})

    def test_local_caddy_configuration_is_unchanged(self):
        services = json.loads(configuration(files=('compose.yaml', 'compose.caddy.yaml')).stdout)['services']
        ports = services['caddy']['ports']
        self.assertEqual(len(ports), 1)
        self.assertEqual((ports[0]['host_ip'], str(ports[0]['published']), ports[0]['target']), ('127.0.0.1', '18443', 443))
        self.assertTrue(services['frontend']['ports'])

    def test_legacy_frontend_tls_is_not_retained_by_server_overlay(self):
        settings = configuration({'TOADZIP_TLS_DOMAIN': 'dev.bokduckbang.com', 'TOADZIP_TLS_GID': '42424'},
            files=('compose.yaml', 'compose.https.yaml', 'compose.bluegreen.yaml', 'compose.caddy-server.yaml'))
        frontend = json.loads(settings.stdout)['services']['frontend']
        self.assertFalse(frontend.get('ports'))
        self.assertFalse(frontend.get('volumes'))
        self.assertFalse(frontend.get('group_add'))
        self.assertFalse(frontend.get('environment'))
        self.assertNotIn('/tmp/https.conf', frontend['command'])


if __name__ == '__main__':
    unittest.main()
