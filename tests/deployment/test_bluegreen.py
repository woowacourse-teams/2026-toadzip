#!/usr/bin/env python3
"""Exercise frontend/backend deployment failures without Docker or real .env files."""
import argparse
import importlib.util
import json
import subprocess
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
upstream backend_metrics_active {
    server backend-blue:8081;
}
"""

CANDIDATE = "sha256:" + "a" * 64


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
        # Containers whose announcement query fails, e.g. an old backend after the shared schema changed.
        self.broken = set()
        self.breaks_on_start = set()
        self.images = {}
        self.labels = {}

    def compose(self, *args, environment=None, timeout=30):
        self.commands.append(args)
        if args[0] == "ps":
            return args[-1]
        if args[0] == "config":
            return '{"services":{"edge-nginx":{}}}'
        if args[0] == "up":
            self.recreations.append((args[-1], environment))
            if self.failure == "candidate":
                raise module.DeploymentError("candidate failed")
            # The candidate runs Flyway against the database both colors share.
            self.broken |= self.breaks_on_start
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
            if self.failure == "health":
                return 503, b'{"status":"DOWN"}'
            return 200, b'{"status":"UP"}'
        if path == "/api/v1/announcements?size=1":
            if (self.failure == "query" and self.served.endswith("-green")) or self.served in self.broken:
                return 500, b'{}'
            return 200, b'{"data":{}}'
        return 200, b"{}"

    def docker(self, *args, **kwargs):
        self.commands.append(args)
        if args[:3] == ("inspect", "--format", "{{json .NetworkSettings.Networks}}"):
            address = "10.1.0.2" if args[-1].endswith("-blue") else "10.1.0.3"
            return json.dumps({"fixture": {"IPAddress": address}})
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
        if args[:3] == ("inspect", "--format", "{{.Image}}"):
            return self.images.get(args[-1], CANDIDATE)
        if module.REVISION_LABEL in args:
            return self.labels.get(args[-1], "")
        if args[:2] == ("image", "inspect"):
            return CANDIDATE
        if args[0] == "logs":
            return self.probe.get(args[-1], "")
        if args[0] == "exec":
            if args[2:4] == ("sh", "-c"):
                active = module.metrics_service(self.config.read_text())
                address = "10.1.0.2" if active.endswith("-blue") else "10.1.0.3"
                if self.failure == "metrics-route" and active.endswith("-green"):
                    address = "10.1.0.2"
                body = "# TYPE fixture_requests_total counter\nfixture_requests_total 1\n"
                if self.failure == "metrics-body" and active.endswith("-green"):
                    body = "not a metrics response"
                return "  X-Toadzip-Metrics-Upstream: " + address + ":8081\n  Content-Type: text/plain; version=0.0.4\n" + body
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
            if args[2] == "curl" and args[-1].endswith("/api/v1/announcements?size=1"):
                if args[1] in self.broken:
                    raise module.DeploymentError("Docker command failed: exec " + args[1] + " curl")
                return '{"data":{}}'
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

    def test_route_probe_retries_connection_failure_but_still_requires_target_log(self):
        request = self.runner.request
        attempts = []
        def transient(path):
            attempts.append(path)
            if len(attempts) == 1:
                raise module.DeploymentError("old worker connection timed out")
            return request(path)
        self.runner.request = transient
        self.runner.verify_route(self.service + "-blue")
        self.assertEqual(len(attempts), 2)
        self.assertEqual(self.runner.probe[self.service + "-blue"], attempts[-1])

    def test_route_probe_continuous_connection_failure_is_not_accepted(self):
        with patch.object(self.runner, "request", side_effect=module.DeploymentError("connection failed")) as request:
            with self.assertRaisesRegex(module.DeploymentError, "Could not verify requests"):
                self.runner.verify_route(self.service + "-blue")
            self.assertEqual(request.call_count, 10)

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

    def test_switch_updates_api_and_metrics_together(self):
        changed = module.replace_service(CONFIG, 'backend-green', 'backend')
        self.assertIn('server backend-green:8080;', changed)
        self.assertIn('server backend-green:8081;', changed)

    def test_missing_or_mismatched_metrics_configuration_blocks_candidate(self):
        for configuration in (CONFIG.split('upstream backend_metrics_active')[0],
                              CONFIG.replace('server backend-blue:8081;', 'server backend-green:8081;')):
            self.runner.config.write_text(configuration)
            with self.assertRaisesRegex(module.DeploymentError, 'metrics'):
                self.runner.run()
        self.assertEqual(self.runner.recreations, [])

    def test_wrong_metrics_target_or_response_restores_both_paths(self):
        for failure in ('metrics-route', 'metrics-body'):
            with self.subTest(failure=failure):
                self.runner.config.write_text(CONFIG)
                self.runner.served = 'backend-blue'
                self.runner.failure = failure
                with self.assertRaisesRegex(module.DeploymentError, 'previous route restored'):
                    self.runner.run()
                self.assertEqual(self.runner.config.read_text(), CONFIG)
                self.assertEqual(self.runner.stopped, set())

    def test_missing_or_changed_management_binding_blocks_deployment(self):
        self.runner.edge = 'edge-nginx'
        configuration = {'services': {'edge-nginx': {'ports': [
            {'target': 8081, 'published': '8081', 'host_ip': '10.1.0.8'}]}}}
        for bindings in ({}, {'8081/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]},
                         {'8081/tcp': [{'HostIp': '10.1.0.8', 'HostPort': '8082'}]}):
            with self.subTest(bindings=bindings):
                with patch.object(self.runner, 'compose', return_value=json.dumps(configuration)), \
                     patch.object(module, 'docker', return_value=json.dumps(bindings)):
                    with self.assertRaisesRegex(module.DeploymentError, 'binding'):
                        self.runner.check_metrics_binding()
        with patch.object(self.runner, 'compose', return_value=json.dumps(configuration)), \
             patch.object(module, 'docker', return_value=json.dumps(
                 {'8081/tcp': [{'HostIp': '10.1.0.8', 'HostPort': '8081'}]})):
            self.runner.check_metrics_binding()

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



class IncompatibleSchemaTests(unittest.TestCase):
    """The 2026-11-16 development failure: the candidate's Flyway dropped a column the active backend maps."""
    service = "backend"

    def setUp(self):
        DeploymentTests.setUp(self)
        self.repository = self.runner.runtime / "repository"
        (self.repository / module.MIGRATIONS).mkdir(parents=True)
        self.git("init", "--quiet")
        self.active = self.commit("V1__announcements.sql", "CREATE TABLE announcements (id bigint, supply_owner text);")
        self.runner.repository = self.repository
        self.runner.images["backend-blue"] = "sha256:" + "b" * 64
        self.runner.labels["backend-blue"] = self.active

    tearDown = DeploymentTests.tearDown

    def git(self, *arguments):
        return subprocess.run(["git", "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid",
                               "-c", "commit.gpgsign=false", *arguments], cwd=self.repository,
                              check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, name, sql):
        (self.repository / module.MIGRATIONS / name).write_text(sql)
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", name)
        return self.git("rev-parse", "HEAD")

    def release(self, sql, breaks_active=True):
        self.runner.labels[CANDIDATE] = self.commit("V2__release.sql", sql)
        if breaks_active:
            self.runner.breaks_on_start = {"backend-blue"}

    def assert_untouched(self):
        self.assertEqual(self.runner.recreations, [])
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertEqual(self.runner.served, "backend-blue")
        self.assertEqual(self.runner.broken, set())

    def test_incompatible_migration_stops_before_candidate_runs_flyway(self):
        self.release("-- 이전 버전은 supply_owner를 매핑한다.\nALTER TABLE announcements DROP COLUMN supply_owner;")
        with self.assertRaisesRegex(module.DeploymentError, r"V2__release\.sql: DROP COLUMN.*--allow-incompatible-schema"):
            self.runner.run()
        self.assert_untouched()

    def test_undetected_incompatibility_still_halts_with_active_backend_broken(self):
        # What happened on the development server; the scan only narrows this to statements it does not know.
        self.release("ALTER TABLE announcements ADD COLUMN note text;")
        with self.assertRaisesRegex(module.DeploymentError, "Backend announcement query is unavailable"):
            self.runner.run()
        self.assertEqual(self.runner.recreations[0][0], "backend-green")
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertIn("backend-blue", self.runner.broken)

    def test_allowed_release_switches_to_candidate_that_serves_the_new_schema(self):
        self.runner.allow_incompatible = True
        self.release("ALTER TABLE announcements DROP COLUMN supply_owner;")
        self.runner.run()
        self.assertEqual(self.runner.served, "backend-green")
        self.assertEqual(self.runner.stopped, {"backend-blue"})
        candidate_query = next(index for index, args in enumerate(self.runner.commands)
                               if args[:3] == ("exec", "backend-green", "curl") and "announcements" in args[-1])
        reload = next(index for index, args in enumerate(self.runner.commands) if args[2:5] == ("nginx", "-s", "reload"))
        self.assertLess(candidate_query, reload)

    def test_allowed_release_does_not_switch_to_candidate_that_cannot_query(self):
        self.runner.allow_incompatible = True
        self.release("ALTER TABLE announcements DROP COLUMN supply_owner;")
        self.runner.breaks_on_start = {"backend-blue", "backend-green"}
        with self.assertRaisesRegex(module.DeploymentError, "Target backend announcement query is unavailable"):
            self.runner.run()
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertEqual(self.runner.stopped, set())

    def test_flag_recovers_route_when_active_backend_is_already_broken(self):
        # Development server state after the failed run: Blue serves 500 on the migrated schema.
        self.release("ALTER TABLE announcements DROP COLUMN supply_owner;", breaks_active=False)
        self.runner.broken = {"backend-blue"}
        with self.assertRaisesRegex(module.DeploymentError, "Backend announcement query is unavailable"):
            self.runner.run()
        self.assertEqual(self.runner.recreations, [])
        self.runner.allow_incompatible = True
        self.runner.run()
        self.assertEqual(self.runner.served, "backend-green")

    def test_compatible_statements_and_comments_deploy_without_flag(self):
        self.release("""-- 다음 릴리스에서 DROP COLUMN legacy_note 예정
/* RENAME TO 설명 */
ALTER TABLE announcements ADD COLUMN note text;
ALTER TABLE announcements DROP CONSTRAINT IF EXISTS ck_note;
ALTER TABLE announcements ALTER COLUMN note DROP NOT NULL;
ALTER TABLE announcements ALTER COLUMN note DROP DEFAULT;
ALTER TABLE announcements RENAME CONSTRAINT ck_old TO ck_new;
CREATE TEMPORARY TABLE staged ON COMMIT DROP AS SELECT 1;""", breaks_active=False)
        self.runner.run()
        self.assertEqual(self.runner.served, "backend-green")

    def test_each_breaking_statement_is_reported(self):
        statements = {
            "DROP TABLE": "DROP TABLE supply_row_groups;",
            "DROP COLUMN": "ALTER TABLE announcements DROP supply_owner;",
            "RENAME": "ALTER TABLE supply_rows RENAME COLUMN announcement_exclusive_area TO exclusive_area;",
            "ALTER COLUMN TYPE": "ALTER TABLE announcements ALTER COLUMN id TYPE text;",
            "SET NOT NULL": "ALTER TABLE announcements ALTER COLUMN supply_owner SET NOT NULL;",
        }
        for kind, sql in statements.items():
            with self.subTest(kind=kind):
                revision = self.commit("V2__release.sql", sql)
                self.assertEqual(self.runner.schema_changes("backend-blue", revision), ["V2__release.sql: " + kind])

    def test_unknown_active_revision_fails_closed(self):
        self.release("ALTER TABLE announcements ADD COLUMN note text;", breaks_active=False)
        for labels, reason in (({}, "labels are missing"), ({"backend-blue": "c" * 40}, "not in this checkout")):
            with self.subTest(reason=reason):
                self.runner.labels = {CANDIDATE: self.runner.labels[CANDIDATE], **labels}
                with self.assertRaisesRegex(module.DeploymentError, reason):
                    self.runner.run()
                self.assert_untouched()

    def test_flag_still_requires_active_backend_health(self):
        self.runner.allow_incompatible = True
        self.release("ALTER TABLE announcements DROP COLUMN supply_owner;", breaks_active=False)
        self.runner.failure = "health"
        with self.assertRaisesRegex(module.DeploymentError, "health response is not UP"):
            self.runner.run()
        self.assert_untouched()

    def test_flag_keeps_checks_after_switch_strict(self):
        # The candidate answers inside its container but fails through Edge: the switch is undone,
        # and the report says the restored backend is still broken.
        self.runner.allow_incompatible = True
        self.release("ALTER TABLE announcements DROP COLUMN supply_owner;")
        self.runner.failure = "query"
        with self.assertRaisesRegex(module.DeploymentError,
                                    "previous route restored; its announcement query still fails"):
            self.runner.run()
        self.assertEqual(self.runner.config.read_text(), CONFIG)
        self.assertEqual(self.runner.stopped, set())

    def test_same_image_skips_comparison(self):
        self.runner.images["backend-blue"] = CANDIDATE
        self.runner.labels = {}
        self.runner.run()
        self.assertEqual(self.runner.served, "backend-green")


if __name__ == "__main__":
    unittest.main()
