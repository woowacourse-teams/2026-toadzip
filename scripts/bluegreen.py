#!/usr/bin/env python3
"""Blue/Green deployment with request draining and retained frontend assets."""

import argparse
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RUNTIME = ROOT / "infra/runtime/bluegreen"


class DeploymentError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def docker(*arguments, environment=None, timeout=30):
    result = subprocess.run(
        ["docker", *arguments], cwd=ROOT, env=environment,
        text=True, capture_output=True, timeout=timeout,
    )
    if result.returncode:
        raise DeploymentError(f"Docker command failed: {' '.join(arguments[:3])}")
    return result.stdout.strip()


def upstream_pattern(service):
    if service not in ("frontend", "backend"):
        raise DeploymentError("Unsupported deployment service")
    return re.compile(
        rf"(?m)(^\s*upstream\s+{service}_active\s*\{{\s*server\s+)"
        rf"({service}(?:-blue|-green)?)(:8080;\s*\}})"
    )


def active_service(text, service):
    matches = list(upstream_pattern(service).finditer(text))
    if len(matches) != 1:
        raise DeploymentError(f"Expected exactly one supported {service}_active upstream")
    return matches[0].group(2)


def replace_service(text, target, service):
    active_service(text, service)
    if target not in (service + "-blue", service + "-green"):
        raise DeploymentError("Unsupported switch target")
    return upstream_pattern(service).sub(lambda match: match.group(1) + target + match.group(3), text)


class Deployment:
    def __init__(self, options):
        self.options = options
        self.service = options.service
        self.runtime = options.runtime.resolve(strict=True)
        self.directory = (self.runtime / "edge").resolve(strict=True)
        self.config = self.directory / "default.conf"
        self.assets = self.directory.parent / "assets"
        if self.config.resolve() == (ROOT / "infra/nginx/edge/default.conf").resolve():
            raise DeploymentError("Use a runtime copy, not the tracked Nginx configuration")
        self.environment = dict(os.environ, EDGE_NGINX_CONF_DIR=str(self.directory),
                                EDGE_NGINX_ASSET_DIR=str(self.assets))
        self.files = ["-f", "compose.yaml", "-f", "compose.bluegreen.yaml"]
        for overlay in options.compose_override:
            self.files.extend(["-f", str(overlay.resolve(strict=True))])
        self.edge = None
        self.opener = urllib.request.build_opener(NoRedirect())

    def compose(self, *arguments, environment=None, timeout=30):
        return docker(
            "compose", "--project-name", self.options.project, *self.files,
            "--profile", "bluegreen", *arguments,
            environment=environment or self.environment, timeout=timeout,
        )

    @contextlib.contextmanager
    def lock(self):
        with (self.directory.parent / "deploy.lock").open("a") as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exception:
                raise DeploymentError("Another deployment is running") from exception
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)

    def find_container(self, service, include_stopped=False):
        filters = ("--all",) if include_stopped else ("--status", "running")
        ids = self.compose("ps", *filters, "-q", service).splitlines()
        if len(ids) > 1:
            raise DeploymentError(f"Expected at most one container: {service}")
        return ids[0] if ids else None

    def container(self, service, include_stopped=False):
        target = self.find_container(service, include_stopped)
        ids = [target] if target else []
        if len(ids) != 1:
            raise DeploymentError(f"Expected one running container: {service}")
        return ids[0]

    def check_mount(self):
        self.edge = self.container("edge-nginx")
        mounts = json.loads(docker("inspect", "--format", "{{json .Mounts}}", self.edge))
        matches = [mount for mount in mounts if mount["Destination"] == "/etc/nginx/conf.d"]
        if len(matches) != 1 or matches[0]["RW"]:
            raise DeploymentError("Edge Nginx needs a read-only configuration directory mount")
        if Path(matches[0]["Source"]).resolve() != self.directory:
            raise DeploymentError("Edge Nginx mounts a different configuration directory")
        if self.service == "frontend":
            assets = [mount for mount in mounts if mount["Destination"] == "/var/cache/toadzip/assets"]
            if (len(assets) != 1 or assets[0]["RW"]
                    or Path(assets[0]["Source"]).resolve() != self.assets.resolve(strict=True)):
                raise DeploymentError("Edge Nginx needs the matching read-only asset archive mount")
        self.check_mounted_text(self.config.read_text())
        if self.service == "frontend":
            self.assets.chmod(0o755)
            probe = self.assets / ("edge-archive-probe-" + uuid.uuid4().hex + ".js")
            payload = uuid.uuid4().hex.encode()
            try:
                with probe.open("xb") as handle:
                    handle.write(payload)
                    os.fchmod(handle.fileno(), 0o644)
                if self.request("/assets/" + probe.name) != (200, payload):
                    raise DeploymentError("Edge asset archive is unavailable; update and reload runtime asset locations")
            finally:
                probe.unlink(missing_ok=True)

    def check_mounted_text(self, expected):
        mounted = docker("exec", self.edge, "cat", "/etc/nginx/conf.d/default.conf")
        if mounted.strip() != expected.strip():
            raise DeploymentError("Mounted configuration differs from the runtime file")

    def request(self, path):
        url = self.options.url.rstrip("/") + path
        try:
            with self.opener.open(url, timeout=5) as response:
                return response.status, response.read(65536)
        except urllib.error.HTTPError as error:
            return error.code, b""
        except (urllib.error.URLError, TimeoutError) as error:
            raise DeploymentError(f"HTTP request failed: {path}") from error

    def check_http(self):
        if self.request("/manifest.webmanifest")[0] != 200:
            raise DeploymentError("Frontend manifest is unavailable")
        status, body = self.request("/api/health")
        try:
            healthy = status == 200 and json.loads(body).get("status") == "UP"
        except (ValueError, AttributeError):
            healthy = False
        if not healthy:
            raise DeploymentError("Backend health response is not UP")
        if self.service == "backend":
            status, body = self.request("/api/v1/announcements?size=1")
            try:
                valid = status == 200 and isinstance(json.loads(body).get("data"), dict)
            except (ValueError, AttributeError):
                valid = False
            if not valid:
                raise DeploymentError("Backend announcement query is unavailable")

    def verify_route(self, target):
        since = datetime.datetime.now(datetime.timezone.utc).isoformat()
        # SPA fallbacks log /index.html as $uri, hiding the unique request path.
        # Use a frontend-owned static route; /assets/ is served by Edge itself.
        prefix = "/api/edge-probe-" if self.service == "backend" else "/region-boundaries/edge-probe-"
        probe = prefix + uuid.uuid4().hex
        for _ in range(10):
            status, _body = self.request(probe)
            if status == 404:
                logs = docker("logs", "--since", since, "--tail", "100", target)
                if probe in logs:
                    return
            time.sleep(0.5)
        raise DeploymentError(
            f"Could not verify requests reaching the selected {self.service} "
            f"(probe={probe}, last_status={status}, expected=404 with target log)"
        )

    def check_target(self, service):
        target = self.container(service, include_stopped=True)
        state = json.loads(docker("inspect", "--format", "{{json .State}}", target))
        if not state["Running"]:
            if state["Status"] not in ("exited", "created"):
                raise DeploymentError(f"Cannot restart rollback container: {service}")
            docker("start", target)
            deadline = time.monotonic() + 180
            while True:
                state = json.loads(docker("inspect", "--format", "{{json .State}}", target))
                if not state["Running"] or state.get("Health", {}).get("Status") == "unhealthy":
                    raise DeploymentError(f"Rollback container failed to start: {service}")
                if state.get("Health", {}).get("Status") == "healthy":
                    break
                if time.monotonic() >= deadline:
                    raise DeploymentError(f"Rollback container health timed out: {service}")
                time.sleep(0.5)
        health = docker("inspect", "--format", "{{.State.Health.Status}}", target)
        if health != "healthy":
            raise DeploymentError(f"Target is not healthy: {service}")
        if self.service == "backend":
            body = docker("exec", target, "curl", "--fail", "--silent", "--show-error", "--max-time", "4",
                          "http://127.0.0.1:8081/actuator/health")
            try:
                healthy = json.loads(body).get("status") == "UP"
            except (ValueError, AttributeError):
                healthy = False
            if not healthy:
                raise DeploymentError("Target backend management health is not UP")
        else:
            docker("exec", target, "wget", "--quiet", "--output-document=/dev/null",
                   "http://127.0.0.1:8080/manifest.webmanifest")
        return target

    def workers(self):
        command = r'''for d in /proc/[0-9]*; do
            cmd=$(tr '\000' ' ' < "$d/cmdline" 2>/dev/null) || continue
            case "$cmd" in 'nginx: worker process'*)
                started=$(awk '{print $22}' "$d/stat" 2>/dev/null) || continue
                [ -n "$started" ] || continue
                case "$cmd" in *'shutting down'*) mode=draining;; *) mode=active;; esac
                printf '%s %s %s\n' "${d##*/}" "$started" "$mode";;
            esac
        done'''
        output = docker("exec", self.edge, "sh", "-c", command)
        result = {}
        for line in output.splitlines():
            pid, started, mode = line.split()
            result[(pid, started)] = mode
        if not result:
            raise DeploymentError("No Edge Nginx workers found; refusing container cleanup")
        return result

    def wait_workers(self, previous):
        deadline = time.monotonic() + self.options.drain_timeout
        while previous.intersection(self.workers()):
            if time.monotonic() >= deadline:
                raise DeploymentError("Request drain timed out; previous container was not stopped")
            time.sleep(0.25)

    def archive_assets(self, target):
        if self.service != "frontend":
            return
        self.assets.chmod(0o755)
        with tempfile.TemporaryDirectory(dir=self.runtime, prefix="asset-copy-") as temporary:
            docker("cp", target + ":/usr/share/nginx/html/assets/.", temporary)
            files = list(Path(temporary).rglob("*"))
            if not files:
                raise DeploymentError("Frontend asset directory is empty")
            for source in files:
                if source.is_symlink() or not (source.is_dir() or source.is_file()):
                    raise DeploymentError("Asset archive accepts only regular files and directories")
                if source.is_dir():
                    continue
                destination = self.assets / source.relative_to(temporary)
                if self.assets.resolve() not in destination.resolve().parents:
                    raise DeploymentError("Asset destination escapes the archive")
                if destination.exists():
                    if hashlib.sha256(destination.read_bytes()).digest() != hashlib.sha256(source.read_bytes()).digest():
                        raise DeploymentError(f"Asset URL reused with different contents: {source.relative_to(temporary)}")
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
                for directory in (destination.parent, *destination.parent.parents):
                    if directory == self.assets:
                        break
                    directory.chmod(0o755)
                descriptor, name = tempfile.mkstemp(dir=destination.parent, prefix=".asset-", suffix=".tmp")
                try:
                    with os.fdopen(descriptor, "wb") as handle:
                        with source.open("rb") as incoming:
                            shutil.copyfileobj(incoming, handle)
                        os.fchmod(handle.fileno(), 0o644)
                        handle.flush()
                        os.fsync(handle.fileno())
                    os.replace(name, destination)
                finally:
                    if Path(name).exists():
                        Path(name).unlink()

    def retire(self, previous, target):
        if previous == target:
            return
        try:
            docker("stop", "--time", "40", previous, timeout=50)
            state = json.loads(docker("inspect", "--format", "{{json .State}}", previous))
            if state["Running"] or state["ExitCode"] == 137:
                raise DeploymentError("Previous container did not shut down gracefully")
        except (DeploymentError, subprocess.TimeoutExpired) as error:
            raise DeploymentError("New route is active; previous container cleanup failed; inspect containers") from error
        self.verify_route(target)
        self.check_http()

    def write_config(self, text):
        descriptor, temporary = tempfile.mkstemp(dir=self.directory, suffix=".tmp", prefix="switch-")
        try:
            with os.fdopen(descriptor, "w") as handle:
                os.fchmod(handle.fileno(), stat.S_IMODE(self.config.stat().st_mode))
                handle.write(text)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.config)
        finally:
            if Path(temporary).exists():
                Path(temporary).unlink()
        self.check_mounted_text(text)

    def reload(self):
        docker("exec", self.edge, "nginx", "-t")
        docker("exec", self.edge, "nginx", "-s", "reload")

    def switch(self, color, before):
        target_service = self.service + "-" + color
        target = self.check_target(target_service)
        previous = self.container(active_service(before, self.service))
        self.check_http()
        self.verify_route(previous)
        self.archive_assets(previous)
        self.archive_assets(target)
        previous_workers = set(self.workers())
        after = replace_service(before, target_service, self.service)
        backup = self.runtime / ("before-switch-" + uuid.uuid4().hex + ".conf.bak")
        with backup.open("x") as handle:
            handle.write(before)
        try:
            self.write_config(after)
            self.reload()
            self.verify_route(target)
            self.check_http()
            self.wait_workers(previous_workers)
        except (Exception, KeyboardInterrupt) as failure:
            try:
                self.write_config(before)
                self.reload()
                self.verify_route(previous)
                self.check_http()
            except (Exception, KeyboardInterrupt) as recovery:
                raise DeploymentError(f"Switch and recovery failed; backup: {backup}") from recovery
            raise DeploymentError(f"Switch failed ({failure}); previous route restored; backup: {backup}") from failure
        self.retire(previous, target)
        print(f"{self.service.capitalize()} route verified: {target_service}; backup: {backup}")

    def run(self):
        with self.lock():
            self.run_locked()

    def run_locked(self):
        self.check_mount()
        draining = {worker for worker, mode in self.workers().items() if mode == "draining"}
        if draining:
            self.wait_workers(draining)
        before = self.config.read_text()
        active = active_service(before, self.service)
        if self.options.action == "switch":
            self.switch(self.options.color, before)
            return
        color = "green" if active == self.service + "-blue" else "blue"
        self.check_http()
        self.verify_route(self.container(active))
        image = docker("image", "inspect", "--format", "{{.Id}}", self.options.image)
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise DeploymentError("Expected a local immutable image ID")
        self.archive_assets(self.container(active))
        inactive = self.find_container(self.service + "-" + color, include_stopped=True)
        if inactive:
            self.archive_assets(inactive)
        environment = dict(self.environment, **{self.service.upper() + "_" + color.upper() + "_IMAGE": image})
        self.compose(
            "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--force-recreate",
            "--wait", "--wait-timeout", "180", self.service + "-" + color,
            environment=environment, timeout=210,
        )
        self.switch(color, before)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["deploy", "switch"])
    parser.add_argument("--project", default="2026-toadzip")
    parser.add_argument("--runtime", type=Path, default=DEFAULT_RUNTIME)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    parser.add_argument("--compose-override", type=Path, action="append", default=[])
    parser.add_argument("--service", choices=["frontend", "backend"], default="frontend")
    parser.add_argument("--image", help="Existing local image tag or ID for the selected service")
    parser.add_argument("--color", choices=["blue", "green"])
    parser.add_argument("--drain-timeout", type=int, default=330, help="Seconds to wait for old Nginx workers")
    options = parser.parse_args()
    if options.drain_timeout < 1:
        parser.error("--drain-timeout must be positive")
    if options.action == "deploy" and not options.image:
        parser.error("deploy requires --image")
    if options.action == "switch" and not options.color:
        parser.error("switch requires --color")
    try:
        Deployment(options).run()
    except (DeploymentError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("ERROR: deployment interrupted", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
