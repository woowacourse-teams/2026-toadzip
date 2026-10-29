#!/usr/bin/env python3
"""Opt-in Linux/Nginx long-request, retirement, rollback and asset-version integration check."""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('backend_check', ROOT / 'tests/deployment/test_backend_bluegreen.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

SERVER = '''from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json, os, signal, sys, threading, time
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split('?')[0]
        if path in ('/slow', '/api/slow'):
            name = 'frontend' if path == '/slow' else 'backend'
            Path('/control/started-' + name).touch()
            deadline = time.monotonic() + 90
            while not Path('/control/release-' + name).exists():
                if time.monotonic() > deadline:
                    self.send_error(504); return
                time.sleep(.05)
            body = os.environ['COLOR'].encode()
        elif path == '/api/color':
            body = json.dumps({'color': os.environ['COLOR']}).encode()
        elif path in ('/api/health', '/actuator/health'):
            body = b'{"status":"UP"}'
        elif path == '/api/v1/announcements':
            body = b'{"data":{}}'
        else:
            self.send_error(404); return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def log_message(self, fmt, *args):
        print(fmt % args, flush=True)
threading.Thread(target=ThreadingHTTPServer(('0.0.0.0',8081),Handler).serve_forever,daemon=True).start()
ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
'''


class DrainCheck(module.BackendCheck):
    def __init__(self):
        super().__init__('docker:cli')
        self.control = self.runtime / 'control'
        self.control.mkdir()
        self.original = self.original.replace('server frontend:8080;', 'server frontend-blue:8080;')
        self.config.write_text(self.original)
        self.logs = []
        self.pool = ThreadPoolExecutor(max_workers=1)

    def build_fixtures(self):
        build = self.runtime / 'build'
        build.mkdir()
        (build / 'server.py').write_text(SERVER)
        (build / 'Dockerfile').write_text('FROM docker:cli\nRUN apk add --no-cache python3 bash curl\nCOPY server.py /fixture-server.py\n')
        runner = self.project + '-runner'
        self.command(['docker', 'build', '--pull=false', '-t', runner, str(build)])
        self.image_id = self.docker('image', 'inspect', '--format', '{{.Id}}', runner)
        self.environment.update(BACKEND_BLUE_IMAGE=self.image_id, BACKEND_GREEN_IMAGE=self.image_id)
        self.frontends = {}
        for version in ('v1', 'v2', 'v3', 'bad'):
            html = build / 'html'
            (html / 'assets').mkdir(parents=True, exist_ok=True)
            # Only these fixture files are replaced; each Docker image keeps its own assets.
            for path in (html / 'assets').iterdir():
                path.unlink()
            (html / 'index.html').write_text('<script type="module" src="/assets/app-' + version + '.js"></script>' + version)
            (html / 'assets' / ('app-' + version + '.js')).write_text('import("/assets/chunk-' + version + '.mjs")')
            (html / 'assets' / ('chunk-' + version + '.mjs')).write_text('export default "' + version + '"')
            (html / 'manifest.webmanifest').write_text(json.dumps({'version': version}))
            (build / 'frontend.conf').write_text('''server {
                listen 8080;
                root /usr/share/nginx/html;
                location = /healthz { return 200 "ok"; }
                location /slow { proxy_pass http://backend-blue:8080/slow; }
                location /assets/ { try_files $uri =404; }
                location / { try_files $uri /index.html; }
            }''')
            if version == 'bad':
                config = build / 'frontend.conf'
                config.write_text(config.read_text().replace('listen 8080;', 'listen 8080; location ^~ /__edge-probe- { return 500; }'))
            (build / 'Dockerfile.frontend').write_text('FROM nginx:stable-alpine\nCOPY frontend.conf /etc/nginx/conf.d/default.conf\nCOPY html/ /usr/share/nginx/html/\n')
            tag = self.project + '-' + version
            self.command(['docker', 'build', '--pull=false', '-f', str(build / 'Dockerfile.frontend'), '-t', tag, str(build)])
            self.frontends[version] = self.docker('image', 'inspect', '--format', '{{.Id}}', tag)
        self.environment.update(FRONTEND_BLUE_IMAGE=self.frontends['v1'], FRONTEND_GREEN_IMAGE=self.frontends['v2'])
        self.fixture.write_text(f'''services:
  backend-blue:
    entrypoint: [python3, -u, /fixture-server.py]
    environment: {{COLOR: blue}}
    volumes: ['{self.control}:/control']
    healthcheck:
      test: [CMD, curl, -fsS, http://127.0.0.1:8081/actuator/health]
      interval: 1s
      timeout: 2s
      retries: 15
      start_period: 0s
  backend-green:
    entrypoint: [python3, -u, /fixture-server.py]
    environment: {{COLOR: green}}
    volumes: ['{self.control}:/control']
    healthcheck:
      test: [CMD, curl, -fsS, http://127.0.0.1:8081/actuator/health]
      interval: 1s
      timeout: 2s
      retries: 15
      start_period: 0s
  frontend-blue:
    healthcheck:
      interval: 1s
      start_period: 0s
  frontend-green:
    healthcheck:
      interval: 1s
      start_period: 0s
  edge-nginx:
    ports: !override
      - target: 80
        published: '0'
        host_ip: 127.0.0.1
''')

    def start_cli(self):
        self.cli = self.project + '-cli'
        empty = self.runtime / 'empty.env'
        empty.touch()
        args = ['docker', 'run', '-d', '--name', self.cli, '--network', self.project + '_default',
                '-v', '/var/run/docker.sock:/var/run/docker.sock', '-v', f'{ROOT}:{ROOT}:ro',
                '-v', f'{self.runtime}:{self.runtime}', '-v', f'{empty}:{ROOT}/.env:ro', '-w', str(ROOT)]
        for key, value in self.environment.items():
            if key not in ('PATH', 'HOME', 'TMPDIR', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG'):
                args += ['-e', key + '=' + value]
        self.command([*args, self.image_id, 'sh', '-c', 'sleep 900'])

    def arguments(self, action, service, image=None, color=None, timeout=30, overlay=None):
        script = 'deploy-bluegreen.sh' if action == 'deploy' else 'switch-' + service + '.sh'
        args = ['docker', 'exec', self.cli, 'bash', 'scripts/' + script, '--service', service,
                '--project', self.project, '--runtime', str(self.runtime), '--url', 'http://edge-nginx',
                '--compose-override', str(self.fixture), '--drain-timeout', str(timeout)]
        if overlay:
            args += ['--compose-override', str(overlay)]
        if image:
            args += ['--image', image]
        if color:
            args += ['--color', color]
        return args

    def invoke(self, *args, check=True, **kwargs):
        result = self.command(self.arguments(*args, **kwargs), check=check)
        self.logs.append(result.stdout + result.stderr)
        return result

    def until(self, predicate, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.1)
        raise AssertionError('Timed out waiting for fixture state')

    def running(self, service):
        return self.docker('inspect', '--format', '{{.State.Running}}', self.container(service)) == 'true'

    def begin_slow(self, name):
        for prefix in ('started-', 'release-'):
            (self.control / (prefix + name)).unlink(missing_ok=True)
        def request():
            with urllib.request.urlopen(self.url + ('/slow' if name == 'frontend' else '/api/slow'), timeout=90) as response:
                return response.status, response.read()
        pending = self.pool.submit(request)
        self.until(lambda: (self.control / ('started-' + name)).exists())
        return pending

    def version(self):
        return json.loads(self.request('/manifest.webmanifest')[1])['version']

    def check_assets(self, versions):
        for version in versions:
            status, body = self.request('/assets/chunk-' + version + '.mjs')
            assert status == 200 and body == ('export default "' + version + '"').encode()
            with urllib.request.urlopen(self.url + '/assets/chunk-' + version + '.mjs') as response:
                assert response.headers.get_content_type() in ('application/javascript', 'text/javascript')
        try:
            self.request('/assets/missing.js')
        except urllib.error.HTTPError as error:
            assert error.code == 404
        else:
            raise AssertionError('Missing asset returned the SPA page')

    def long_deploy(self, service, image, ready):
        pending = self.begin_slow(service)
        old = service + '-blue'
        process = subprocess.Popen(self.arguments('deploy', service, image=image), cwd=ROOT,
                                   env=self.environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.until(ready)
            assert process.poll() is None, 'Deployment completed before the old request'
            assert self.running(old), 'Old container stopped while its request was active'
            (self.control / ('release-' + service)).touch()
            assert pending.result(timeout=10) == (200, b'blue')
            out, err = process.communicate(timeout=60)
            self.logs.append(out + err)
            assert process.returncode == 0, out + err
            assert not self.running(old)
        finally:
            (self.control / ('release-' + service)).touch()
            if process.poll() is None:
                process.kill()
                process.communicate()

    def run(self):
        try:
            print('Building isolated Linux fixtures', flush=True)
            self.build_fixtures()
            self.compose('up', '-d', '--no-build', '--no-deps', '--wait', 'backend-blue', 'frontend-blue', 'edge-nginx')
            binding = json.loads(self.docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', self.container('edge-nginx')))['80/tcp'][0]
            self.url = 'http://127.0.0.1:' + binding['HostPort']
            self.start_cli()
            self.long_deploy('frontend', self.frontends['v2'], lambda: self.version() == 'v2')
            self.check_assets(('v1', 'v2'))
            print('PASS: long frontend request completes before stop; old/new assets survive', flush=True)
            previous_image = self.docker('inspect', '--format', '{{.Image}}', self.container('frontend-blue'))
            self.invoke('switch', 'frontend', color='blue')
            assert self.version() == 'v1' and not self.running('frontend-green')
            assert self.docker('inspect', '--format', '{{.Image}}', self.container('frontend-blue')) == previous_image
            self.invoke('deploy', 'frontend', image=self.frontends['v3'])
            assert self.version() == 'v3'
            self.check_assets(('v1', 'v2', 'v3'))
            print('PASS: exact stopped image rollback and assets retained across three versions', flush=True)
            self.long_deploy('backend', self.image_id, lambda: json.loads(self.request('/api/color')[1])['color'] == 'green')
            self.invoke('switch', 'backend', color='blue')
            assert not self.running('backend-green')
            print('PASS: long backend request completes before stop; stopped backend rollback', flush=True)
            pending = self.begin_slow('frontend')
            failure = self.invoke('deploy', 'frontend', image=self.frontends['v2'], timeout=1, check=False)
            assert failure.returncode != 0 and 'previous route restored' in failure.stderr
            assert self.version() == 'v3' and self.running('frontend-green')
            candidate = self.container('frontend-blue')
            blocked = self.invoke('deploy', 'frontend', image=self.frontends['v1'], timeout=1, check=False)
            assert blocked.returncode != 0
            assert candidate == self.container('frontend-blue'), 'Pending drain must block candidate recreation'
            (self.control / 'release-frontend').touch()
            assert pending.result(timeout=10) == (200, b'blue')
            print('PASS: drain timeout restores route, keeps request alive and blocks unsafe redeploy', flush=True)
            failed_switch = self.invoke('deploy', 'frontend', image=self.frontends['bad'], check=False)
            assert failed_switch.returncode != 0 and 'previous route restored' in failed_switch.stderr
            assert self.version() == 'v3' and self.running('frontend-green')
            print('PASS: post-reload request verification failure restores the serving version', flush=True)
            broken = self.runtime / 'compose.frontend-broken.yaml'
            broken.write_text("services:\n  frontend-blue:\n    healthcheck:\n      test: [CMD, 'false']\n      interval: 1s\n      retries: 1\n      start_period: 0s\n")
            rejected = self.invoke('deploy', 'frontend', image=self.frontends['v1'], overlay=broken, check=False)
            assert rejected.returncode != 0 and self.version() == 'v3' and self.running('frontend-green')
            self.check_assets(('v1', 'v2', 'v3'))
            print('PASS: unhealthy frontend candidate leaves the active version and assets intact', flush=True)
        finally:
            for name in ('frontend', 'backend'):
                (self.control / ('release-' + name)).touch()
            self.pool.shutdown(wait=True)
            (self.runtime / 'tool-results.log').write_text('\n'.join(self.logs))
            logs = self.compose('logs', '--no-color', '--tail', '150', check=False)
            (self.runtime / 'containers.log').write_text(logs.stdout + logs.stderr)
            if self.cli:
                self.command(['docker', 'rm', '-f', self.cli], check=False)
            cleanup = self.compose('down', '--timeout', '10', check=False)
            assert cleanup.returncode == 0, 'Fixture cleanup failed: ' + self.project
        print('PASS: request drain/rollback/asset integration; artifacts: ' + str(self.runtime), flush=True)


if __name__ == '__main__':
    DrainCheck().run()
