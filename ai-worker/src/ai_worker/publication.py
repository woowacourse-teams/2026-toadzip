"""공개 후보 판정. 원문이 잘리거나 모호하면 승인하지 않고 해당 항목과 근거를 남긴다."""
import json
import re
import time
from datetime import date

from ai_worker import jev

DATES = {"applicationStartDate": "신청 접수 시작일", "applicationEndDate": "신청 접수 종료일",
         "winnerAnnouncementDate": "최종 당첨자 또는 예비입주자 발표일"}
RENT = {"HAPPY_HOUSING": "행복주택", "NATIONAL_RENTAL": "국민임대", "PERMANENT_RENTAL": "영구임대",
        "PUBLIC_RENTAL": "분양전환 공공임대", "PUBLIC_RENTAL_50Y": "50년 공공임대", "INTEGRATED_PUBLIC_RENTAL": "통합공공임대",
        "REDEVELOPMENT_RENTAL": "재개발임대", "LONG_TERM_JEONSE": "장기전세"}
RECRUIT = {"NEW": "신규 당첨자 모집", "WAITLIST": "예비입주자 모집"}
CHECK = {"confirmed": "All relevant original pages support the candidate, without exception or omission.",
         "conflict": "The candidate conflicts with the notice or misses a group, row, date, or exception.",
         "unclear": "Evidence is missing, ambiguous, or insufficient."}
UNCLEAR = {"unclear": "혼합 유형, 근거 부족 또는 해당 선택지 없음"}
# 모의 판정부터 같은 기준으로 평가한다. 운영 기준의 실측 정확도는 별도로 측정한다.
MIN_PROBABILITY = 0.95
MAX_CONTEXT = 95000


def question(instructions, criteria):
    return {"type": "choice", "instructions": instructions, "criteria": criteria}


def assess(tree, texts, warnings, judge, deadline, title, reread):
    data = tree["data"]
    sheet = data["supplySheet"]
    notice = data["announcement"]
    reasons = [w for w in warnings if not w.startswith("신청자격")]
    reasons += [f"{f.get('path')}: {f.get('reason')}" for f in sheet.get("unresolvedFields", [])]
    for f in data.get("unresolvedFields", []):
        if f.get("path", "").startswith(("announcement.", "schedules", "supplySheet")):
            reasons.append(f"{f['path']}: {f.get('reason')}")
    if not all(sheet.get(key) for key in ("housingTypes", "cells", "quotas")):
        reasons.append("모집 주택형·공급대상·수량을 모두 확인할 수 없습니다.")
    if not texts or any(not text.strip() for text in texts):
        reasons.append("원문 전체의 텍스트를 확인할 수 없습니다.")
    evidence = {}
    questions = {}
    for field, label in DATES.items():
        value = notice.get(field)
        try:
            date.fromisoformat(value)
        except (ValueError, TypeError):
            reasons.append(f"{label} 값이 없습니다.")
            continue
        cite = next((c for c in tree.get("evidence", []) if c.get("path", "").removeprefix("data.") == f"announcement.{field}"
                     and type(c.get("page")) is int and 1 <= c["page"] <= len(texts)
                     and isinstance(c.get("quote"), str) and c["quote"].strip()
                     and compact(c["quote"]) in compact(texts[c["page"] - 1])), None)
        if not cite:
            reasons.append(f"{label}의 원문 쪽·인용을 확인할 수 없습니다.")
            continue
        evidence[field] = cite
        questions[field] = question(f"Does {value} mean {label} for this notice? Check year, all groups, schedules and footnotes.", CHECK)
    result = {"policy": 1, "reasons": reasons, "evidence": evidence, "answers": {}}
    if judge is None:
        reasons.append("JEV를 사용할 수 없습니다.")
    if reasons:
        return result
    questions.update({
        "notice": question("Is this the current original recruitment notice for the supplied title? Reject revisions, cancellations, guides and forms.", CHECK),
        "supply": question("Check every recruitment row, site, type, target, subtotal, quota and rent against the entire PDF. Reject missing, duplicated or incorrect rows and group associations.", CHECK),
        "schedules": question("Do the proposed schedules and reception places accurately cover the notice, including deadlines, groups, time and exceptions?", CHECK),
        "rentalType": question("Which single rental type does this notice recruit?", RENT | UNCLEAR),
        "recruitmentType": question("Which single recruitment type does this notice recruit?", RECRUIT | UNCLEAR),
    })
    context = {"sourcePages": dict(enumerate(texts, 1)), "noticeTitle": title, "supply": sheet,
               "schedule": data.get("schedules"), "receptionPlaces": data.get("receptionPlaces"), "citations": evidence}
    for attempt in range(2):
        if len(json.dumps(context, ensure_ascii=False)) > MAX_CONTEXT or deadline - time.monotonic() < 1:
            reasons.append("원문 전체 대조에 필요한 입력 또는 시간 한도를 초과했습니다.")
            break
        try:
            answers = judge.choices(context, questions, deadline - time.monotonic())
        except jev.JevError:
            reasons.append("JEV가 공개 근거를 확인하지 못했습니다.")
            break
        pending = {}
        for key, prompt in questions.items():
            answer = answers[key]
            result["answers"][key] = {"choice": answer.choice, "probability": answer.probability}
            if answer.choice in prompt["criteria"] and answer.choice not in ("conflict", "unclear") and answer.probability >= MIN_PROBABILITY:
                continue
            if answer.choice == "conflict" or attempt:
                cite = evidence.get(key, {})
                location = f" · {cite['page']}쪽 {cite['quote']}" if cite else ""
                reasons.append(f"{DATES.get(key, key)} 원문 확인 필요{location}")
            else:
                pending[key] = prompt
        if reasons or not pending:
            break
        # 첫 판정에서 의미가 불명확한 항목만 원본 PDF의 문서 내부 순서로 다시 읽는다.
        alternate = reread()
        if len(alternate) != len(texts) or any(not page.strip() for page in alternate):
            reasons.append("재검토할 원문을 모두 읽지 못했습니다.")
            break
        context["sourcePages"] = dict(enumerate(alternate, 1))
        questions = pending
    return result


def compact(text):
    return re.sub(r"\s+", "", text)
