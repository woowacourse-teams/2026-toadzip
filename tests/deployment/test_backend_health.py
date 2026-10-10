#!/usr/bin/env python3
"""Validate release health gating without reading .env or changing running services."""

import http.server
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENT = {
    key: os.environ[key]
    for key in ("PATH", "HOME", "TMPDIR", "DOCKER_CONFIG", "DOCKER_HOST", "DOCKER_CONTEXT")
    if key in os.environ
}
FIXTURES = {
    "PRIMARY_DB_HOST": "fixture-primary", "PRIMARY_DB_PASSWORD": "fixture-only",
    "SHARED_DB_HOST": "fixture-shared", "SHARED_DB_PASSWORD": "fixture-only",
    "LOKI_PUSH_URL": "http://fixture-loki", "GRAFANA_ADMIN_PASSWORD": "fixture-only",
    "TOADZIP_TLS_DOMAIN": "example.invalid", "TOADZIP_TLS_GID": "42424",
}


def configuration(profile, overlays=()):
    arguments = ["docker", "compose", "--env-file", "/dev/null", "-f", "compose.yaml"]
    for overlay in overlays:
        arguments.extend(["-f", overlay])
    result = subprocess.run(
        [*arguments, "config", "--format", "json"], cwd=ROOT,
        env={**ENVIRONMENT, **FIXTURES, "SPRING_PROFILES_ACTIVE": profile},
        text=True, capture_output=True, check=True, timeout=30,
    )
    return json.loads(result.stdout)["services"]


class HealthHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        status = int(self.path.strip("/"))
        self.send_response(status)
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}' if status == 200 else b'{"status":"DOWN"}')

    def log_message(self, _format, *arguments):
        pass


class BackendHealthTests(unittest.TestCase):
    def test_health_dependency_survives_profiles_and_overlays(self):
        for profile, overlays in (
            ("dev", ()), ("prod", ()), ("local", ("compose.local.yaml",)),
            ("dev", ("compose.https.yaml",)), ("prod", ("compose.https.yaml",)),
            ("prod", ("compose.monitoring.yaml",)),
            ("prod", ("compose.https.yaml", "compose.monitoring.yaml")),
            ("local", ("compose.local.yaml", "compose.monitoring.yaml")),
        ):
            with self.subTest(profile=profile, overlays=overlays):
                services = configuration(profile, overlays)
                self.assertEqual(services["frontend"]["depends_on"]["backend"]["condition"], "service_healthy")
                health = services["backend"]["healthcheck"]
                self.assertEqual(health["test"][0], "CMD")
                address = urlsplit(health["test"][-1])
                self.assertEqual((address.hostname, address.port, address.path),
                                 ("127.0.0.1", 8081, "/actuator/health"))
                self.assertEqual(services["backend"]["environment"]["SPRING_PROFILES_ACTIVE"], profile)
                if "compose.local.yaml" in overlays:
                    for database in ("db", "db-shared"):
                        self.assertEqual(services["backend"]["depends_on"][database]["condition"], "service_healthy")
                if "compose.https.yaml" in overlays:
                    self.assertIn(("443", 8443), {(str(port["published"]), port["target"]) for port in services["frontend"]["ports"]})
                if "compose.monitoring.yaml" in overlays:
                    self.assertTrue({"prometheus", "loki", "grafana", "caddy"}.issubset(services))

    def test_actual_probe_fails_for_down_missing_and_unreachable_backend(self):
        probe = configuration("prod")["backend"]["healthcheck"]["test"][1:]
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        address = f"http://127.0.0.1:{server.server_port}"
        try:
            for status in (200, 503, 404):
                with self.subTest(status=status):
                    result = subprocess.run([*probe[:-1], f"{address}/{status}"],
                                            env=ENVIRONMENT, capture_output=True, timeout=6)
                    self.assertEqual(result.returncode == 0, status == 200)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
        result = subprocess.run([*probe[:-1], f"{address}/200"],
                                env=ENVIRONMENT, capture_output=True, timeout=6)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
