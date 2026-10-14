"""Generate behavior cases from the dashboard's prod, disk and PostgreSQL expressions."""

import json
import copy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
panels = {p["id"]: p for p in json.loads(
    (ROOT / "infra/grafana/dashboards/api-load-test.json").read_text())["panels"]}
series, cases = [], []


def metric(name, values, **labels):
    selector = ",".join(f'{key}="{value}"' for key, value in labels.items())
    series.append({"series": f"{name}{{{selector}}}", "values": values})


def case(panel, value=None, labels="{}", ref="A", time="5m"):
    expression = next(t["expr"] for t in panels[panel]["targets"] if t["refId"] == ref)
    if 36 <= panel <= 44:
        assert not any(v in expression for v in ["$environment", "${scope", "${api", "${method", "${pool"])
    expression = expression.replace("$__rate_interval", "5m").replace("$db_host", "db:9100")
    samples = []
    if value is not None:
        expression = f"abs(({expression}) - {value}) < bool 1e-8"
        samples = [{"labels": labels, "value": 1}]
    cases.append({"expr": expression, "eval_time": time, "exp_samples": samples})


for env in ["dev", "prod"]:
    tags = dict(job="backend", instance=env, environment=env)
    metric("up", "1x5 0x4" if env == "prod" else "1x9", **tags)
    for uri, status, rate in [("/api/v2/complexes/map", "200", 54),
                              ("/api/v2/complexes/map", "500", 6),
                              ("/api/admin/test", "200", 60), ("/api/health", "200", 600)]:
        metric("http_server_requests_seconds_count", f"0+{rate if env == 'prod' else 600}x9",
               **tags, method="GET", uri=uri, status=status)
    for pool, pending in [("primary", 3), ("shared", 2)]:
        metric("hikaricp_connections_pending", f"{pending if env == 'prod' else 99}x9", **tags, pool=pool)
        metric("hikaricp_connections_timeout_total", "0+1x9", **tags, pool=pool)
for uri, buckets in [("/api/v2/complexes/map", {"0.1": 54, "0.5": 60, "+Inf": 60}),
                     ("/api/admin/test", {"0.1": 60, "0.5": 60, "+Inf": 60})]:
    for le, rate in buckets.items():
        metric("http_server_requests_seconds_bucket", f"0+{rate}x9", job="backend", instance="prod",
               environment="prod", uri=uri, method="GET", le=le)
for id, value in [(36, 1), (37, 2), (38, 0.1), (39, 5), (40, 5), (41, 10), (43, 0.1)]:
    case(id, value)
for id in [37, 38, 39, 40, 41, 42, 43, 44]:
    case(id, time="7m")

# Disk averages, bytes/sec, zero I/O, host isolation and failed current scrape.
host = dict(job="db-host", instance="db:9100")
metric("up", "1x5 0x4", **host)
for device in ["nvme0n1", "loop0"]:
    for name, rate in [("read_time_seconds", 6), ("reads_completed", 600),
                       ("write_time_seconds", 12), ("writes_completed", 300),
                       ("io_time_seconds", 30), ("read_bytes", 61440),
                       ("written_bytes", 122880), ("io_time_weighted_seconds", 120)]:
        metric("node_disk_" + name + "_total", f"0+{rate}x9", **host, device=device)
for name in ["read_time_seconds", "reads_completed", "write_time_seconds", "writes_completed"]:
    metric("node_disk_" + name + "_total", "0x9", **host, device="idle")
disk_labels = '{device="nvme0n1",instance="db:9100",job="db-host"}'
for id, ref, value in [(45, "A", 0.01), (45, "B", 0.04), (46, "A", 50),
                        (47, "A", 1024), (47, "B", 2048), (48, "A", 2)]:
    case(id, value, disk_labels, ref)
    case(id, ref=ref, time="7m")

# One real lock wait. Other active/idle sessions must not count as lock waiters.
pg = dict(job="postgres", instance="pg:9187", db_role="dev")
metric("up", "1x7 0x2", **pg)
metric("pg_up", "1x6 0x3", **pg)
metric("pg_scrape_collector_success", "1x5 0x4", **pg, collector="stat_activity")
metric("pg_scrape_collector_success", "1x9", **pg, collector="stat_database")
for datname in ["toadzip", "template1"]:
    for state, wait, count in [("active", "Lock", 1), ("active", "IO", 3), ("idle", "Client", 7)]:
        metric("pg_stat_activity_count", f"{count}x9", **pg, datname=datname,
               state=state, wait_event_type=wait)
    metric("pg_stat_activity_max_tx_duration", "12x9", **pg, datname=datname, state="active")
metric("pg_stat_database_deadlocks", "0+1x9", **pg, datname="toadzip")
pg_labels = '{db_role="dev",instance="pg:9187",datname="toadzip"}'
case(51, 1, pg_labels)
case(52, 12, pg_labels)
case(54, 5, pg_labels)
for time in ["6m", "7m", "8m"]:
    for id in [51, 52, 53]:
        case(id, time=time)
case(54, time="7m")

tests = [{"name": "prod isolation, disk math and PostgreSQL failure masking", "interval": "1m",
          "input_series": series, "promql_expr_test": cases}]

quiet = copy.deepcopy([s for s in series if 'job="backend"' in s["series"]])
for sample in quiet:
    sample["values"] = "1x9" if sample["series"].startswith("up{") else "0x9"
cases = []
case(37, 0)
case(39)
case(43, 0)
tests.append({"name": "quiet prod is not a healthy error ratio", "interval": "1m",
              "input_series": quiet, "promql_expr_test": cases})

# Healthy no-wait is zero, absent activity collection is not a healthy zero.
minimal = [copy.deepcopy(s) for s in series if s["series"].startswith(
    ("up{job=\"postgres\"", "pg_up", "pg_scrape_collector_success"))]
for s in minimal:
    s["values"] = "1x9"
healthy = [*minimal, {"series": 'pg_stat_activity_count{job="postgres",instance="pg:9187",db_role="dev",datname="toadzip",state="idle"}', "values": "2x9"}]
cases = []
case(51, 0, pg_labels)
tests.append({"name": "healthy idle database has zero waiters", "interval": "1m", "input_series": healthy,
              "promql_expr_test": cases})
cases = []
case(51)
tests.append({"name": "missing activity is not zero", "interval": "1m", "input_series": minimal,
              "promql_expr_test": cases})
print(json.dumps({"rule_files": [], "evaluation_interval": "1m", "tests": tests}, indent=2))
