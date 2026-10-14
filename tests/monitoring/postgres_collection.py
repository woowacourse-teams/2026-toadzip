"""Verify the shipped exporter against an isolated PostgreSQL 17 row-lock wait."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.request import urlopen
import uuid

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".local/verification/monitoring-guardrails"
WORK.mkdir(parents=True, exist_ok=True)
name = "toadzip-metrics-test-" + uuid.uuid4().hex[:8]
database, exporter = name + "-db", name + "-exporter"


def docker(*args, input=None, check=True):
    return subprocess.run(["docker", *args], input=input, text=True, capture_output=True, check=check)


def sql(statement):
    return docker("exec", "-i", database, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1",
                  "-At", input=statement).stdout.strip()


def until(predicate, description):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.25)
    raise AssertionError(description)


def samples(body, metric, **labels):
    result = []
    for line in body.splitlines():
        if not (line.startswith(metric + "{") or line.startswith(metric + " ")):
            continue
        tags = dict(re.findall(r'(\w+)="([^\"]*)"', line))
        if all(tags.get(key) == value for key, value in labels.items()):
            result.append(float(line.rsplit(" ", 1)[1]))
    return result


processes = []
try:
    with tempfile.TemporaryDirectory(prefix=name, dir=WORK) as folder:
        password = Path(folder) / "monitor-password"
        password.write_text("fixture-monitor")
        password.chmod(0o444)
        environment = {**os.environ, "DB_METRICS_BIND_IP": "127.0.0.1",
                       **{key: str(password) for key in ["DEV_DB_METRICS_PASSWORD_FILE",
                           "PROD_DB_METRICS_PASSWORD_FILE", "SHARED_DB_METRICS_PASSWORD_FILE"]}}
        config = json.loads(subprocess.run([
            "docker", "compose", "--env-file", "/dev/null", "-f",
            str(ROOT / "infra/db/compose.postgres-metrics.yaml"), "config", "--format", "json"
        ], env=environment, capture_output=True, text=True, check=True).stdout)
        settings = config["services"]["postgres-dev"]
        for role, port in [("dev", 5432), ("prod", 5433), ("shared", 5434)]:
            assert f":{port}/postgres?" in config["services"]["postgres-" + role]["environment"]["DATA_SOURCE_URI"]
        docker("network", "create", name)
        docker("run", "-d", "--name", database, "--network", name,
               "-e", "POSTGRES_PASSWORD=fixture-admin", "postgres:17-alpine")
        # The image initializes through a temporary socket-only server before its final TCP listener.
        until(lambda: docker("exec", database, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", check=False).returncode == 0,
              "PostgreSQL did not start")
        sql((ROOT / "infra/db/create-monitor-role.sql").read_text())
        sql("ALTER ROLE toadzip_monitor PASSWORD 'fixture-monitor'; "
            "CREATE TABLE lock_fixture(id integer PRIMARY KEY, count integer); "
            "INSERT INTO lock_fixture VALUES (1,0);")
        denied = docker("exec", "-e", "PGPASSWORD=fixture-monitor", database, "psql",
                        "-h", "127.0.0.1", "-U", "toadzip_monitor", "-d", "postgres",
                        "-v", "ON_ERROR_STOP=1", "-c", "SELECT * FROM lock_fixture", check=False)
        assert denied.returncode != 0 and "permission denied" in denied.stderr
        command = ["run", "-d", "--name", exporter, "--network", name,
                   "--user", settings["user"], "--read-only", "--cap-drop=ALL",
                   "--security-opt=no-new-privileges:true", "-p", "127.0.0.1::9187",
                   "--mount", f"type=bind,src={password},dst=/run/secrets/db-password,readonly"]
        exporter_environment = {**settings["environment"],
                                "DATA_SOURCE_URI": f"{database}:5432/postgres?sslmode=disable&connect_timeout=3"}
        for key, value in exporter_environment.items():
            command.extend(["-e", f"{key}={value}"])
        docker(*command, settings["image"], *settings["command"])
        port = docker("port", exporter, "9187").stdout.strip().split(":")[-1]

        def scrape():
            with urlopen(f"http://127.0.0.1:{port}/metrics", timeout=10) as response:
                return response.read().decode()

        until(lambda: docker("logs", exporter).stderr.find("Listening on") >= 0, "Exporter did not start")
        baseline = scrape()
        assert samples(baseline, "pg_up") == [1.0]
        assert samples(baseline, "pg_scrape_collector_success", collector="stat_activity") == [1.0]
        assert samples(baseline, "pg_scrape_collector_success", collector="stat_database") == [1.0]
        assert samples(baseline, "pg_stat_database_deadlocks", datname="postgres") == [0.0]
        assert sum(samples(baseline, "pg_stat_activity_count", wait_event_type="Lock")) == 0
        for application, statement in [
            ("fixture-blocker", "BEGIN; UPDATE lock_fixture SET count=count+1 WHERE id=1; SELECT pg_sleep(90);"),
            ("fixture-waiter", "BEGIN; UPDATE lock_fixture SET count=count+1 WHERE id=1; COMMIT;")
        ]:
            process = subprocess.Popen(["docker", "exec", "-i", "-e", f"PGAPPNAME={application}",
                                        database, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1"],
                                       stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            processes.append(process)
            process.stdin.write(statement.encode())
            process.stdin.close()
            if application == "fixture-blocker":
                until(lambda: sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='fixture-blocker' "
                                  "AND wait_event='PgSleep'") == "1", "Blocker did not acquire the row lock")
        until(lambda: sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='fixture-waiter' "
                          "AND wait_event_type='Lock'") == "1", "Waiter did not block")
        blocked = scrape()
        assert sum(samples(blocked, "pg_stat_activity_count", wait_event_type="Lock")) == 1
        assert max(samples(blocked, "pg_stat_activity_max_tx_duration", datname="postgres")) > 0
        sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='fixture-blocker'")
        processes[-1].wait(timeout=10)
        assert sum(samples(scrape(), "pg_stat_activity_count", wait_event_type="Lock")) == 0
        docker("stop", "--time", "1", database)
        failed = scrape()
        assert samples(failed, "pg_up") == [0.0]
        print("PASS: restricted role, successful collectors, real row-lock wait, transaction age, recovery, DB outage")
finally:
    docker("rm", "-f", "-v", exporter, database, check=False)
    for process in processes:
        process.wait(timeout=10)
    docker("network", "rm", name, check=False)
