#!/usr/bin/env python3
"""Local HTTPS smoke checks; uses disposable certificates, never ACME or EC2.

Build the frontend image first, then run with --image toadzip-frontend:https-test.
--config-only checks Compose and the renewal hook without a Docker daemon.
"""

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
DOMAINS = ("dev.bokduckbang.com", "bokduckbang.com")
TLS_GROUP = "42424"
# Do not load a developer's .env or forward application credentials to Compose.
ENVIRONMENT = {
    key: os.environ[key]
    for key in ("PATH", "HOME", "TMPDIR", "DOCKER_CONFIG", "DOCKER_HOST", "DOCKER_CONTEXT")
    if key in os.environ
}


def run(*arguments, env=None, check=True):
    result = subprocess.run(
        arguments, cwd=ROOT, env=ENVIRONMENT if env is None else env,
        text=True, capture_output=True, timeout=180,
    )
    if check and result.returncode:
        raise RuntimeError(f"{arguments[0]} failed ({result.returncode}): {result.stderr.strip()}")
    return result


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def check_compose():
    environment = {
        **ENVIRONMENT, "PRIMARY_DB_HOST": "placeholder", "PRIMARY_DB_PASSWORD": "placeholder",
        "SHARED_DB_HOST": "placeholder", "SHARED_DB_PASSWORD": "placeholder",
        "SPRING_PROFILES_ACTIVE": "dev", "LOKI_PUSH_URL": "http://placeholder",
        "FRONTEND_PORT": "80",
    }

    def compose(overlay=None, values=None, check=True):
        command = ["docker", "compose", "--env-file", "/dev/null", "-f", "compose.yaml"]
        if overlay:
            command.extend(["-f", overlay])
        return run(*command, "config", "--format", "json",
                   env={**environment, **(values or {})}, check=check)

    local = json.loads(compose().stdout)["services"]["frontend"]
    require(not local.get("volumes") and not local.get("group_add"),
            "Local frontend must not require host certificate files or groups")
    bootstrap = json.loads(compose("compose.certbot.yaml").stdout)["services"]["frontend"]
    require([item["target"] for item in bootstrap["volumes"]] == ["/var/www/certbot"],
            "Bootstrap needs only the ACME webroot, not a certificate or config bind")
    require(bootstrap["volumes"][0]["read_only"] and
            bootstrap["volumes"][0]["bind"]["create_host_path"] is False,
            "Bootstrap webroot must be readonly and fail on a missing host directory")
    for missing, values in (
        ("domain", {"TOADZIP_TLS_GID": TLS_GROUP}),
        ("group", {"TOADZIP_TLS_DOMAIN": DOMAINS[0]}),
    ):
        require(compose("compose.https.yaml", values, check=False).returncode != 0,
                f"HTTPS must require its {missing}")
    configurations = {}
    for domain in DOMAINS:
        values = {"TOADZIP_TLS_DOMAIN": domain, "TOADZIP_TLS_GID": TLS_GROUP}
        configuration = json.loads(compose("compose.https.yaml", values).stdout)["services"]["frontend"]
        configurations[domain] = configuration
        ports = {(str(port["published"]), port["target"]) for port in configuration["ports"]}
        require(ports == {("80", 8080), ("443", 8443)}, "HTTP and HTTPS ports must coexist")
        require(configuration["group_add"] == [TLS_GROUP], "Supplementary TLS group missing")
        mounts = {mount["target"]: mount for mount in configuration["volumes"]}
        expected = {"/var/www/certbot", f"/etc/letsencrypt/live/{domain}",
                    f"/etc/letsencrypt/archive/{domain}"}
        require(set(mounts) == expected, "HTTPS needs only its webroot and its own certificate mounts")
        require(all(mount["source"] == target and mount["read_only"] and
                    mount["bind"]["create_host_path"] is False for target, mount in mounts.items()),
                "Certificate mounts must preserve paths, be readonly, and fail when absent")
    with tempfile.NamedTemporaryFile(mode="w", prefix="toadzip-https-", suffix=".env") as settings:
        settings.write("COMPOSE_FILE=compose.yaml:compose.https.yaml\n"
                       f"TOADZIP_TLS_DOMAIN={DOMAINS[0]}\nTOADZIP_TLS_GID={TLS_GROUP}\n")
        settings.flush()
        automatic = json.loads(run("docker", "compose", "--env-file", settings.name,
                                   "config", "--format", "json", env=environment).stdout)["services"]["frontend"]
        require(automatic["ports"] == configurations[DOMAINS[0]]["ports"] and
                automatic["command"] == configurations[DOMAINS[0]]["command"],
                "COMPOSE_FILE in the server env file must activate HTTPS without explicit -f options")
    print("PASS: local Compose, bootstrap, required settings, and two-file HTTPS for both domains")
    print("PASS: server env COMPOSE_FILE selects HTTPS for the normal Compose command")
    return configurations


def check_hook(directory):
    mock = directory / "docker"
    mock.write_text(f"#!{sys.executable}\n" + '''import json, os, sys
arguments = sys.argv[1:]
with open(os.environ["MOCK_LOG"], "a") as log:
    log.write(json.dumps(arguments) + "\\n")
state = json.loads(os.environ["MOCK_STATE"])
if arguments[0] == "ps":
    if state.get("query_failure"):
        sys.exit(1)
    print("\\n".join(state["containers"]))
elif arguments[0] == "inspect":
    if state.get("inspect_failure"):
        sys.exit(1)
    container = next(value for value in arguments if value in state["containers"])
    value = state["containers"][container]
    format_value = arguments[arguments.index("--format") + 1]
    if ".State.Running" in format_value and ".Mounts" in format_value:
        print(str(value["running"]).lower())
        print(value["source"])
    else:
        sys.exit("Unsupported mock inspect format: " + format_value)
elif arguments[0] == "exec":
    if "-t" in arguments:
        sys.exit(state.get("test_status", 0))
    sys.exit(state.get("reload_status", 0))
else:
    sys.exit("Unexpected Docker call")
''')
    mock.chmod(0o755)
    log = directory / "docker.log"
    lineage = f"/etc/letsencrypt/live/{DOMAINS[0]}"
    matching = {"source": lineage, "running": True}
    other = {"source": "/etc/letsencrypt/live/unrelated.invalid", "running": True}
    cases = [
        ("missing lineage", None, {}, False, False),
        ("unrelated certificate", lineage, {"containers": {"other": other}}, True, False),
        ("empty host", lineage, {"containers": {}}, True, False),
        ("stopped match", lineage,
         {"containers": {"one": {**matching, "running": False}}}, False, False),
        ("duplicate match", lineage, {"containers": {"one": matching, "two": matching}}, False, False),
        ("query failure", lineage, {"containers": {}, "query_failure": True}, False, False),
        ("inspect failure", lineage, {"containers": {"one": matching}, "inspect_failure": True}, False, False),
        ("invalid config", lineage, {"containers": {"one": matching}, "test_status": 1}, False, False),
        ("reload failure", lineage, {"containers": {"one": matching}, "reload_status": 1}, False, True),
        ("reload", lineage, {"containers": {"other": other, "one": matching}}, True, True),
        ("production reload", f"/etc/letsencrypt/live/{DOMAINS[1]}",
         {"containers": {"one": {"source": f"/etc/letsencrypt/live/{DOMAINS[1]}", "running": True}}},
         True, True),
    ]
    for name, renewed_lineage, state, success, reload_expected in cases:
        log.write_text("")
        environment = {**ENVIRONMENT, "PATH": f"{directory}:{ENVIRONMENT['PATH']}",
                       "MOCK_LOG": str(log), "MOCK_STATE": json.dumps(state)}
        if renewed_lineage:
            environment["RENEWED_LINEAGE"] = renewed_lineage
        result = run("sh", "infra/certbot/reload-nginx.sh", env=environment, check=False)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        require((result.returncode == 0) == success, f"Hook {name}: {result.stderr.strip()}")
        executions = [call for call in calls if call[0] == "exec"]
        reloads = [call for call in executions if "reload" in call]
        require(bool(reloads) == reload_expected, f"Hook {name}: wrong reload behavior")
        if reload_expected:
            require(executions == [["exec", "one", "nginx", "-c", "/tmp/https.conf", "-t"],
                                   ["exec", "one", "nginx", "-c", "/tmp/https.conf", "-s", "reload"]],
                    f"Hook {name}: must validate the active HTTPS config before reloading it")
        if calls:
            require(calls[0][0] == "ps" and "--all" in calls[0] and
                    "label=com.docker.compose.service=frontend" in calls[0] and
                    "label=com.docker.compose.oneoff=False" in calls[0],
                    "Hook must include stopped frontends and exclude one-off containers")
    print("PASS: renewal hook target selection, failure handling, config check, and reload ordering")


def mount(source, target):
    return ["--mount", f"type=bind,src={source},dst={target},readonly"]


@dataclass
class Response:
    status: int
    body: str
    headers: dict


def request(domain, published, path, certificate=None, host=None):
    options = ["--header", f"Host: {host}"] if host else []
    scheme = "http"
    if certificate:
        scheme = "https"
        options.extend(["--cacert", str(certificate)])
    result = run("curl", "--silent", "--show-error", "--include", "--noproxy", "*",
                 "--connect-timeout", "2", "--max-time", "5", "--resolve",
                 f"{domain}:{published}:127.0.0.1", *options,
                 f"{scheme}://{domain}:{published}{path}")
    header_block, body = result.stdout.split("\n\n", 1)
    lines = header_block.splitlines()
    headers = {key.lower(): value.strip() for key, value in
               (line.split(":", 1) for line in lines[1:])}
    return Response(int(lines[0].split()[1]), body, headers)


def ready(domain, published, certificate=None):
    deadline = time.monotonic() + 15
    while True:
        try:
            response = request(domain, published, "/healthz", certificate)
            require((response.status, response.body) == (200, "ok\n"), "Unexpected health response")
            return
        except RuntimeError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.2)


def check_runtime(directory, image, configurations):
    run("docker", "image", "inspect", image)
    network = f"toadzip-https-smoke-{uuid.uuid4().hex[:12]}"
    containers, volumes = [], []
    network_created = False
    try:
        run("docker", "network", "create", network)
        network_created = True

        def start(options, command=()):
            identifier = run("docker", "run", "-d", "--network", network,
                             *options, image, *command).stdout.strip()
            containers.append(identifier)
            return identifier

        def port(identifier, target):
            mapping = run("docker", "port", identifier, f"{target}/tcp").stdout.strip()
            return mapping.rsplit(":", 1)[1]

        backend_config = directory / "backend.conf"
        backend_config.write_text(
            "pid /tmp/backend.pid; error_log /dev/stderr; events {}\n"
            "http { access_log off; client_body_temp_path /tmp/client-body; "
            "proxy_temp_path /tmp/proxy; fastcgi_temp_path /tmp/fastcgi; "
            "uwsgi_temp_path /tmp/uwsgi; scgi_temp_path /tmp/scgi; "
            "server { listen 8080; location / { default_type text/plain; "
            "return 200 '$http_x_forwarded_proto $request_uri'; } } }\n"
        )
        start(["--network-alias", "backend", *mount(backend_config, "/tmp/backend.conf")],
              ["nginx", "-c", "/tmp/backend.conf", "-g", "daemon off;"])
        local = start(["-p", "127.0.0.1::8080"])
        local_port = port(local, 8080)
        ready(DOMAINS[0], local_port)
        require(run("docker", "exec", local, "id", "-u").stdout.strip() != "0",
                "Frontend image must run as a non-root user")
        home = request(DOMAINS[0], local_port, "/")
        require(home.status == 200 and "<html" in home.body.lower(), "Frontend image must contain the app")
        require(request(DOMAINS[0], local_port, "/arbitrary/route").body == home.body,
                "Local SPA fallback failed")
        require(request(DOMAINS[0], local_port, "/api/probe?one=two").body == "http /api/probe?one=two",
                "Local API must preserve path, query, and HTTP scheme")
        modules = run("docker", "exec", local, "find", "/usr/share/nginx/html/assets", "-name", "*.mjs")
        require(modules.stdout.strip(), "Built frontend must include a PDF worker module")
        module_path = modules.stdout.splitlines()[0].removeprefix("/usr/share/nginx/html")

        def check_module(domain, published, certificate=None):
            response = request(domain, published, module_path, certificate)
            require(response.status == 200 and
                    response.headers.get("content-type", "").startswith("application/javascript") and
                    "immutable" in response.headers.get("cache-control", ""),
                    "PDF worker must retain JavaScript MIME type and immutable asset caching")

        check_module(DOMAINS[0], local_port)
        print("PASS: built image starts locally without TLS settings; non-root, SPA, API, and .mjs MIME")
        webroot = directory / "webroot"
        token = webroot / ".well-known/acme-challenge/probe"
        token.parent.mkdir(parents=True)
        token.write_text("acme-probe\n")
        bootstrap = start(["-p", "127.0.0.1::8080", *mount(webroot, "/var/www/certbot")])
        bootstrap_port = port(bootstrap, 8080)
        ready(DOMAINS[0], bootstrap_port)

        def check_challenge(domain, published):
            found = request(domain, published, "/.well-known/acme-challenge/probe")
            require((found.status, found.body) == (200, "acme-probe\n"), "ACME token must be served verbatim")
            require(request(domain, published, "/.well-known/acme-challenge/missing").status == 404,
                    "A missing ACME token must not redirect or fall back to the app")

        check_challenge(DOMAINS[0], bootstrap_port)
        require(request(DOMAINS[0], bootstrap_port, "/arbitrary/route").body == home.body,
                "Bootstrap must preserve SPA routes")
        print("PASS: bootstrap image serves health, ACME token, missing-token 404, and SPA")

        for domain in DOMAINS:
            certificate_root = directory / domain
            archive = certificate_root / "archive" / domain
            archive.mkdir(parents=True)
            volume = run("docker", "volume", "create", f"{network}-{domain}").stdout.strip()
            volumes.append(volume)

            def certificate(number):
                chain, key = archive / f"fullchain{number}.pem", archive / f"privkey{number}.pem"
                run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", f"/CN={domain}", "-addext", f"subjectAltName=DNS:{domain}",
                    "-keyout", str(key), "-out", str(chain))
                key.chmod(0o600)
                # Linux volumes preserve root/group permissions on Docker Desktop too.
                commands = [f"mkdir -p /certificates/live/{domain} /certificates/archive/{domain}",
                            f"chown 0:{TLS_GROUP} /certificates/live/{domain} /certificates/archive/{domain}",
                            f"chmod 750 /certificates/live/{domain} /certificates/archive/{domain}"]
                for name, mode in (("fullchain", "644"), ("privkey", "640")):
                    destination = f"/certificates/archive/{domain}/{name}{number}.pem"
                    next_link = f"/certificates/live/{domain}/{name}.next"
                    commands.extend([f"cp /source/archive/{domain}/{name}{number}.pem {destination}",
                                     f"chown 0:{TLS_GROUP} {destination}", f"chmod {mode} {destination}",
                                     f"ln -s ../../archive/{domain}/{name}{number}.pem {next_link}",
                                     f"mv -f {next_link} /certificates/live/{domain}/{name}.pem"])
                run("docker", "run", "--rm", "--network", "none", "--user", "0:0",
                    *mount(certificate_root, "/source"), "--mount",
                    f"type=volume,src={volume},dst=/certificates", "--entrypoint", "sh", image,
                    "-ec", " && ".join(commands))
                return chain

            first = certificate(1)
            configuration = configurations[domain]
            options = [*mount(webroot, "/var/www/certbot")]
            for key, value in configuration["environment"].items():
                options.extend(["--env", f"{key}={value}"])
            for kind in ("live", "archive"):
                options.extend(["--mount", f"type=volume,src={volume},"
                                f"dst=/etc/letsencrypt/{kind}/{domain},volume-subpath={kind}/{domain},readonly"])
            denied = run("docker", "run", "--rm", "--network", network, *options, image,
                         "nginx", "-c", "/tmp/https.conf", "-t", check=False)
            require(denied.returncode != 0 and "Permission denied" in denied.stderr,
                    f"TLS must fail without the certificate group: {denied.stderr.strip()}")
            secured = start(["--group-add", TLS_GROUP, "-p", "127.0.0.1::8080",
                             "-p", "127.0.0.1::8443", *options], configuration["command"])
            plain_port, secure_port = port(secured, 8080), port(secured, 8443)
            ready(domain, secure_port, first)
            require(run("docker", "exec", secured, "id", "-u").stdout.strip() != "0",
                    "HTTPS must not switch the frontend to root")
            run("docker", "exec", secured, "nginx", "-c", "/tmp/https.conf", "-t")
            redirect = request(domain, plain_port, "/api/probe?one=two", host="untrusted.invalid")
            require(redirect.status == 308 and
                    redirect.headers.get("location") == f"https://{domain}/api/probe?one=two",
                    "Redirect must preserve the request path/query and use the configured domain")
            ready(domain, plain_port)
            check_challenge(domain, plain_port)
            require(request(domain, secure_port, "/api/probe?one=two", first).body ==
                    "https /api/probe?one=two", "HTTPS API must forward scheme, path, and query")
            require(request(domain, secure_port, "/arbitrary/route", first).body == home.body,
                    "HTTPS must preserve SPA routes")
            check_module(domain, secure_port, first)
            second = certificate(2)
            run("docker", "exec", secured, "nginx", "-c", "/tmp/https.conf", "-t")
            run("docker", "exec", secured, "nginx", "-c", "/tmp/https.conf", "-s", "reload")
            ready(domain, secure_port, second)
            print(f"PASS: {domain}: TLS trust, group access, redirects, ACME, API, SPA, .mjs, and certificate reload")
    finally:
        errors = []
        resources = [("container", value) for value in reversed(containers)]
        if network_created:
            resources.append(("network", network))
        resources.extend(("volume", value) for value in volumes)
        for kind, value in resources:
            arguments = ["docker", kind, "rm"]
            if kind == "container":
                arguments.append("-f")
            result = run(*arguments, value, check=False)
            if result.returncode:
                errors.append(f"{kind} {value}: {result.stderr.strip()}")
        require(not errors, "Cleanup failed: " + "; ".join(errors))
        print("PASS: disposable Docker resources removed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="toadzip-frontend:https-test", help="Already built frontend image")
    parser.add_argument("--config-only", action="store_true", help="Check Compose and hook without a Docker daemon")
    arguments = parser.parse_args()
    configurations = check_compose()
    with tempfile.TemporaryDirectory(prefix="toadzip-https-smoke-") as temporary:
        directory = Path(temporary)
        check_hook(directory)
        if not arguments.config_only:
            check_runtime(directory, arguments.image, configurations)
    print("PASS: local HTTPS checks complete (real ACME issuance and EC2 deployment are not tested)")


if __name__ == "__main__":
    sys.stdout.reconfigure(line_buffering=True)
    main()
