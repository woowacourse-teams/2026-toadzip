#!/usr/bin/env python3
"""Verify the production Caddy topology on isolated loopback ports with a disposable local CA."""

import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('backend_check', ROOT / 'tests/deployment/test_backend_bluegreen.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CaddyCheck(module.BackendCheck):
    def __init__(self):
        super().__init__('nginx:stable-alpine')
        self.environment['TOADZIP_CADDY_DOMAIN'] = 'localhost'
        self.original = self.original.replace('server backend-blue:8080;', 'server backend:8080;')
        self.config.write_text(self.original)
        (self.runtime / 'assets/chunk-old.mjs').write_text('export default "old"')
        for name, version in [('backend', 'old'), ('green', 'green')]:
            (self.runtime / (name + '.conf')).write_text('''server {
                listen 8080;
                default_type application/json;
                location = /api/health { return 200 '{"status":"UP","version":"VERSION"}'; }
                location = /api/headers { return 200 '{"proto":"$http_x_forwarded_proto","host":"$http_host"}'; }
            }'''.replace('VERSION', version))
        (self.runtime / 'frontend.conf').write_text('server { listen 8080; location = /healthz { return 200 "ok"; } location / { return 200 "frontend"; } }')
        self.fixture.write_text(f'''services:
  backend:
    image: nginx:stable-alpine
    ports: !reset []
    volumes: ['{self.runtime}/backend.conf:/etc/nginx/conf.d/default.conf:ro']
    healthcheck:
      test: [CMD, wget, -qO-, http://127.0.0.1:8080/api/health]
      interval: 1s
      timeout: 2s
      retries: 15
      start_period: 0s
  backend-green:
    image: nginx:stable-alpine
    volumes: ['{self.runtime}/green.conf:/etc/nginx/conf.d/default.conf:ro']
    healthcheck:
      test: [CMD, wget, -qO-, http://127.0.0.1:8080/api/health]
      interval: 1s
      timeout: 2s
      retries: 15
      start_period: 0s
  frontend:
    image: nginx:stable-alpine
    depends_on: !reset {{}}
    volumes: ['{self.runtime}/frontend.conf:/etc/nginx/conf.d/default.conf:ro']
    healthcheck:
      interval: 1s
      start_period: 0s
  edge-nginx:
    ports: !override
      - target: 80
        published: '0'
        host_ip: 127.0.0.1
  caddy:
    ports: !override
      - target: 80
        published: '0'
        host_ip: 127.0.0.1
      - target: 443
        published: '0'
        host_ip: 127.0.0.1
''')

    def compose(self, *arguments, check=True):
        files = ['-f', 'compose.yaml', '-f', 'compose.bluegreen.yaml', '-f', 'compose.caddy-server.yaml', '-f', str(self.fixture)]
        return self.command(['docker', 'compose', '--env-file', '/dev/null', '--project-name', self.project,
                             *files, '--profile', 'bluegreen', *arguments], check=check)

    def retry(self, callback):
        failure = None
        for _ in range(40):
            try:
                return callback()
            except (RuntimeError, AssertionError) as error:
                failure = error
                time.sleep(.25)
        raise failure

    def certificate(self, destination):
        self.docker('cp', self.container('caddy') + ':/data/caddy/pki/authorities/local/root.crt', str(destination))
        return destination.read_bytes()

    def https(self, path, headers=()):
        arguments = ['curl', '--fail', '--silent', '--show-error', '--connect-timeout', '2', '--max-time', '5',
                     '--cacert', str(self.ca), '--resolve', 'localhost:' + self.port + ':127.0.0.1']
        for header in headers:
            arguments += ['-H', header]
        return self.command([*arguments, 'https://localhost:' + self.port + path]).stdout

    def run(self):
        try:
            print('Starting isolated Caddy -> Edge -> frontend/backend topology', flush=True)
            self.compose('up', '-d', '--no-deps', '--no-build', '--wait', 'backend', 'backend-green', 'frontend')
            self.compose('up', '-d', '--no-deps', 'edge-nginx', 'caddy')
            caddy = self.container('caddy')
            ports = json.loads(self.docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', caddy))
            self.port = ports['443/tcp'][0]['HostPort']
            http_port = ports['80/tcp'][0]['HostPort']
            self.ca = self.runtime / 'test-root.crt'
            before = self.retry(lambda: self.certificate(self.ca))
            self.retry(lambda: self.https('/'))
            assert self.https('/') == 'frontend'
            assert json.loads(self.https('/api/health')) == {'status': 'UP', 'version': 'old'}
            headers = json.loads(self.https('/api/headers', headers=('X-Forwarded-Proto: http',)))
            assert headers['proto'] == 'https'
            assert headers['host'] == 'localhost'
            assert self.https('/assets/chunk-old.mjs') == 'export default "old"'
            response = self.command(['curl', '-sS', '--max-time', '5', '-D', '-', '-o', '/dev/null',
                                     '-H', 'Host: localhost', 'http://127.0.0.1:' + http_port + '/']).stdout.lower()
            assert '308' in response and 'location: https://localhost/' in response
            print('PASS: verified TLS, HTTP redirect, frontend/API/assets, HTTPS forwarding without a redirect loop', flush=True)
            temporary = self.directory / 'switch.tmp'
            temporary.write_text(self.config.read_text().replace('server backend:8080;', 'server backend-green:8080;'))
            temporary.replace(self.config)
            self.docker('exec', self.container('edge-nginx'), 'nginx', '-t')
            self.docker('exec', self.container('edge-nginx'), 'nginx', '-s', 'reload')
            def green():
                assert json.loads(self.https('/api/health'))['version'] == 'green'
            self.retry(green)
            assert self.container('caddy') == caddy
            print('PASS: backend route changes through Edge while Caddy remains running', flush=True)
            self.compose('up', '-d', '--no-deps', '--force-recreate', 'caddy')
            assert self.container('caddy') != caddy
            # Recreating the container may allocate another host port; the same CA must still work.
            ports = json.loads(self.docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', self.container('caddy')))
            self.port = ports['443/tcp'][0]['HostPort']
            after = self.retry(lambda: self.certificate(self.runtime / 'after-root.crt'))
            assert after == before
            self.retry(green)
            print('PASS: Caddy data volume preserves the CA across container recreation', flush=True)
        finally:
            logs = self.compose('logs', '--no-color', '--tail', '120', check=False)
            (self.runtime / 'containers.log').write_text(logs.stdout + logs.stderr)
            # Only the unique disposable test project owns these certificate volumes.
            cleanup = self.compose('down', '--volumes', '--timeout', '10', check=False)
            assert cleanup.returncode == 0, 'Fixture cleanup failed: ' + self.project
        print('PASS: Caddy ingress integration; artifacts: ' + str(self.runtime), flush=True)


if __name__ == '__main__':
    CaddyCheck().run()
