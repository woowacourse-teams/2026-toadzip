"""Verify Docker -> Alloy -> Loki and the shipped request queries with disposable fixtures."""

import json
import re
import subprocess
import time
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]
PROJECT = "toadzip-monitoring-fixture-" + uuid.uuid4().hex[:10]
COMPOSE = ["docker", "compose", "--project-name", PROJECT, "--file", str(Path(__file__).with_name("compose.yaml"))]
LOKI = "http://127.0.0.1:58310"


def query_streams(expression):
    parameters = urlencode({"query": expression, "limit": 1000,
                            "start": str(time.time_ns() - 300_000_000_000), "end": str(time.time_ns())})
    with urlopen(f"{LOKI}/loki/api/v1/query_range?{parameters}", timeout=5) as response:
        return json.load(response)["data"]["result"]


def query(expression):
    streams = query_streams(expression)
    return [line for stream in streams for _, line in stream["values"]]


def request_query(panel_id, api=".*"):
    dashboard = json.loads((ROOT / "infra/grafana/dashboards/api-load-test.json").read_text())
    expression = next(panel["targets"][0]["expr"] for panel in dashboard["panels"] if panel["id"] == panel_id)
    for variable, value in {"$environment": "dev", "${method:regex}": ".*",
                            "${scope:raw}": "/api/.*", "${api:regex}": api, "$slow_ms": "1000"}.items():
        expression = expression.replace(variable, value)
    return expression


class LogCollectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = ROOT / ".local/verification/monitoring"
        path.mkdir(parents=True, exist_ok=True)
        config = (ROOT / "infra/alloy/config.alloy").read_text()
        # Keep the actual pipeline; exclude the user's other Docker workloads from this test sink.
        project_filter = f'''targets = discovery.docker.containers.targets
  rule {{
    source_labels = ["__meta_docker_container_label_com_docker_compose_project"]
    regex = "{PROJECT}"
    action = "keep"
  }}'''
        config = config.replace("targets = discovery.docker.containers.targets", project_filter)
        (path / "fixture.alloy").write_text(config)
        subprocess.run(COMPOSE + ["up", "--detach"], check=True)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                with urlopen(f"{LOKI}/ready", timeout=3) as response:
                    ready = response.status == 200
                lines = query('{environment="dev", service="backend"}')
                if ready and any("fixture-legacy" in line for line in lines):
                    break
            except (HTTPError, URLError, TimeoutError):
                pass
            time.sleep(1)
        else:
            raise AssertionError("Fixture logs did not arrive at Loki within 120 seconds")

        for service in ('frontend', 'frontend-blue', 'frontend-green'):
            address = subprocess.check_output(COMPOSE + ['port', service, '8080'], text=True).strip()
            try:
                urlopen('http://' + address + '/api/v1/fixture?token=private-token', timeout=5)
            except HTTPError as error:
                if error.code != 502:
                    raise
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if (len(query('{environment="dev", service="nginx"}')) == 3
                    and len(query('{environment="dev", service="backend"} |= "event=fixture.bluegreen"')) == 2):
                break
            time.sleep(1)
        else:
            raise AssertionError("Nginx access log did not arrive at Loki")

    def test_stack_trace_is_one_log_event(self):
        lines = query('{environment="dev", service="backend"} |= "fixture.failure"')
        self.assertEqual(len(lines), 1)
        self.assertIn("java.lang.IllegalStateException: fixture failure", lines[0])
        self.assertIn("at fixture.run(Fixture.java:1)", lines[0])

    def test_nginx_records_proxy_failure_without_query_data(self):
        lines = query('{environment="dev", service="nginx"}')
        self.assertEqual(len(lines), 3)
        for line in lines:
            self.assertNotIn("private-token", line)
            event = json.loads(line)
            self.assertEqual(event["status"], 502)
            self.assertEqual(event["uri"], "/api/v1/fixture")
            self.assertEqual(event["upstreamStatus"], "502")
            self.assertGreaterEqual(event["durationSeconds"], 0)

    def test_both_colors_keep_service_labels_stack_traces_and_container_identity(self):
        streams = query_streams('{environment="dev", service="backend"} |= "event=fixture.bluegreen"')
        self.assertEqual(len(streams), 2)
        self.assertEqual({stream['stream']['color'] for stream in streams}, {'blue', 'green'})
        self.assertEqual(len({stream['stream']['container'] for stream in streams}), 2)
        for stream in streams:
            self.assertEqual(len(stream['values']), 1)
            line = stream['values'][0][1]
            self.assertIn('java.lang.IllegalStateException: color fixture failure', line)
            self.assertIn('at fixture.color(Fixture.java:1)', line)
        frontend = query_streams('{environment="dev", service="nginx", color=~"blue|green"}')
        self.assertEqual({stream['stream']['color'] for stream in frontend}, {'blue', 'green'})

    def test_current_and_legacy_request_logs_are_readable(self):
        lines = query(request_query(28))
        self.assertEqual(len(lines), 2)
        self.assertTrue(any("fixture-request /api/v1/fixture/{id}" in line for line in lines))
        self.assertTrue(any("fixture-legacy /api/v1/fixture/43" in line for line in lines))

    def test_failed_and_slow_requests_are_selected(self):
        for panel_id in (24, 25):
            lines = query(request_query(panel_id))
            self.assertEqual(len(lines), 1)
            self.assertIn("503 1500 fixture-request", lines[0])

    def test_api_template_selects_the_matching_request(self):
        # Grafana's :regex formatter also escapes slashes. LogQL uses a raw string.
        escaped_api = re.escape("/api/v1/fixture/{id}").replace("/", r"\/")
        lines = query(request_query(28, escaped_api))
        self.assertEqual(len(lines), 1)
        self.assertIn("fixture-request", lines[0])


if __name__ == "__main__":
    try:
        unittest.main(verbosity=2)
    finally:
        subprocess.run(COMPOSE + ["logs", "--no-color", "--tail", "80", "alloy", "loki", "backend"], check=False)
        subprocess.run(COMPOSE + ["down", "--volumes", "--remove-orphans"], check=True)
