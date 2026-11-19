"""검수자가 원본 PDF와 비교한 작업별 CSV를 기관별로 집계한다. 모델 호출은 없다."""
import argparse
import csv
from collections import Counter, defaultdict

OUTCOMES = {"REVIEW", "WOULD_PUBLISH", "WOULD_CONFIRM", "PUBLISHED", "CONFIRMED"}


def summarize(rows):
    totals = defaultdict(Counter)
    seen = set()
    for row in rows:
        job = row["job_id"].strip()
        if not job or job in seen or row["outcome"] not in OUTCOMES:
            raise ValueError(f"잘못된 행 또는 중복 작업: {job}")
        seen.add(job)
        passed = row["outcome"] != "REVIEW"
        correct = row["correct"].strip()
        if correct not in ("yes", "no", ""):
            raise ValueError(f"{job}: correct는 yes, no, 또는 미검수인 빈 값이어야 합니다.")
        for group in (row["agency"], "전체"):
            totals[group]["total"] += 1
            totals[group]["review"] += not passed
            totals[group]["audited_pass"] += passed and bool(correct)
            totals[group]["wrong_pass"] += passed and correct == "no"
    return totals


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv")
    with open(parser.parse_args().csv, encoding="utf-8-sig", newline="") as source:
        totals = summarize(csv.DictReader(source))
    for agency, counts in sorted(totals.items()):
        passed = counts["total"] - counts["review"]
        reviewed = counts["audited_pass"]
        print(f"{agency} · 전체 {counts['total']} · 검수함 {counts['review']} ({counts['review']/counts['total']:.1%})"
              f" · 자동 통과 {passed} · 원문 대조 {reviewed} · 오통과 {counts['wrong_pass']} · 미검증 통과 {passed-reviewed}")
