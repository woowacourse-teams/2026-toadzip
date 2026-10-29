#!/usr/bin/env python3
"""Opt-in Linux release integration with a committed fixture checkout and actual Compose builds."""

import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('drain_check', ROOT / 'tests/deployment/test_bluegreen_drain.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseCheck(module.DrainCheck):
    def prepare_checkout(self):
        source = self.runtime / 'release-source'
        (source / 'scripts').mkdir(parents=True)
        for name in ('bluegreen.py', 'release-bluegreen.py'):
            shutil.copyfile(ROOT / 'scripts' / name, source / 'scripts' / name)
        for name in ('compose.yaml', 'compose.bluegreen.yaml'):
            shutil.copyfile(ROOT / name, source / name)
        (source / 'frontend/nginx').mkdir(parents=True)
        shutil.copyfile(ROOT / 'frontend/nginx/api-disabled.conf', source / 'frontend/nginx/api-disabled.conf')
        (source / 'backend').mkdir()
        (source / 'backend/Dockerfile').write_text('FROM ' + self.project + '-runner\n')
        html = source / 'frontend/html'
        (html / 'assets').mkdir(parents=True)
        (html / 'index.html').write_text('release-v2')
        (html / 'manifest.webmanifest').write_text('{"version":"release-v2"}')
        (html / 'assets/release-v2.js').write_text('console.log("release-v2")')
        shutil.copyfile(ROOT / 'frontend/nginx/logging.conf', source / 'frontend/nginx/logging.conf')
        (source / 'frontend/Dockerfile').write_text('FROM nginx:stable-alpine\nCOPY nginx/fixture.conf /etc/nginx/conf.d/default.conf\nCOPY nginx/logging.conf /etc/nginx/toadzip/logging.conf\nCOPY html/ /usr/share/nginx/html/\n')
        (source / 'frontend/nginx/fixture.conf').write_text('include /etc/nginx/toadzip/logging.conf; server { listen 8080; root /usr/share/nginx/html; location = /healthz { return 200 "ok"; } location /assets/ { try_files $uri =404; } location ^~ /region-boundaries/ { try_files $uri =404; } location / { try_files $uri /index.html; } }')
        (source / '.gitignore').write_text('.env\n.local/\n')
        for arguments in (['init', '-q'], ['config', 'user.name', 'Release fixture'],
                          ['config', 'user.email', 'fixture@example.invalid'], ['add', '.'],
                          ['commit', '-q', '-m', 'release fixture']):
            subprocess.run(['git', '-C', str(source), *arguments], check=True, capture_output=True)
        revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        return source, revision

    def run(self):
        try:
            print('Building isolated release fixtures', flush=True)
            self.build_fixtures()
            self.environment['SPRING_PROFILES_ACTIVE'] = 'dev'
            self.compose('up', '-d', '--no-build', '--no-deps', '--wait', 'backend-blue', 'frontend-blue', 'edge-nginx')
            binding = json.loads(self.docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', self.container('edge-nginx')))['80/tcp'][0]
            self.url = 'http://127.0.0.1:' + binding['HostPort']
            source, revision = self.prepare_checkout()
            self.start_cli()
            self.check_frontend_probe_log()
            self.command(['docker', 'exec', self.cli, 'apk', 'add', '--no-cache', 'git', 'util-linux'])
            self.command(['docker', 'exec', '-w', '/tmp', self.cli, 'git', 'config', '--global', '--add', 'safe.directory', str(source)])
            shell = '''set -euo pipefail
                cd "$1"
                exec 9>"$2/deploy.lock"
                flock --nonblock 9
                python3 -B scripts/release-bluegreen.py --revision "$3" --environment dev \
                  --runtime "$2" --url http://edge-nginx --project "$4" --compose-override "$5" --lock-fd 9
            '''
            result = self.command(['docker', 'exec', '-e', 'GITHUB_STEP_SUMMARY=' + str(self.runtime / 'summary.md'),
                                   self.cli, 'bash', '-c', shell, 'release-fixture', str(source), str(self.runtime),
                                   revision, self.project, str(self.fixture)], timeout=300)
            self.logs.append(result.stdout + result.stderr)
            reports = list((self.runtime / 'releases').glob('*.json'))
            assert len(reports) == 1
            report = json.loads(reports[0].read_text())
            assert report['revision'] == revision and report['result'] == 'success'
            assert report['completed'] == ['backend', 'frontend']
            assert report['routes'] == {'backend': 'backend-green', 'frontend': 'frontend-green'}
            assert self.version() == 'release-v2'
            for service in ('backend', 'frontend'):
                old = self.container(service + '-blue')
                new = self.container(service + '-green')
                assert self.docker('inspect', '--format', '{{.State.Status}}', old) == 'exited'
                assert self.docker('inspect', '--format', '{{.Image}}', new) == report['images'][service]
                labels = json.loads(self.docker('image', 'inspect', '--format', '{{json .Config.Labels}}', report['images'][service]))
                assert labels['org.opencontainers.image.revision'] == revision
            assert self.request('/assets/chunk-v1.mjs')[1] == b'export default "v1"'
            assert revision in (self.runtime / 'summary.md').read_text()
            print('PASS: both SHA-labelled images built before backend/frontend deployment under inherited Linux lock', flush=True)
            print('PASS: old containers stopped; recorded image IDs/routes and Actions summary match actual state', flush=True)
        finally:
            self.pool.shutdown(wait=True)
            (self.runtime / 'release-tool.log').write_text('\n'.join(self.logs))
            logs = self.compose('logs', '--no-color', '--tail', '100', check=False)
            (self.runtime / 'containers.log').write_text(logs.stdout + logs.stderr)
            if self.cli:
                self.command(['docker', 'rm', '-f', self.cli], check=False)
            cleanup = self.compose('down', '--timeout', '10', check=False)
            assert cleanup.returncode == 0, 'Fixture cleanup failed: ' + self.project
        print('PASS: Linux release integration; artifacts: ' + str(self.runtime), flush=True)


if __name__ == '__main__':
    ReleaseCheck().run()
