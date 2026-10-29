#!/usr/bin/env python3
"""Exercise frontend/backend deployment failures without Docker or real .env files."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("bluegreen", ROOT / "scripts/bluegreen.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
CONFIG = """upstream frontend_active {
    server frontend-blue:8080;
}
upstream backend_active {
    server backend-blue:8080;
}
"""


class Harness(module.Deployment):
    def __init__(self, options):
        super().__init__(options)
        self.served = self.service + "-blue"
        self.probe = {}
        self.requests = []
        self.commands = []
        self.recreations = []
        self.failure = None
        self.failed_once = False
        self.stopped = set()
        self.started = []

    def compose(self, *args, environment=None, timeout=30):
        self.commands.append(args)
        if args[0] == "ps":
            return args[-1]
        if args[0] == "up":
            self.recreations.append((args[-1], environment))
            if self.failure == "candidate":
                raise module.DeploymentError("candidate failed")
            return ""
        raise AssertionError(args)

    def request(self, path):
        self.requests.append(path)
        if path.startswith("/assets/edge-archive-probe-"):
            if self.failure == "archive-route":
                return 404, b""
            return 200, (self.assets / path.split("/")[-1]).read_bytes()
        if path.startswith(("/region-boundaries/edge-probe-", "/api/edge-probe-")):
            if not (self.failure == "probe" and self.served != self.service + "-blue"):
                self.probe[self.served] = path
            return 404, b""
        if path == "/api/health":
            return 200, b'{"status":"UP"}'
        if path == "/api/v1/announcements?size=1":
            if self.failure == "query" and self.served.endswith("-green"):
                return 503, b'{}'
            return 200, b'{"data":{}}'
        return 200, b"{}"

    def docker(self, *args, **kwargs):
        self.commands.append(args)
        if args[:3] == ("inspect", "--format", "{{json .Mounts}}"):
            directory = str(self.directory)
            if self.failure == "mount":
                directory += "-different"
            return json.dumps([{"Destination": "/etc/nginx/conf.d", "Source": directory, "RW": False},
                               {"Destination": "/var/cache/toadzip/assets", "Source": str(self.assets), "RW": False}])
        if args[:3] == ("inspect", "--format", "{{json .State}}"):
            running = args[-1] not in self.stopped
            return json.dumps({"Running": running, "Status": "running" if running else "exited",
                               "ExitCode": 0, "Health": {"Status": "healthy"}})
        if args[0] == "stop":
            if self.failure == "stop":
                raise module.DeploymentError("stop failed")
            self.stopped.add(args[-1])
            return args[-1]
        if args[0] == "start":
            self.started.append(args[-1])
            self.stopped.discard(args[-1])
            return args[-1]
        if args[0] == "cp":
            service = args[1].split(":")[0]
            name = "shared.js" if self.failure == "asset-collision" else service + ".js"
            (Path(args[-1]) / name).write_text(service)
            return ""
        if args[:3] == ("inspect", "--format", "{{.State.Health.Status}}"):
            if self.failure == "unhealthy" and args[-1] == self.service + "-green":
                return "unhealthy"
            return "healthy"
        if args[:2] == ("image", "inspect"):
            return "sha256:" + "a" * 64
        if args[0] == "logs":
            return self.probe.get(args[-1], "")
        if args[0] == "exec":
            if args[2] == "cat":
                return self.config.read_text()
            if args[2:4] == ("nginx", "-t"):
                if self.failure == "syntax" and self.service + "-green" in self.config.read_text() and not self.failed_once:
                    self.failed_once = True
                    raise module.DeploymentError("nginx syntax failed")
                return ""
            if args[2:5] == ("nginx", "-s", "reload"):
                if self.failure == "reload" and not self.failed_once:
                    self.failed_once = True
                    raise module.DeploymentError("reload failed")
                self.served = module.active_service(self.config.read_text(), self.service)
                return ""
            if args[2] == "wget":
                return ""
            if args[2] == "curl":
                return '{"status":"DOWN"}' if self.failure == "management" else '{"status":"UP"}'
        raise AssertionError(args)

    def workers(self):
        if self.served == self.service + "-blue":
            return {("10", "100"): "active"}
        workers = {("20", "200"): "active"}
        if self.failure == "drain":
            workers[("10", "100")] = "draining"
        return workers


class DeploymentTests(unittest.TestCase):
    service = "frontend"
    def setUp(self):
        (ROOT / ".local").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="bluegreen-test-", dir=ROOT / ".local")
        runtime = Path(self.temporary.name)
        (runtime / "edge").mkdir()
        (runtime / "assets").mkdir()
        (runtime / "edge/default.conf").write_text(CONFIG)
        self.options = argparse.Namespace(action="deploy", project="fixture-project", runtime=runtime,
            url="http://127.0.0.1:18080", compose_override=[], image="fixture:release", color=None, service=self.service, drain_timeout=1)
        self.runner = Harness(self.options)
        self.patch_docker = patch.object(module, "docker", side_effect=self.runner.docker)
        self.patch_docker.start()
        self.patch_sleep = patch.object(module.time, "sleep")
        self.patch_sleep.start()

    def tearDown(self):
        self.patch_sleep.stop()
        self.patch_docker.stop()
        self.temporary.cleanup()

    def test_deploy_recreates_only_inactive_service_with_immutable_image_then_switches(self):
        self.runner.run()
        self.assertEqual(self.runner.served, self.service + "-green")
        service, environment = self.runner.recreations[0]
        self.assertEqual(service, self.service + "-green")
        self.assertEqual(environment[self.service.upper() + "_GREEN_IMAGE"], "sha256:" + "a" * 64)
        up = next(args for args in self.runner.commands if args[0] == "up")
        for flag in ["--no-deps", "--no-build", "--wait", "--force-recreate"]:
            self.assertIn(flag, up)
        self.assertEqual(up[up.index("--pull") + 1], "never")
        other = "backend" if self.service == "frontend" else "frontend"
        self.assertIn("server " + other + "-blue:8080;", self.runner.config.read_text())
        self.assertTrue(list(self.runner.runtime.glob("before-switch-*.conf.bak")))

    def test_route_probe_survives_frontend_spa_uri_rewrite(self):
        self.runner.verify_route(self.service + "-blue")
        prefix = "/api/edge-probe-" if self.service == "backend" else "/region-boundaries/edge-probe-"
        self.assertTrue(self.runner.requests[-1].startswith(prefix))
        self.assertEqual(self.runner.probe[self.service + "-blue"], self.runner.requests[-1])

    def test_route_probe_rejects_response_without_target_log(self):
        self.runner.probe.clear()
        self.runner.request = lambda path: (404, b"")
        with self.assertRaisesRegex(module.DeploymentError, "Could not verify requests"):
            self.runner.verify_route(self.service + "-blue")

    def test_route_probe_rejects_spa_success_even_with_target_log(self):
        def request(path):
            self.runner.probe[self.service + "-blue"] = path
            return 200, b"SPA fallback"
        self.runner.request = request
        with self.assertRaisesRegex(module.DeploymentError, "Could not verify requests"):
            self.runner.verify_route(self.service + "-blue")

    def test_previous_container_stops_only_after_worker_drain(self):
        events = []
        self.runner.wait_workers = lambda workers: events.append("drained")
        original = self.runner.docker
        def docker(*args, **kwargs):
            if args[0] == "stop":
                events.append("stopped")
            return original(*args, **kwargs)
        self.patch_docker.stop()
        self.patch_docker = patch.object(module, "docker", side_effect=docker)
        self.patch_docker.start()
        self.runner.run()
        self.assertEqual(events, ["drained", "stopped"])

    def test_green_to_blue_and_legacy_to_blue_choose_correct_candidate(self):
        for active in [self.service + "-green", self.service]:
            with self.subTest(active=active):
                self.runner.config.write_text(CONFIG.replace(self.service + "-blue", active))
                self.runner.served = active
                self.runner.run()
                self.assertEqual(self.runner.recreations[-1][0], self.service + "-blue")
                self.assertEqual(self.runner.served, self.service + "-blue")

    def test_candidate_failure_does_not_change_active_config(self):
        self.runner.failure = "candidate"
        with self.assertRaises(module.DeploymentError):
            self.runner.run()
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertFalse(list(self.runner.runtime.glob("before-switch-*.conf.bak")))

    def test_syntax_reload_and_request_verification_failures_restore_previous_route(self):
        for failure in ["syntax", "reload", "probe"]:
            with self.subTest(failure=failure):
                self.runner.failure = failure
                self.runner.failed_once = False
                self.runner.config.write_text(CONFIG)
                self.runner.served = self.service + "-blue"
                with self.assertRaisesRegex(module.DeploymentError, "previous route restored"):
                    self.runner.run()
                self.assertEqual(self.runner.config.read_text(), CONFIG)
                self.assertEqual(self.runner.served, self.service + "-blue")

    def test_unhealthy_target_is_not_switched(self):
        self.options.action = "switch"
        self.options.color = "green"
        self.runner.failure = "unhealthy"
        with self.assertRaisesRegex(module.DeploymentError, "not healthy"):
            self.runner.run()
        self.assertEqual(self.runner.config.read_text(), CONFIG)

    def test_explicit_switch_does_not_recreate_containers(self):
        self.options.action = "switch"
        self.options.color = "green"
        self.runner.run()
        self.assertEqual(self.runner.served, self.service + "-green")
        self.assertEqual(self.runner.recreations, [])

    def test_wrong_mount_is_rejected_before_recreation(self):
        self.runner.failure = "mount"
        with self.assertRaisesRegex(module.DeploymentError, "different configuration"):
            self.runner.run()
        self.assertEqual(self.runner.recreations, [])

    def test_existing_deployment_lock_blocks_another_invocation(self):
        with self.runner.lock():
            with self.assertRaisesRegex(module.DeploymentError, "Another deployment"):
                self.runner.run()
        self.assertEqual(self.runner.commands, [])

    def test_lock_stays_held_during_candidate_preparation(self):
        original = self.runner.compose
        def compose(*args, **kwargs):
            if args[0] == "up":
                with self.assertRaisesRegex(module.DeploymentError, "Another deployment"):
                    with self.runner.lock():
                        self.fail("lock released before switching")
            return original(*args, **kwargs)
        self.runner.compose = compose
        self.runner.run()

    def test_drain_timeout_restores_route_without_stopping_previous(self):
        self.options.drain_timeout = 0
        self.runner.failure = "drain"
        with self.assertRaisesRegex(module.DeploymentError, "previous route restored"):
            self.runner.run()
        self.assertEqual(self.runner.served, self.service + "-blue")
        self.assertEqual(self.runner.stopped, set())

    def test_rollback_restarts_exact_stopped_container_without_recreating_it(self):
        self.options.action = "switch"
        self.options.color = "green"
        self.runner.stopped.add(self.service + "-green")
        self.runner.run()
        self.assertEqual(self.runner.started, [self.service + "-green"])
        self.assertEqual(self.runner.recreations, [])
        self.assertEqual(self.runner.stopped, {self.service + "-blue"})

    def test_cleanup_failure_keeps_new_route_active(self):
        self.runner.failure = "stop"
        with self.assertRaisesRegex(module.DeploymentError, "New route is active"):
            self.runner.run()
        self.assertEqual(self.runner.served, self.service + "-green")

    def test_ambiguous_upstream_is_rejected(self):
        self.runner.config.write_text(CONFIG + CONFIG)
        with self.assertRaisesRegex(module.DeploymentError, "exactly one"):
            self.runner.run()
        self.assertEqual(self.runner.recreations, [])


class FrontendAssetTests(unittest.TestCase):
    setUp = DeploymentTests.setUp
    tearDown = DeploymentTests.tearDown
    service = "frontend"
    def test_missing_archive_route_blocks_candidate_before_stop_or_recreation(self):
        self.runner.failure = "archive-route"
        with self.assertRaisesRegex(module.DeploymentError, "asset archive is unavailable"):
            self.runner.run()
        self.assertEqual(self.runner.recreations, [])
        self.assertEqual(self.runner.stopped, set())
        self.assertEqual(list(self.runner.assets.iterdir()), [])

    def test_changed_content_at_existing_asset_url_rejects_candidate(self):
        self.runner.failure = "asset-collision"
        with self.assertRaisesRegex(module.DeploymentError, "URL reused"):
            self.runner.run()
        self.assertEqual(self.runner.recreations, [])
        self.assertEqual(self.runner.config.read_text(), CONFIG)

    def test_old_and_new_assets_are_kept_after_previous_container_stops(self):
        self.runner.run()
        self.assertEqual((self.runner.assets / "frontend-blue.js").read_text(), "frontend-blue")
        self.assertEqual((self.runner.assets / "frontend-green.js").read_text(), "frontend-green")
        self.assertIn("frontend-blue", self.runner.stopped)


class BackendDeploymentTests(DeploymentTests):
    service = "backend"

    def test_management_health_failure_prevents_switch(self):
        self.runner.failure = "management"
        with self.assertRaisesRegex(module.DeploymentError, "management health"):
            self.runner.run()
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertEqual(self.runner.served, "backend-blue")

    def test_database_query_failure_after_switch_restores_route(self):
        self.runner.failure = "query"
        with self.assertRaisesRegex(module.DeploymentError, "previous route restored"):
            self.runner.run()
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertEqual(self.runner.served, "backend-blue")

    def test_frontend_and_backend_share_lock_even_with_runtime_alias(self):
        alias = self.runner.runtime / "alias"
        alias.mkdir()
        (alias / "edge").symlink_to(self.runner.directory, target_is_directory=True)
        options = argparse.Namespace(**{**vars(self.options), "service": "frontend", "runtime": alias})
        frontend = Harness(options)
        with frontend.lock():
            with self.assertRaisesRegex(module.DeploymentError, "Another deployment"):
                self.runner.run()
        self.assertEqual(self.runner.recreations, [])


if __name__ == "__main__":
    unittest.main()
