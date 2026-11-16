"""Generate behavior cases from the AI job queue dashboard: environment isolation and failed scrapes."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
panels = {p["id"]: p for p in json.loads(
    (ROOT / "infra/grafana/dashboards/ai-jobs.json").read_text())["panels"]}
series, cases = [], []


def metric(name, values, **labels):
    selector = ",".join(f'{key}="{value}"' for key, value in labels.items())
    series.append({"series": f"{name}{{{selector}}}", "values": values})


def case(panel, value=None, labels="{}", ref="A", time="3m", others=()):
    """others are label sets of the same panel whose value differs from value."""
    expression = next(t["expr"] for t in panels[panel]["targets"] if t["refId"] == ref)
    expression = expression.replace("$environment", "prod")
    samples = []
    if value is not None:
        expression = f"abs(({expression}) - {value}) < bool 1e-8"
        samples = [{"labels": labels, "value": 1}] + [{"labels": other, "value": 0} for other in others]
    cases.append({"expr": expression, "eval_time": time, "exp_samples": samples})


# prod scrape stops after 5m. dev values are large so a leak into prod panels shows up.
for env, scale in [("dev", 100), ("prod", 1)]:
    tags = dict(job="backend", instance=env, environment=env)
    metric("up", "1x5 0x4" if env == "prod" else "1x9", **tags)
    for status, count in [("pending", 3), ("retrying", 1), ("running", 2)]:
        metric("toadzip_ai_jobs", f"{count * scale}x9", **tags, status=status)
    metric("toadzip_ai_jobs_oldest_wait_seconds", f"{720 * scale}x9", **tags)
    metric("toadzip_ai_jobs_recent", f"{2 * scale}x9", **tags, result="failed")
    metric("toadzip_ai_jobs_recent", f"{20 * scale}x9", **tags, result="succeeded")
    metric("toadzip_ai_jobs_processing_seconds", f"{26.5 * scale}x9", **tags, stat="average")

case(1, 4)
case(2, 2)
case(3, 720)
case(4, 2)
case(5, 2, '{status="running"}', others=('{status="pending"}', '{status="retrying"}'))
case(7, 26.5, '{stat="average"}')
for id in [1, 2, 3, 4, 5, 6, 7, 8]:
    case(id, time="7m")

tests = [{"name": "AI job queue is per environment and hidden when the scrape fails", "interval": "1m",
          "input_series": series, "promql_expr_test": cases}]
print(json.dumps({"rule_files": [], "evaluation_interval": "1m", "tests": tests}, indent=2))
