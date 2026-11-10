#!/usr/bin/env python3
"""Run the real Caddyfile with a local CA and check indexing headers and redirects per host.

Docker is required. Certificates come from Caddy's internal CA, so no ACME or external DNS is used.
"""

from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
CADDY_IMAGE = 'caddy:2'
EDGE_IMAGE = 'nginx:1.29.5-alpine'
TEST_WRAPPER = '''{
	local_certs
	skip_install_trust
}
import /etc/caddy/Caddyfile
'''


def docker(*arguments):
    return subprocess.run(['docker', *arguments], capture_output=True, text=True, check=True, timeout=120).stdout.strip()


class CaddyRobotsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suffix = uuid.uuid4().hex[:8]
        cls.network = 'toadzip-caddy-robots-' + cls.suffix
        cls.directory = tempfile.TemporaryDirectory()
        cls.wrapper = Path(cls.directory.name) / 'test.Caddyfile'
        cls.wrapper.write_text(TEST_WRAPPER)
        docker('network', 'create', cls.network)
        docker('run', '-d', '--rm', '--name', 'edge-nginx-' + cls.suffix, '--network', cls.network,
               '--network-alias', 'edge-nginx', EDGE_IMAGE, 'sh', '-c',
               'printf "server { listen 81; location / { return 200 ok; } }" > /etc/nginx/conf.d/default.conf'
               ' && exec nginx -g "daemon off;"')

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', 'edge-nginx-' + cls.suffix], capture_output=True)
        subprocess.run(['docker', 'network', 'rm', cls.network], capture_output=True)
        cls.directory.cleanup()

    def start_caddy(self, domain, redirect_domain=''):
        container = docker('run', '-d', '--rm', '--network', self.network, '-p', '127.0.0.1::443',
                           '-e', 'TOADZIP_CADDY_DOMAIN=' + domain, '-e', 'TOADZIP_CADDY_REDIRECT_DOMAIN=' + redirect_domain,
                           '-v', str(ROOT / 'infra/caddy/Caddyfile') + ':/etc/caddy/Caddyfile:ro',
                           '-v', str(self.wrapper) + ':/etc/caddy/test.Caddyfile:ro',
                           CADDY_IMAGE, 'caddy', 'run', '--config', '/etc/caddy/test.Caddyfile', '--adapter', 'caddyfile')
        self.addCleanup(subprocess.run, ['docker', 'rm', '-f', container], capture_output=True)
        port = docker('port', container, '443').splitlines()[0].rsplit(':', 1)[1]
        return port

    def head(self, port, host, path):
        command = ['curl', '-sk', '-o', '/dev/null', '-D', '-', '--max-time', '5',
                   '--resolve', f'{host}:{port}:127.0.0.1', f'https://{host}:{port}{path}']
        for _ in range(30):
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode == 0 and result.stdout:
                lines = result.stdout.replace('\r', '').splitlines()
                headers = {}
                for line in lines[1:]:
                    name, separator, value = line.partition(':')
                    if separator:
                        headers[name.strip().lower()] = value.strip()
                return int(lines[0].split()[1]), headers
            time.sleep(0.5)
        self.fail(f'Caddy did not answer https://{host}{path}')

    def test_development_host_marks_every_response_noindex(self):
        port = self.start_caddy('dev.bokduckbang.com')
        for path in ('/', '/explore', '/api/v1/announcements?size=1', '/robots.txt'):
            with self.subTest(path=path):
                status, headers = self.head(port, 'dev.bokduckbang.com', path)
                self.assertEqual(status, 200)
                self.assertEqual(headers.get('x-robots-tag'), 'noindex')

    def test_production_host_stays_indexable_and_www_redirects(self):
        port = self.start_caddy('bokduckbang.com', 'www.bokduckbang.com')
        for path in ('/', '/explore', '/api/v1/announcements?size=1'):
            with self.subTest(path=path):
                status, headers = self.head(port, 'bokduckbang.com', path)
                self.assertEqual(status, 200)
                self.assertNotIn('x-robots-tag', headers)
        status, headers = self.head(port, 'www.bokduckbang.com', '/explore?complexId=1')
        self.assertEqual(status, 308)
        self.assertEqual(headers['location'], 'https://bokduckbang.com/explore?complexId=1')


if __name__ == '__main__':
    unittest.main()
