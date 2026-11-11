#!/usr/bin/env python3
"""Serve fixture HTML with the frontend Nginx config and check status codes, redirects and indexing headers.

Docker is required. The fixture replaces the Vite build, so the frontend does not need to be built.
"""

import http.client
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'nginx:1.29.5-alpine'
FIXTURE = {
    'index.html': 'app-shell',
    'prerendered/index.html': 'prerendered-landing',
    'prerendered/explore/index.html': 'prerendered-explore',
    'prerendered/privacy/index.html': 'prerendered-privacy',
    'robots.txt': 'User-agent: *',
    'sitemap.xml': '<urlset/>',
    'manifest.webmanifest': '{}',
    'assets/app-1234.js': 'export {}',
}


def docker(*arguments):
    return subprocess.run(['docker', *arguments], capture_output=True, text=True, check=True, timeout=120).stdout.strip()


class FrontendRoutesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        html = Path(cls.directory.name)
        for name, body in FIXTURE.items():
            (html / name).parent.mkdir(parents=True, exist_ok=True)
            (html / name).write_text(body)
        nginx = ROOT / 'frontend/nginx'
        cls.container = docker(
            'run', '-d', '--rm', '-p', '127.0.0.1::8080',
            '-v', f'{nginx / "http.conf"}:/etc/nginx/nginx.conf:ro',
            '-v', f'{nginx}:/etc/nginx/toadzip:ro',
            '-v', f'{nginx / "api-disabled.conf"}:/etc/nginx/toadzip/api-proxy.conf:ro',
            '-v', f'{html}:/usr/share/nginx/html:ro', IMAGE)
        cls.port = int(docker('port', cls.container, '8080').splitlines()[0].rsplit(':', 1)[1])
        for _ in range(30):
            try:
                if cls.get('/healthz')[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.3)
        raise RuntimeError(docker('logs', cls.container))

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '-f', cls.container], capture_output=True)
        cls.directory.cleanup()

    @classmethod
    def get(cls, path):
        connection = http.client.HTTPConnection('127.0.0.1', cls.port, timeout=5)
        connection.request('GET', path, headers={'Host': 'bokduckbang.com'})
        response = connection.getresponse()
        body = response.read().decode()
        headers = {name.lower(): value for name, value in response.getheaders()}
        connection.close()
        return response.status, headers, body

    def test_public_routes_serve_prerendered_html_without_noindex(self):
        for path, body in [('/', 'prerendered-landing'), ('/?utm_source=naver', 'prerendered-landing'),
                           ('/explore', 'prerendered-explore'), ('/explore?complexId=1', 'prerendered-explore'),
                           ('/privacy', 'prerendered-privacy')]:
            with self.subTest(path=path):
                status, headers, text = self.get(path)
                self.assertEqual((status, text), (200, body))
                self.assertNotIn('x-robots-tag', headers)
                self.assertEqual(headers['cache-control'], 'no-store')

    def test_app_routes_serve_shell_with_noindex(self):
        for path in ['/admin', '/admin/operations', '/login', '/mypage', '/mypage/notifications', '/notifications',
                     '/privacy/settings', '/privacy/PRIVACY_POLICY/privacy-2026-10-15-v1', '/feedback']:
            with self.subTest(path=path):
                status, headers, text = self.get(path)
                self.assertEqual((status, text), (200, 'app-shell'))
                self.assertEqual(headers['x-robots-tag'], 'noindex')

    def test_unknown_paths_return_404_with_app_shell(self):
        for path in ['/no-such-page', '/explore/foo', '/brand/missing.png', '/sitemap_index.xml', '/llms.txt']:
            with self.subTest(path=path):
                status, headers, text = self.get(path)
                self.assertEqual((status, text), (404, 'app-shell'))
                self.assertEqual(headers['cache-control'], 'no-store')

    def test_duplicate_urls_redirect_permanently_with_relative_location(self):
        for path, location in [('/explore/', '/explore'), ('/explore/?mapZoom=12', '/explore?mapZoom=12'),
                               ('/privacy/', '/privacy'), ('/admin/', '/admin'), ('/index.html', '/'),
                               ('/index.html?utm_source=x', '/?utm_source=x'),
                               ('/?complexId=1', '/explore?complexId=1'),
                               ('/?login=required&tab=announcements', '/explore?login=required&tab=announcements')]:
            with self.subTest(path=path):
                status, headers, _ = self.get(path)
                self.assertEqual((status, headers.get('location')), (308, location))

    def test_static_files_and_internal_paths(self):
        self.assertEqual(self.get('/robots.txt')[0], 200)
        self.assertEqual(self.get('/sitemap.xml')[0], 200)
        self.assertEqual(self.get('/manifest.webmanifest')[0], 200)
        self.assertEqual(self.get('/assets/app-1234.js')[0], 200)
        self.assertEqual(self.get('/assets/missing.js')[0], 404)
        self.assertEqual(self.get('/region-boundaries/edge-probe-1')[0], 404)
        self.assertEqual(self.get('/prerendered/explore/index.html')[0], 404)
        self.assertEqual(self.get('/__app-shell')[0], 404)


if __name__ == '__main__':
    unittest.main()
