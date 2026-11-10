#!/usr/bin/env python3
"""Serve fixture HTML with the frontend Nginx config and check status codes, redirects and indexing headers.

Docker is required. The fixture replaces the Vite build, so the frontend does not need to be built.
A fake backend answers as `edge-nginx` on a private network so the SEO page proxy and SSI run for real.
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
    'sitemap.xml': '<sitemapindex/>',
    'sitemaps/static.xml': '<urlset>static</urlset>',
    'prerendered/shared-head.html': '<meta charset="UTF-8" /><script type="module" src="/assets/app-1234.js"></script>',
    'manifest.webmanifest': '{}',
    'assets/app-1234.js': 'export {}',
}


SEO_PAGE = '<!doctype html><html><head><!--# include virtual="/__seo-head" --><title>%s</title></head>' \
    '<body><div id="root"><h1>%s</h1></div></body></html>'

# Fake backend reached as edge-nginx, the same way blue/green frontends reach the active backend.
FAKE_BACKEND = '''
server {
    listen 80;
    absolute_redirect off;
    default_type text/html;
    location = /api/v1/seo/pages/complexes/17 { return 200 '%s'; }
    location = /api/v1/seo/pages/regions/11140/happy-housing { return 200 '%s'; }
    location = /api/v1/seo/pages/complexes/404 { return 404 '%s'; }
    location = /api/v1/seo/pages/complexes/5 { return 308 /complexes/17; }
    location = /api/v1/seo/pages/complexes/500 { return 500 'backend-error'; }
    location = /api/v1/seo/sitemaps/complexes.xml { default_type application/xml; return 200 '<urlset>complexes</urlset>'; }
    location / { return 404 'unexpected-backend-path'; }
}
''' % (SEO_PAGE % ('complex-title', 'complex-body'), SEO_PAGE % ('region-title', 'region-body'),
       SEO_PAGE % ('missing-title', 'missing-body'))


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
        (Path(cls.directory.name) / 'backend.conf').write_text(FAKE_BACKEND)
        nginx = ROOT / 'frontend/nginx'
        cls.network = docker('network', 'create', 'toadzip-frontend-routes-' + str(time.time_ns()))
        cls.backend = docker(
            'run', '-d', '--rm', '--network', cls.network, '--network-alias', 'edge-nginx',
            '-v', f'{Path(cls.directory.name) / "backend.conf"}:/etc/nginx/conf.d/default.conf:ro', IMAGE)
        cls.container = docker(
            'run', '-d', '--rm', '--network', cls.network, '-p', '127.0.0.1::8080',
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
        subprocess.run(['docker', 'rm', '-f', cls.container, cls.backend], capture_output=True)
        subprocess.run(['docker', 'network', 'rm', cls.network], capture_output=True)
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
                           ('/explore', 'prerendered-explore'), ('/explore?mapZoom=12', 'prerendered-explore'),
                           ('/explore?complexId=abc', 'prerendered-explore'),
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
        for path in ['/no-such-page', '/explore/foo', '/brand/missing.png', '/sitemap_index.xml', '/llms.txt',
                     '/complexes', '/complexes/abc', '/complexes/0', '/complexes/01', '/complexes/17/extra',
                     '/announcements/-1', '/regions/1', '/regions/1114', '/regions/11140/HAPPY_HOUSING',
                     '/sitemaps/unknown.xml']:
            with self.subTest(path=path):
                status, headers, text = self.get(path)
                self.assertEqual((status, text), (404, 'app-shell'))
                self.assertEqual(headers['cache-control'], 'no-store')

    def test_duplicate_urls_redirect_permanently_with_relative_location(self):
        for path, location in [('/explore/', '/explore'), ('/explore/?mapZoom=12', '/explore?mapZoom=12'),
                               ('/privacy/', '/privacy'), ('/admin/', '/admin'), ('/index.html', '/'),
                               ('/index.html?utm_source=x', '/?utm_source=x'),
                               ('/?complexId=1', '/explore?complexId=1'),
                               ('/explore?complexId=1&mapZoom=12', '/complexes/1?complexId=1&mapZoom=12'),
                               ('/explore?announcementId=9', '/announcements/9?announcementId=9'),
                               ('/complexes/17/', '/complexes/17'),
                               ('/?login=required&tab=announcements', '/explore?login=required&tab=announcements')]:
            with self.subTest(path=path):
                status, headers, _ = self.get(path)
                self.assertEqual((status, headers.get('location')), (308, location))

    def test_detail_and_region_pages_get_backend_html_with_this_builds_shared_head(self):
        for path, title, body in [('/complexes/17', 'complex-title', 'complex-body'),
                                  ('/complexes/17?mapZoom=12', 'complex-title', 'complex-body'),
                                  ('/regions/11140/happy-housing', 'region-title', 'region-body')]:
            with self.subTest(path=path):
                status, headers, text = self.get(path)
                self.assertEqual(status, 200)
                self.assertTrue(headers['content-type'].startswith('text/html'))
                self.assertIn('<head><meta charset="UTF-8" /><script type="module" src="/assets/app-1234.js">', text)
                self.assertIn(f'<title>{title}</title>', text)
                self.assertIn(f'<h1>{body}</h1>', text)
                self.assertNotIn('<!--#', text)
                self.assertNotIn('x-robots-tag', headers)

    def test_backend_not_found_and_redirect_pass_through_and_failures_fall_back_to_app(self):
        status, _, text = self.get('/complexes/404')
        self.assertEqual(status, 404)
        self.assertIn('<h1>missing-body</h1>', text)
        self.assertNotIn('<!--#', text)
        status, headers, _ = self.get('/complexes/5')
        self.assertEqual((status, headers.get('location')), (308, '/complexes/17'))
        self.assertEqual(self.get('/complexes/500')[:3:2], (200, 'app-shell'))

    def test_sitemaps(self):
        self.assertEqual(self.get('/sitemap.xml')[2], '<sitemapindex/>')
        self.assertEqual(self.get('/sitemaps/static.xml')[2], '<urlset>static</urlset>')
        status, headers, text = self.get('/sitemaps/complexes.xml')
        self.assertEqual((status, text), (200, '<urlset>complexes</urlset>'))
        self.assertIn('xml', headers['content-type'])

    def test_static_files_and_internal_paths(self):
        self.assertEqual(self.get('/robots.txt')[0], 200)
        self.assertEqual(self.get('/sitemap.xml')[0], 200)
        self.assertEqual(self.get('/manifest.webmanifest')[0], 200)
        self.assertEqual(self.get('/assets/app-1234.js')[0], 200)
        self.assertEqual(self.get('/assets/missing.js')[0], 404)
        self.assertEqual(self.get('/region-boundaries/edge-probe-1')[0], 404)
        self.assertEqual(self.get('/prerendered/explore/index.html')[0], 404)
        self.assertEqual(self.get('/__app-shell')[0], 404)
        self.assertEqual(self.get('/__seo-head')[0], 404)
        self.assertEqual(self.get('/prerendered/shared-head.html')[0], 404)


if __name__ == '__main__':
    unittest.main()
