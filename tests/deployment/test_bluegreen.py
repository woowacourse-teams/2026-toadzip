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
        self.commands = []
        self.recreations = []
        self.failure = None
        self.failed_once = False

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
        if path.startswith(("/assets/edge-probe-", "/api/edge-probe-")):
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
            return json.dumps([{"Destination": "/etc/nginx/conf.d", "Source": directory, "RW": False}])
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


class DeploymentTests(unittest.TestCase):
    service = "frontend"
    def setUp(self):
        (ROOT / ".local").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="bluegreen-test-", dir=ROOT / ".local")
        runtime = Path(self.temporary.name)
        (runtime / "edge").mkdir()
        (runtime / "edge/default.conf").write_text(CONFIG)
        self.options = argparse.Namespace(action="deploy", project="fixture-project", runtime=runtime,
            url="http://127.0.0.1:18080", compose_override=[], image="fixture:release", color=None, service=self.service)
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

    def test_ambiguous_upstream_is_rejected(self):
        self.runner.config.write_text(CONFIG + CONFIG)
        with self.assertRaisesRegex(module.DeploymentError, "exactly one"):
            self.runner.run()
        self.assertEqual(self.runner.recreations, [])


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
