#!/usr/bin/env python3
"""Opt-in Docker integration check using disposable PostgreSQL and no real .env."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


class BackendCheck:
    def __init__(self, image):
        self.project = 'toadzip-backend-check-' + uuid.uuid4().hex[:10]
        self.runtime = ROOT / '.local' / self.project
        self.directory = self.runtime / 'edge'
        self.directory.mkdir(parents=True)
        (self.runtime / 'assets').mkdir()
        self.config = self.directory / 'default.conf'
        self.original = (ROOT / 'infra/nginx/edge/default.conf').read_text().replace(
            'server backend:8080;', 'server backend-blue:8080;', 1)
        if self.original.count('server backend-blue:8080;') != 1:
            raise RuntimeError('Expected the initial backend upstream exactly once')
        self.config.write_text(self.original)
        self.environment = {key: os.environ[key] for key in (
            'PATH', 'HOME', 'TMPDIR', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG') if key in os.environ}
        self.environment.update({
            'PRIMARY_DB_HOST': 'db', 'PRIMARY_DB_PORT': '5432', 'PRIMARY_DB_NAME': 'fixture',
            'PRIMARY_DB_USERNAME': 'fixture', 'PRIMARY_DB_PASSWORD': 'fixture-only',
            'SHARED_DB_HOST': 'db-shared', 'SHARED_DB_PORT': '5432', 'SHARED_DB_NAME': 'fixture',
            'SHARED_DB_USERNAME': 'fixture', 'SHARED_DB_PASSWORD': 'fixture-only',
            'SPRING_PROFILES_ACTIVE': 'local', 'LOKI_PUSH_URL': 'http://unused.invalid',
            'EDGE_NGINX_CONF_DIR': str(self.directory), 'EDGE_NGINX_ASSET_DIR': str(self.runtime / 'assets'),
            'NOTIFICATION_PUBLISHER_ENABLED': 'true', 'NOTIFICATION_MATERIALIZER_ENABLED': 'true',
            'NOTIFICATION_MAINTENANCE_ENABLED': 'true', 'NOTIFICATION_DELIVERY_ENABLED': 'false',
            'NOTIFICATION_ACTIVATION_AT': '2026-10-01T00:00:00Z',
        })
        image_id = self.docker('image', 'inspect', '--format', '{{.Id}}', image)
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
            raise RuntimeError('Use an existing local backend image')
        self.environment.update(BACKEND_BLUE_IMAGE=image_id, BACKEND_GREEN_IMAGE=image_id)
        self.fixture = self.runtime / 'compose.fixture.yaml'
        (self.runtime / 'frontend.conf').write_text(
            'server { listen 8080; location = /healthz { return 200 "ok"; } '
            'location = /manifest.webmanifest { return 200 "{}"; } '
            'location / { return 200 "frontend-fixture"; } }\n')
        self.fixture.write_text(f'''services:
  db:
    image: postgres:17-alpine
    environment: {{POSTGRES_DB: fixture, POSTGRES_USER: fixture, POSTGRES_PASSWORD: fixture-only}}
    tmpfs: [/var/lib/postgresql/data]
    healthcheck:
      test: [CMD-SHELL, pg_isready -U fixture -d fixture]
      interval: 1s
      timeout: 3s
      retries: 30
  db-shared:
    image: postgres:17-alpine
    environment: {{POSTGRES_DB: fixture, POSTGRES_USER: fixture, POSTGRES_PASSWORD: fixture-only}}
    tmpfs: [/var/lib/postgresql/data]
    healthcheck:
      test: [CMD-SHELL, pg_isready -U fixture -d fixture]
      interval: 1s
      timeout: 3s
      retries: 30
  frontend:
    image: nginx:stable-alpine
    ports: !reset []
    depends_on: !reset {{}}
    volumes:
      - {self.runtime}/frontend.conf:/etc/nginx/conf.d/default.conf:ro
  edge-nginx:
    ports: !override
      - target: 80
        published: '0'
        host_ip: 127.0.0.1
''')
        self.broken = self.runtime / 'compose.broken.yaml'
        self.broken.write_text('''services:
  backend-green:
    environment:
      SPRING_DATASOURCE_PASSWORD: intentionally-wrong-fixture-password
    healthcheck:
      interval: 1s
      timeout: 2s
      start_period: 5s
      retries: 3
    restart: 'no'
''')
        self.image_id = image_id
        self.cli = None
        self.stopped = threading.Event()
        self.monitor = None
        self.samples = []

    def command(self, arguments, check=True, timeout=240):
        result = subprocess.run(arguments, cwd=ROOT, env=self.environment,
                                capture_output=True, text=True, timeout=timeout)
        if check and result.returncode:
            # Only isolated fixture commands are executed; never print container environments.
            raise RuntimeError(f'Command failed: {arguments[:4]}\n{result.stderr[-2500:]}')
        return result

    def docker(self, *arguments):
        return self.command(['docker', *arguments]).stdout.strip()

    def compose(self, *arguments, broken=False, check=True):
        files = ['-f', 'compose.yaml', '-f', 'compose.bluegreen.yaml', '-f', str(self.fixture)]
        if broken:
            files += ['-f', str(self.broken)]
        return self.command(['docker', 'compose', '--env-file', '/dev/null', '--project-name',
                             self.project, *files, '--profile', 'bluegreen', *arguments], check=check)

    def container(self, service):
        ids = self.compose('ps', '--all', '-q', service).stdout.splitlines()
        if len(ids) != 1:
            raise RuntimeError('Expected one fixture container: ' + service)
        return ids[0]

    def request(self, path):
        with urllib.request.urlopen(self.url + path, timeout=4) as response:
            return response.status, response.read()

    def check_api(self):
        status, body = self.request('/api/health')
        assert status == 200 and json.loads(body)['status'] == 'UP'
        status, body = self.request('/api/v1/announcements?size=1')
        assert status == 200 and isinstance(json.loads(body)['data'], dict)
        assert self.request('/manifest.webmanifest')[0] == 200

    def verify_backend(self, service):
        container = self.container(service)
        probe = '/api/edge-probe-' + uuid.uuid4().hex
        since = datetime.datetime.now(datetime.timezone.utc).isoformat()
        for _ in range(15):
            try:
                self.request(probe)
            except urllib.error.HTTPError as error:
                assert error.code < 500
            logs = self.docker('logs', '--since', since, '--tail', '100', container)
            if probe in logs:
                self.check_api()
                return
            time.sleep(0.2)
        raise RuntimeError('Could not prove actual requests reaching ' + service)

    def write_config(self, text):
        temporary = self.directory / 'default.conf.tmp'
        temporary.write_text(text)
        temporary.replace(self.config)
        actual = self.docker('exec', self.container('edge-nginx'), 'cat', '/etc/nginx/conf.d/default.conf')
        assert actual == text.strip()

    def reload(self):
        edge = self.container('edge-nginx')
        self.docker('exec', edge, 'nginx', '-t')
        self.docker('exec', edge, 'nginx', '-s', 'reload')

    def switch(self, service, invalid=False):
        before = self.config.read_text()
        old_service = re.search(r'server (backend-(?:blue|green)):8080;', before).group(1)
        after = re.sub(r'server backend-(?:blue|green):8080;', 'server ' + service + ':8080;', before)
        if invalid:
            after += '\nintentionally_invalid_directive;\n'
        (self.runtime / ('before-' + uuid.uuid4().hex + '.conf.bak')).write_text(before)
        try:
            self.write_config(after)
            self.reload()
            self.verify_backend(service)
        except BaseException:
            self.write_config(before)
            self.reload()
            self.verify_backend(old_service)
            raise

    def sql(self, query):
        return self.docker('exec', self.container('db'), 'psql', '-U', 'fixture', '-d', 'fixture',
                           '-At', '-v', 'ON_ERROR_STOP=1', '-c', query)

    def history(self):
        return self.sql("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;") + self.sql(
            "SELECT version,checksum,success FROM privacy_flyway_schema_history ORDER BY installed_rank;")

    def watch(self):
        while not self.stopped.is_set():
            started = time.monotonic()
            try:
                self.check_api()
                failure = None
            except Exception as error:
                failure = type(error).__name__ + ': ' + str(error)
            self.samples.append({'elapsed_seconds': time.monotonic() - started, 'error': failure})
            self.stopped.wait(0.1)

    def start_linux_cli(self):
        self.cli = self.project + "-cli"
        empty_env = self.runtime / "empty.env"
        empty_env.touch()
        arguments = ['docker', 'run', '-d', '--name', self.cli, '--network', self.project + '_default',
                     '-v', '/var/run/docker.sock:/var/run/docker.sock', '-v', f'{ROOT}:{ROOT}:ro',
                     '-v', f'{self.runtime}:{self.runtime}', '-v', f'{empty_env}:{ROOT}/.env:ro', '-w', str(ROOT)]
        for key, value in self.environment.items():
            if key not in ('PATH', 'HOME', 'TMPDIR', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG'):
                arguments += ['-e', key + '=' + value]
        self.command([*arguments, 'docker:cli', 'sh', '-c',
                      'apk add --no-cache python3 bash >/dev/null && touch /tmp/ready && sleep 900'])
        for _ in range(60):
            if self.command(['docker', 'exec', self.cli, 'test', '-f', '/tmp/ready'], check=False).returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError('Linux CLI fixture is not ready')

    def deploy(self, broken=False, check=True):
        arguments = ['docker', 'exec', self.cli, 'bash', 'scripts/deploy-bluegreen.sh', '--service', 'backend',
                     '--project', self.project, '--runtime', str(self.runtime), '--url', 'http://edge-nginx',
                     '--compose-override', str(self.fixture), '--image', self.image_id]
        if broken:
            arguments += ['--compose-override', str(self.broken)]
        return self.command(arguments, check=check)

    def restore_blue(self):
        self.command(['docker', 'exec', self.cli, 'bash', 'scripts/switch-backend.sh', '--color', 'blue',
                      '--project', self.project, '--runtime', str(self.runtime), '--url', 'http://edge-nginx',
                      '--compose-override', str(self.fixture)])

    def run(self):
        try:
            print('Starting disposable PostgreSQL pair and actual backend Blue', flush=True)
            self.compose('up', '-d', '--no-build', '--no-deps', '--wait', '--wait-timeout', '60', 'db', 'db-shared')
            self.compose('up', '-d', '--no-build', '--no-deps', '--wait', '--wait-timeout', '180', 'backend-blue')
            self.compose('up', '-d', '--no-build', '--no-deps', 'frontend', 'edge-nginx')
            binding = json.loads(self.docker('inspect', '--format', '{{json .NetworkSettings.Ports}}',
                                            self.container('edge-nginx')))['80/tcp'][0]
            assert binding['HostIp'] == '127.0.0.1'
            self.url = 'http://127.0.0.1:' + binding['HostPort']
            self.verify_backend('backend-blue')
            self.sql("CREATE SCHEMA bluegreen_check; CREATE TABLE bluegreen_check.sentinel (id int PRIMARY KEY); "
                     "INSERT INTO bluegreen_check.sentinel VALUES (533);")
            history = self.history()
            self.start_linux_cli()
            self.monitor = threading.Thread(target=self.watch, daemon=True)
            self.monitor.start()
            failed = self.deploy(broken=True, check=False)
            assert failed.returncode != 0, 'Wrong DB credentials must reject the candidate'
            assert self.config.read_text() == self.original
            self.verify_backend('backend-blue')
            print('PASS: unhealthy candidate leaves Blue serving API', flush=True)
            self.deploy()
            for service in ('backend-blue', 'backend-green'):
                container = self.container(service)
                assert self.docker('inspect', '--format', '{{.State.Status}}', container) == ('exited' if service.endswith('blue') else 'running')
                if service.endswith('green'):
                    assert self.docker('inspect', '--format', '{{.State.Health.Status}}', container) == 'healthy'
                assert self.docker('inspect', '--format', '{{json .HostConfig.PortBindings}}', container) in ('{}', 'null')
            try:
                self.switch('backend-green', invalid=True)
            except RuntimeError as error:
                assert 'intentionally_invalid_directive' in str(error)
                self.verify_backend('backend-green')
            else:
                raise AssertionError('Invalid Nginx configuration was accepted')
            print('PASS: rejected Nginx configuration restores previous route', flush=True)
            self.verify_backend('backend-green')
            print('PASS: Linux deploy tool chose Green; actual API requests observed in Green logs', flush=True)
            self.restore_blue()
            self.verify_backend('backend-blue')
            print('PASS: Linux switch tool restored Blue; actual API requests observed in Blue logs', flush=True)
            assert history == self.history(), 'Unexpected Flyway history change'
            assert self.sql('SELECT id FROM bluegreen_check.sentinel;') == '533'
            print('PASS: primary/privacy Flyway history and sentinel data preserved', flush=True)
        finally:
            self.stopped.set()
            if self.monitor:
                self.monitor.join(timeout=6)
            (self.runtime / 'requests.json').write_text(json.dumps(self.samples, indent=2))
            logs = self.compose('logs', '--no-color', '--tail', '300', check=False)
            (self.runtime / 'containers.log').write_text(logs.stdout + logs.stderr)
            if self.cli:
                self.command(['docker', 'rm', '-f', self.cli], check=False)
            cleanup = self.compose('down', '--timeout', '10', check=False)
            if cleanup.returncode:
                raise RuntimeError('Fixture cleanup failed; inspect project ' + self.project)
        failures = [sample for sample in self.samples if sample['error']]
        assert self.samples and not failures, failures[:5]
        print(f'PASS: {len(self.samples)} API checks, zero observed failures; artifacts: {self.runtime}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='Locally built backend image; no image build or pull is performed')
    options = parser.parse_args()
    BackendCheck(options.image).run()
