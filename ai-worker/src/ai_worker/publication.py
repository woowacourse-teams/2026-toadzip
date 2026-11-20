"""공개 후보 판정. 추출 결과를 원문과 대조해 자동 공개할 수 있는지와, 안 되면 운영자가 볼 사유를 정한다.

판단은 Jev가, 공개 여부는 이 모듈이 정한다. 질문 문구·선택지·기준값은 여기에만 두고 서버는 결론(reasons와 승인 값)만 읽는다.
질문 묶음마다 사람이 그 판단에 볼 쪽만 state로 보낸다. 공고문 전체를 보내면 Jev 입력 한도(질문당 32k 토큰)에 걸리고
판단과 무관한 쪽이 정확도를 낮춘다. 그래서 빠진 일정·자격은 인용된 쪽 안에서만 잡힌다. 공급표는 원문 행마다 따로 묻는다.
"""
import re
import time
from collections import Counter
from dataclasses import dataclass
from datetime import date

from ai_worker import jev, supply_sources

DATES = {"applicationStartDate": "신청 접수 시작일", "applicationEndDate": "신청 접수 종료일",
         "winnerAnnouncementDate": "최종 당첨자 또는 예비입주자 발표일"}
RENT = {"HAPPY_HOUSING": "행복주택", "NATIONAL_RENTAL": "국민임대", "PERMANENT_RENTAL": "영구임대",
        "PUBLIC_RENTAL": "분양전환 공공임대", "PUBLIC_RENTAL_50Y": "50년 공공임대", "INTEGRATED_PUBLIC_RENTAL": "통합공공임대",
        "REDEVELOPMENT_RENTAL": "재개발임대", "LONG_TERM_JEONSE": "장기전세"}
RECRUIT = {"NEW": "신규 당첨자 모집", "WAITLIST": "예비입주자 모집"}
CHECK = {"confirmed": "The supplied pages support the candidate, without exception or omission.",
         "conflict": "The candidate conflicts with the notice or misses a group, row, date, or exception.",
         "unclear": "Evidence is missing, ambiguous, or insufficient."}
UNCLEAR = {"unclear": "혼합 유형, 근거 부족 또는 해당 선택지 없음"}
ROW = {"represented": "Every recruited unit, target and number in this row occurs exactly once in the candidate.",
       "not_a_recruitment_row": "A header, subtotal, rent-only or detail row with no additional recruitment.",
       "conflict": "A recruitment row is missing, misassigned or double counted.",
       "unclear": "The meaning or coverage of this row is ambiguous."}
REJECTED = ("conflict", "unclear")
# 이 확률 이상인 답만 승인한다. 실측 정확도는 아직 없어 모의 판정 결과로 조정한다.
MIN_PROBABILITY = 0.95
# 처리 과정을 알리는 경고는 추출 결과의 결함이 아니다. 여기 없는 경고는 모두 보류 사유다.
PROCESS_NOTES = {supply_sources.AMBIGUOUS_TABLE_NOTE}
# 운영자에게 보일 미확인 항목 이름. 없는 경로는 경로 그대로 보인다.
FIELDS = {"housingTypes[].complexName": "주택형의 단지명", "housingTypes[].name": "주택형 이름",
          "housingTypes[].exclusiveArea": "전용면적", "housingTypes[].commonArea": "공용면적",
          "housingTypes[].constructedHouseholdCount": "건설호수", "targets[].label": "공급대상 이름",
          "cells[].winner": "모집 수", "cells[].reserve": "예비자 수", "cells[].rentalDeposit": "임대보증금",
          "cells[].monthlyRent": "월임대료", "leaseTerms[].rentalDeposit": "임대보증금",
          "leaseTerms[].monthlyRent": "월임대료", "leaseTerms[].convertedDeposit": "전환보증금"}


@dataclass
class Check:
    """Jev 요청 하나. pages는 이 판단에 사람이 볼 원문 쪽, labels는 질문 키별 보류 사유다."""
    name: str
    pages: list[int]
    state: dict
    questions: dict[str, dict]
    labels: dict[str, str]


def question(instructions, criteria):
    return {"type": "choice", "instructions": instructions, "criteria": criteria}


def assess(tree, texts, warnings, judge, deadline, title, reread, source_rows=None):
    data = tree["data"]
    cites, reasons = date_citations(tree, texts)
    reasons = held_by_extraction(data, texts, warnings) + reasons
    review = held(*reasons) | {"evidence": cites}
    reasons = review["reasons"]
    if judge is None:
        reasons.append("원문 대조 모델(Jev)을 쓸 수 없습니다.")
    if reasons:
        return review
    asker = Asker(judge, texts, reread, deadline, review["answers"])
    evidence = tree.get("evidence", [])
    for check in (kind_check(title, texts), date_check(data["announcement"], cites),
                  schedule_check(data, evidence, cites, texts),
                  supply_check(data["supplySheet"], source_rows or {}, texts)):
        reasons += asker.ask(check)
    if reasons:
        return review
    review["rentalType"] = review["answers"]["rentalType"]["choice"]
    review["recruitmentType"] = review["answers"]["recruitmentType"]["choice"]
    # 신청자격은 공고 공개를 막지 않는다. 확인되지 않으면 자격만 운영자 검수로 남는다.
    eligibility = eligibility_check(data, evidence, warnings, texts)
    review["eligibilityVerified"] = eligibility is not None and not asker.ask(eligibility)
    return review


def held(*reasons):
    """통과하지 못한 판정. 승인 값은 비어 있다."""
    return {"policy": 1, "reasons": list(reasons), "rentalType": None, "recruitmentType": None,
            "eligibilityVerified": False, "evidence": {}, "answers": {}}


def held_by_extraction(data, texts, warnings):
    """Jev에 묻기 전에 추출 단계가 이미 알린 보류 사유."""
    sheet = data["supplySheet"]
    reasons = [w for w in warnings if not w.startswith("신청자격") and w not in PROCESS_NOTES]
    reasons += unresolved(sheet.get("unresolvedFields", []))
    reasons += unresolved([f for f in data.get("unresolvedFields", [])
                           if (f.get("path") or "").startswith(("announcement.", "schedules", "supplySheet"))])
    if not all(sheet.get(key) for key in ("housingTypes", "cells", "quotas")):
        reasons.append("모집 주택형·공급대상·수량을 모두 확인할 수 없습니다.")
    if not texts or any(not text.strip() for text in texts):
        reasons.append("원문 전체의 텍스트를 확인할 수 없습니다.")
    return reasons


def unresolved(fields):
    """같은 항목·같은 이유는 한 줄로 묶는다. 주택형이 많은 공고는 같은 미확인이 수십 건 나온다."""
    counts = Counter((re.sub(r"\[\d+\]", "[]", f.get("path") or ""), f.get("reason")) for f in fields)
    return [f"{FIELDS.get(path, path)} {count}건 미확인: {reason}" for (path, reason), count in counts.items()]


def date_citations(tree, texts):
    """날짜마다 그 값을 뒷받침하는 인용이 해당 쪽 원문에 실제로 있어야 한다."""
    cites, reasons = {}, []
    for field, label in DATES.items():
        try:
            date.fromisoformat(tree["data"]["announcement"].get(field))
        except (ValueError, TypeError):
            reasons.append(f"{label} 값이 없습니다.")
            continue
        cite = next((c for c in tree.get("evidence", [])
                     if (c.get("path") or "").removeprefix("data.") == f"announcement.{field}"
                     and type(c.get("page")) is int and 1 <= c["page"] <= len(texts)
                     and isinstance(c.get("quote"), str) and c["quote"].strip()
                     and compact(c["quote"]) in compact(texts[c["page"] - 1])), None)
        if cite:
            cites[field] = cite
        else:
            reasons.append(f"{label}의 원문 쪽·인용을 확인할 수 없습니다.")
    return cites, reasons


def kind_check(title, texts):
    return Check("공고 성격", list(range(1, min(len(texts), 2) + 1)), {"noticeTitle": title}, {
        "notice": question("Is this the current original recruitment notice for `noticeTitle`? "
                           "Reject revisions, cancellations, guides and forms.", CHECK),
        "rentalType": question("Which single rental type does this notice recruit?", RENT | UNCLEAR),
        "recruitmentType": question("Which single recruitment type does this notice recruit?", RECRUIT | UNCLEAR),
    }, {"notice": "원공고인지 원문 확인 필요(정정·취소·안내문일 수 있음)",
        "rentalType": "공급유형을 원문에서 하나로 정하지 못했습니다.",
        "recruitmentType": "모집유형(신규·예비)을 원문에서 하나로 정하지 못했습니다."})


def date_check(notice, cites):
    questions = {field: question(f"Does {notice[field]} mean {label} for this notice, as cited in `citations.{field}`? "
                                 "Check year, all groups, schedules and footnotes.", CHECK)
                 for field, label in DATES.items()}
    labels = {field: f"{label} 원문 확인 필요 · {cites[field]['page']}쪽 {cites[field]['quote']}"
              for field, label in DATES.items()}
    return Check("접수·발표일", sorted({cite["page"] for cite in cites.values()}), {"citations": cites}, questions, labels)


def schedule_check(data, evidence, cites, texts):
    pages = cited_pages(evidence, ("schedules", "receptionPlace"), texts) | {cite["page"] for cite in cites.values()}
    return Check("일정·접수처", sorted(pages),
                 {"schedules": data.get("schedules"), "receptionPlaces": data.get("receptionPlaces")},
                 {"schedules": question("Do `schedules` and `receptionPlaces` accurately cover the schedule on the supplied "
                                        "pages, including deadlines, groups, time and exceptions?", CHECK)},
                 {"schedules": f"일정·접수처 원문 확인 필요 · {page_list(pages)}"})


def supply_check(sheet, source_rows, texts):
    """원문 행은 질문마다 그 글을 직접 싣는다. 같은 형명이 여러 쪽에 반복돼도 행마다 따로 판정된다."""
    row_pages = {row_id: int(row_id.split(":")[0][1:]) for row_id in source_rows}
    pages = cited_pages(sheet.get("evidence", []), ("",), texts) | {p for p in row_pages.values() if 1 <= p <= len(texts)}
    candidate = {key: value for key, value in sheet.items() if key not in ("unresolvedFields", "evidence")}
    questions = {"supply": question("Check every recruitment row, site, type, target, subtotal, quota and rent in `supply` "
                                    "against the supplied pages. Reject missing, duplicated or incorrect rows and group "
                                    "associations.", CHECK)}
    labels = {"supply": f"공급표 모집 행·수량 원문 확인 필요 · {page_list(pages)}"}
    for row_id, text in source_rows.items():
        questions[row_id] = question(f"Classify how `supply` covers this table row from page {row_pages[row_id]} "
                                     f"of the notice: {text}", ROW)
        labels[row_id] = f"공급표 {row_pages[row_id]}쪽 행 원문 확인 필요 · {text}"
    return Check("공급표", sorted(pages), {"supply": candidate}, questions, labels)


def eligibility_check(data, evidence, warnings, texts):
    eligibility = data.get("eligibility")
    unverified = any((f.get("path") or "").startswith("eligibility") for f in data.get("unresolvedFields", []))
    if not eligibility or unverified or any(w.startswith("신청자격") for w in warnings):
        return None
    return Check("신청자격", sorted(cited_pages(evidence, ("eligibility",), texts)), {"eligibility": eligibility},
                 {"eligibility": question("Check ALL conditions, thresholds, exceptions and target links in `eligibility` "
                                          "against the supplied pages. Is it complete and correct for public eligibility "
                                          "decisions?", CHECK)},
                 {"eligibility": "신청자격 원문 확인 필요"})


class Asker:
    """질문 묶음을 Jev에 묻고 보류 사유를 돌려준다. 뜻이 불명확한 답은 원본 PDF를 문서 내부 순서로 다시 읽어 한 번 더 묻는다."""

    def __init__(self, judge, texts, reread, deadline, answers):
        self.judge, self.texts, self.reread, self.deadline, self.answers = judge, texts, reread, deadline, answers
        self._alternate = None

    def ask(self, check):
        if not check.pages:
            return [f"{check.name}의 원문 쪽을 확인할 수 없습니다."]
        questions, texts, reasons = check.questions, self.texts, []
        for retried in (False, True):
            if self.deadline - time.monotonic() < 1:
                return reasons + [f"{check.name} 원문 대조 시간이 모자랍니다."]
            state = check.state | {"pages": {number: texts[number - 1] for number in check.pages}}
            try:
                answers = self.judge.choices(state, questions, self.deadline - time.monotonic())
            except jev.JevError:
                return reasons + [f"{check.name} 원문 대조 응답을 받지 못했습니다."]
            unclear = {}
            for key, prompt in questions.items():
                answer = answers[key]
                self.answers[key] = {"choice": answer.choice, "probability": answer.probability}
                if (answer.choice in prompt["criteria"] and answer.choice not in REJECTED
                        and answer.probability >= MIN_PROBABILITY):
                    continue
                if answer.choice == "conflict" or retried:
                    reasons.append(check.labels[key])
                else:
                    unclear[key] = prompt
            # 이미 보류가 정해졌으면 다시 읽지 않고 불명확한 항목도 사유로 남긴다.
            if reasons or not unclear:
                return reasons + [check.labels[key] for key in unclear]
            texts = self.alternate()
            if texts is None:
                return [f"{check.name}: 다시 읽을 원문을 모두 읽지 못했습니다."]
            questions = unclear
        return reasons

    def alternate(self):
        if self._alternate is None:
            pages = self.reread()
            complete = len(pages) == len(self.texts) and all(page.strip() for page in pages)
            self._alternate = pages if complete else []
        return self._alternate or None


def cited_pages(evidence, prefixes, texts):
    return {c["page"] for c in evidence if (c.get("path") or "").removeprefix("data.").startswith(prefixes)
            and type(c.get("page")) is int and 1 <= c["page"] <= len(texts)}


def page_list(pages):
    return ", ".join(map(str, sorted(pages))) + "쪽"


def compact(text):
    return re.sub(r"\s+", "", text)
