"""Generate promtool cases from the announcement collection alerts: thresholds, environment isolation, never-succeeded types."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
rules = {r["uid"]: r for r in json.loads(
    (ROOT / "infra/grafana/alerting/ingest.json").read_text())["groups"][0]["rules"]}
series, cases = [], []


def metric(name, values, **labels):
    selector = ",".join(f'{key}="{value}"' for key, value in labels.items())
    series.append({"series": f"{name}{{{selector}}}", "values": values})


def firing(uid, time, labels):
    """labels are the series the rule's condition selects at time; others must not appear."""
    rule = rules[uid]
    expression = rule["data"][0]["model"]["expr"]
    threshold = rule["data"][1]["model"]["conditions"][0]["evaluator"]["params"][0]
    samples = [{"labels": label, "value": 1} for label in labels]
    cases.append({"expr": f"({expression}) > bool {threshold} == 1", "eval_time": time, "exp_samples": samples})


# The promtool clock starts at 0, so a last success of 0 means "succeeded at the start".
# LH in prod succeeded at the start and then stopped. SH in prod keeps succeeding. GH never succeeded (NaN).
# dev LH is always stale so a leak of dev into prod shows up as an extra sample.
for env in ["dev", "prod"]:
    tags = dict(job="backend", instance=env, environment=env)
    metric("toadzip_ingest_auto_last_success_seconds", "0x300", **tags, type="ANNOUNCEMENT_SYNC")
    metric("toadzip_ingest_auto_last_success_seconds", "0+60x300", **tags, type="SH_ANNOUNCEMENT_COLLECTION")
    metric("toadzip_ingest_auto_last_success_seconds", " ".join(["NaN"] * 301), **tags, type="GH_ANNOUNCEMENT_COLLECTION")
    metric("toadzip_ingest_auto_failure_streak", "0x300", **tags, type="SH_ANNOUNCEMENT_COLLECTION")
    metric("toadzip_ingest_auto_failure_streak", "1x60 2x60 3x180", **tags, type="GH_ANNOUNCEMENT_COLLECTION")

STALE, FAILING = "toadzip-ingest-auto-stale", "toadzip-ingest-auto-failing"
lh = [f'{{environment="{env}",type="ANNOUNCEMENT_SYNC"}}' for env in ["dev", "prod"]]
gh = [f'{{environment="{env}",type="GH_ANNOUNCEMENT_COLLECTION"}}' for env in ["dev", "prod"]]
firing(STALE, "2h59m", [])
firing(STALE, "3h1m", lh)
firing(FAILING, "1h59m", [])
firing(FAILING, "2h3m", gh)

tests = [{"name": "Announcement collection alerts fire per environment and type", "interval": "1m",
          "input_series": series, "promql_expr_test": cases}]
print(json.dumps({"rule_files": [], "evaluation_interval": "1m", "tests": tests}, indent=2))
