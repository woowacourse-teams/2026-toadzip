"""공개 후보 판정. 추출 결과를 자동 공개할 수 있는지와, 안 되면 운영자가 볼 사유를 정한다.

판단은 Jev가, 공개 여부는 이 모듈이 정한다. 질문 문구·선택지·기준값은 여기에만 두고 서버는 결론(reasons와 승인 값)만 읽는다.
Jev에 "추출 결과가 다 맞는가"는 묻지 않는다. 그렇게 넓게 물으면 맞는 결과에도 '모순'이라 답한다(LH 표본 17건 모두).
사람이 한눈에 답할 수 있는 좁은 판단만 묻는다: 이 문서가 모집공고인가, 이 날짜는 어느 절차의 날짜인가,
추출이 인용하지 않은 이 표 행이 모집 행인가, 이 인용문을 만족하는 사람이 이 요건(사람이 읽는 문장)도 만족하는가.
값 대조(날짜 표현, 인용 여부, 요건의 숫자)는 코드가 한다.
수집 때 가진 값(collected)과 같은 날짜는 두 원천이 일치한 것이라 Jev에 묻지 않는다. 수집한 유형은 PDF와 대조하지 않고 그대로 쓴다.
일정·접수처도 Jev에 묻지 않는다. 추출 단계의 인용 확인과 미확인 표시만 본다.
"""
import re
import time
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date

from ai_worker import checks, jev, supply_assembly, supply_sources

DATES = {"applicationStartDate": "신청 접수 시작일", "applicationEndDate": "신청 접수 종료일",
         "winnerAnnouncementDate": "최종 당첨자 또는 예비입주자 발표일"}
# 날짜 필드가 가리켜야 하는 절차
DATE_STEP = {"applicationStartDate": "application", "applicationEndDate": "application",
             "winnerAnnouncementDate": "final_announcement"}
STEPS = {
    "application": "Applicants submit their housing application on this date or in a period that includes it (신청접수, 청약신청).",
    "shortlist_announcement": "The applicants who must hand in documents are announced (서류제출대상자 발표). The final result comes later.",
    "document_submission": "Shortlisted applicants hand in their documents (서류제출, 서류접수).",
    "final_announcement": "The final winners or reserve tenants are announced (당첨자 발표, 예비입주자 당첨발표).",
    "contract": "Winners sign the lease contract (계약체결).",
    "other": "Another event, such as the day the notice was posted, a model-house viewing, unit selection or move-in.",
}
RENT = {"HAPPY_HOUSING": "행복주택", "NATIONAL_RENTAL": "국민임대", "PERMANENT_RENTAL": "영구임대",
        "PUBLIC_RENTAL": "분양전환 공공임대 (5년·10년 공공임대, 리츠 포함)", "PUBLIC_RENTAL_50Y": "50년 공공임대",
        "INTEGRATED_PUBLIC_RENTAL": "통합공공임대", "REDEVELOPMENT_RENTAL": "재개발임대", "LONG_TERM_JEONSE": "장기전세"}
RECRUIT = {
    "NEW": "Tenants who get a home through this notice. This includes additional recruitment (추가모집) "
           "and notices where applicants pick a unit (동호지정).",
    "WAITLIST": "Only reserve tenants (예비입주자) who wait for a vacancy. No home is assigned through this notice.",
}
# 신청자격 요건의 뜻을 옮길 때 쓰는 말(meaning). 키는 워커 계약의 요건 값이다.
# 공고문 나이는 '만'을 적지 않아도 만 나이다(2023년 만 나이 통일). 적지 않으면 '6세 이하'를 세는 나이로 볼 수 있다며 망설였다.
ELIGIBILITY_DOCUMENT = ("Eligibility conditions from a Korean public rental housing recruitment notice. "
                        "Ages in these notices are in full years (만 나이), even when 만 is not written.")
OWNERS = {"APPLICANT": "the applicant", "HOUSEHOLD": "the applicant's household",
          "APPLICANT_AND_PARENTS": "the applicant and their parents together"}
INCOME_STANDARDS = {
    "URBAN_WORKER_MONTHLY_INCOME": "the average monthly income of urban worker households of the same size "
                                  "(도시근로자 가구원수별 가구당 월평균소득)",
    "STANDARD_MEDIAN_INCOME": "the standard median income (기준 중위소득)"}
FAMILIES = {"UNMARRIED": "not married (미혼)", "SINGLE_PARENT": "a single parent (한부모가족)",
            "COUPLE_OR_ENGAGED": "married, or planning to marry (신혼부부·예비신혼부부)"}
RAISED = {"CHILDBIRTH": "households with children born recently (출산가구)", "DUAL_INCOME": "dual-income households (맞벌이)"}
STATUSES = {"UNIVERSITY_STUDENT": "The applicant is a university student (대학생).",
            "JOB_SEEKER": "The applicant is a job seeker (취업준비생)."}
# Choice는 이 확률 이상인 답만, Noul은 YES 이상을 '예'로 NO 이하를 '아니오'로 읽는다. 그 사이는 판단 없음이라 보류한다.
# 유형은 고른 답이 그대로 공개 값이 된다. LH 표본 9건에서 맞는 답은 모두 0.96 이상이었다.
# 날짜의 절차는 고른 답이 기대한 절차와 같아야만 통과하므로 더 낮게 둔다. 표본에서 기대한 절차를 고른 답은 0.43·0.71·0.90·0.94와
# 0.95 이상이었고, 0.82·0.96·1.0으로 고른 다른 절차는 확률과 무관하게 보류된다. GH·SH와 부정 사례는 재지 못한 값이다.
TYPE_PROBABILITY = 0.9
STEP_PROBABILITY = 0.7
YES, NO = 0.8, 0.2
# Jev 요청 하나에 싣는 질문 수. 요청당 입력 한도(64k 토큰) 안에 들게 나눈다.
QUESTIONS_PER_REQUEST = 40
# 날짜의 절차를 물을 때 보일 표의 최대 글자 수. 일정표는 작다. 이보다 큰 표는 일정표가 아니다.
MAX_TABLE_CHARS = 6000
MAX_ROW_REASONS = 5
# 처리 과정을 알리는 경고는 추출 결과의 결함이 아니다. 여기 없는 경고는 모두 보류 사유다.
PROCESS_NOTES = {supply_sources.AMBIGUOUS_TABLE_NOTE}
# 운영자에게 보일 미확인 항목 이름. 없는 경로는 경로 그대로 보인다.
FIELDS = {"housingTypes[].complexName": "주택형의 단지명", "housingTypes[].name": "주택형 이름",
          "housingTypes[].exclusiveArea": "전용면적", "housingTypes[].commonArea": "공용면적",
          "housingTypes[].constructedHouseholdCount": "건설호수", "targets[].label": "공급대상 이름",
          "cells[].winner": "모집 수", "cells[].reserve": "예비자 수", "cells[].rentalDeposit": "임대보증금",
          "cells[].monthlyRent": "월임대료", "leaseTerms[].rentalDeposit": "임대보증금",
          "leaseTerms[].monthlyRent": "월임대료", "leaseTerms[].convertedDeposit": "전환보증금"}
# 공고에 적용되는 값의 미확인 표시. 신청자격의 미확인은 공고 공개를 막지 않는다.
UNRESOLVED_HOLDS = ("announcement.", "receptionPlaces", "supplySheet")
# 일정은 공고를 보류하지 않는다. 미확인이거나 근거가 원문에 없는 일정은 그 일정만 적용하지 않는다(skippedSchedules).
# 접수 기간과 당첨자 발표일은 일정 목록이 아니라 공고 날짜(announcement.)로 따로 확인한다.
SCHEDULE = re.compile(r"(?:data\.)?schedules(?:\[(\d+|\*)])?(?![\w\[])")
COMPLEX_NAME = re.compile(r"housingTypes\[\d+]\.complexName")


@dataclass(frozen=True)
class Source:
    """판정이 원문에서 보는 것. rows는 공급표 원문 행 ID(P쪽:T표:R행) → 행 글이고,
    tables는 원본 쪽 번호로 그 쪽의 표 칸을 돌려준다(수집값이 없는 날짜의 절차를 물을 때만 부른다)."""
    texts: list[str]
    rows: dict[str, str]
    tables: Callable[[int], list[list[list[str | None]]]]


@dataclass(frozen=True)
class Ask:
    """Jev 질문 하나와 그 답을 읽는 법. reason은 통과하지 못했을 때 운영자가 볼 사유다."""
    question: dict
    passes: Callable[[object], bool]
    reason: str


@dataclass(frozen=True)
class Check:
    """같은 state를 보는 질문 묶음. Jev 요청 하나(질문이 많으면 여러 개)가 된다."""
    name: str
    state: dict
    asks: dict[str, Ask]


@dataclass(frozen=True)
class Unanswerable:
    """Jev에 보일 원문을 마련하지 못해 묻지 않는 판단. 사유 그대로 보류한다."""
    reason: str


def is_yes(instructions, true, false, reason):
    return Ask({"type": "noul", "instructions": instructions, "criteria": {"true": true, "false": false}},
               lambda answer: answer >= YES, reason)


def is_no(instructions, true, false, reason):
    return Ask({"type": "noul", "instructions": instructions, "criteria": {"true": true, "false": false}},
               lambda answer: answer <= NO, reason)


def one_of(instructions, criteria, reason, minimum, expected=None):
    return Ask({"type": "choice", "instructions": instructions, "criteria": criteria},
               lambda answer: (answer.choice in criteria and answer.probability >= minimum
                               and expected in (None, answer.choice)), reason)


def assess(tree, source, warnings, judge, deadline, collected):
    """collected는 서버가 수집 때 가진 값이다: 있으면 rentalType·recruitmentType과 날짜 셋(DATES의 키)."""
    collected = known(collected)
    data = tree["data"]
    notice = data["announcement"]
    cites, reasons = date_citations(tree, source.texts)
    reasons = held_by_extraction(data, source.texts, warnings) + reasons + disagreements(notice, collected)
    # 예비자만 뽑는 공고로 수집됐으면 예비자 수가 곧 모집 수다. 유형을 모르면 Jev에 물은 뒤에 정한다.
    if collected.get("recruitmentType") not in (None, "NEW"):
        reasons += missing_reserve(data["supplySheet"])
    review = held(*reasons) | {"evidence": cites, "skippedSchedules": skipped_schedules(data, warnings)}
    reasons = review["reasons"]
    if judge is None:
        reasons.append("원문 대조 모델(Jev)을 쓸 수 없습니다.")
    if reasons:
        return review
    asker = Asker(judge, deadline, review["answers"])
    reasons += asker.ask(kind_check(collected, source.texts))
    if not collected.get("recruitmentType") and review["answers"].get("recruitmentType", {}).get("choice") != "NEW":
        reasons += missing_reserve(data["supplySheet"])
    for field in DATES:
        if not collected.get(field):
            reasons += asker.ask(date_check(field, date.fromisoformat(notice[field]), cites[field], source))
    reasons += supply_reasons(asker, data["supplySheet"], source.rows)
    if reasons:
        return review
    for field in ("rentalType", "recruitmentType"):
        review[field] = collected.get(field) or review["answers"][field]["choice"]
    # 신청자격은 공고 공개를 막지 않는다. 원문으로 뜻을 확인하지 못한 요건은 판정에서 빠지고 목록으로만 보인다.
    review["eligibilityPublishable"], review["unconfirmedRequirements"] = eligibility_review(tree, asker)
    return review


def known(collected):
    """수집값 가운데 이 판정이 아는 유형만 남긴다. 분류 목록 밖 유형(서버의 ETC '기타')은 수집이 분류하지 못한 것이라
    수집값이 없는 것으로 보고 원문에 묻는다. 서버는 기존 값이 ETC면 물어서 얻은 유형으로 덮는다."""
    choices = {"rentalType": RENT, "recruitmentType": RECRUIT}
    return {field: value for field, value in collected.items() if field not in choices or value in choices[field]}


def missing_reserve(sheet):
    """표에 예비자 수가 없다는 미확인. 입주자를 뽑는 신규 모집 공고는 예비자 수 없이 확정하므로 사유가 아니고,
    예비자만 뽑는 공고에서는 모집 수가 없는 것이라 보류한다."""
    return unresolved([f for f in sheet.get("unresolvedFields", []) if f.get("reason") == supply_assembly.RESERVE_MISSING])


def held(*reasons):
    """통과하지 못한 판정. 승인 값은 비어 있다."""
    return {"policy": 1, "reasons": list(reasons), "rentalType": None, "recruitmentType": None,
            "eligibilityPublishable": False, "unconfirmedRequirements": [], "skippedSchedules": [], "evidence": {},
            "answers": {}}


def schedule_indices(path, count):
    """일정 경로가 가리키는 일정 번호들. 일정 경로가 아니면 None이다. `schedules[*]`(모든 일정)는 모든 번호이고,
    번호 없는 `schedules`는 추출이 넣지 않은 일정이라 뺄 번호가 없다."""
    match = SCHEDULE.match(path)
    if match is None:
        return None
    if match[1] == "*":
        return set(range(count))
    return {int(match[1])} if match[1] else set()


def skipped_schedules(data, warnings):
    """적용하지 않을 일정 번호. 추출이 미확인으로 남겼거나 근거 인용문이 원문에 없다고 경고한 일정이다."""
    paths = [f.get("path") or "" for f in data.get("unresolvedFields", [])]
    paths += [warning.partition(":")[0] for warning in warnings]
    count = len(data.get("schedules") or [])
    return sorted(set().union(*(schedule_indices(path, count) or set() for path in paths)))


def held_by_extraction(data, texts, warnings):
    """Jev에 묻기 전에 추출 단계가 이미 알린 보류 사유."""
    sheet = data["supplySheet"]
    reasons = [w for w in warnings if not w.startswith("신청자격") and w not in PROCESS_NOTES
               and schedule_indices(w.partition(":")[0], 0) is None]
    fields = [f for f in sheet.get("unresolvedFields", []) if f.get("reason") != supply_assembly.RESERVE_MISSING]
    # 단지 열이 없는 공급표(단지 하나만 모집하는 공고)는 모든 주택형의 단지명이 빈다. 서버가 공고에 연결된 단지 하나로 채우고,
    # 연결된 단지가 하나가 아니면 서버가 보류한다. 추출이 단지명을 냈는데 원문에서 확인되지 않아 비운 것은 단지 구분이 있는 표라 남긴다.
    if not any(row.get("complexName") for row in sheet.get("housingTypes", [])):
        fields = [f for f in fields if not (COMPLEX_NAME.fullmatch(f.get("path") or "")
                                            and f.get("reason") == supply_assembly.NO_SOURCE_VALUE)]
    reasons += unresolved(fields)
    reasons += unresolved([f for f in data.get("unresolvedFields", [])
                           if (f.get("path") or "").startswith(UNRESOLVED_HOLDS)])
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
    """날짜마다 근거 인용에 그 날짜가 적혀 있고, 적힌 그대로가 인용된 쪽 원문에 있어야 한다.
    인용문 전체를 쪽 글에서 찾지 않는다. 일정표는 쪽 글에서 칸 순서가 달라져 맞는 인용도 이어서는 나오지 않는다."""
    cites, reasons = {}, []
    for field, label in DATES.items():
        try:
            value = date.fromisoformat(tree["data"]["announcement"].get(field))
        except (ValueError, TypeError):
            reasons.append(f"{label} 값이 없습니다.")
            continue
        for cite in tree.get("evidence", []):
            if ((cite.get("path") or "").removeprefix("data.") != f"announcement.{field}"
                    or type(cite.get("page")) is not int or not 1 <= cite["page"] <= len(texts)
                    or not isinstance(cite.get("quote"), str)):
                continue
            written = written_date(cite["quote"], value)
            if written and checks.compact_text(written.group()) in checks.compact_text(texts[cite["page"] - 1]):
                cites[field] = cite | {"written": written.group()}
                break
        else:
            reasons.append(f"{label}의 원문 쪽·인용을 확인할 수 없습니다.")
    return cites, reasons


def written_date(text, value):
    """text에서 value를 가리키는 날짜 표현('26.11.17, 2026. 11. 17, 11월 17일)을 찾는다. 연도는 적혀 있을 때만 맞춰 본다.
    앞에 숫자나 구분자가 붙은 두 자리(11.03-11.05의 03)는 앞 날짜의 일이지 연도가 아니다."""
    pattern = (rf"(?:(?<![\d.\-/])(?:20)?(\d\d)\s*[.\-/년]\s*)?"
               rf"(?<!\d)0?{value.month}\s*[.\-/월]\s*0?{value.day}(?!\d)")
    return next((found for found in re.finditer(pattern, text)
                 if found.group(1) is None or int(found.group(1)) == value.year % 100), None)


def disagreements(notice, collected):
    """기관이 수집 때 준 날짜와 PDF 날짜가 다르면 어느 쪽이 맞는지 사람이 본다."""
    return [f"{label}: 수집한 값 {collected[field]}와 PDF의 {notice.get(field)}가 다릅니다."
            for field, label in DATES.items() if collected.get(field) and notice.get(field) not in (None, collected[field])]


def kind_check(collected, texts):
    """공고 성격. 앞 두 쪽이면 제목과 모집 내용이 드러난다.
    수집한 제목과 같은 공고인지는 묻지 않는다. 블록 이름이 비슷한 다른 단지의 공고문을 Jev가 같은 공고라 답했다(0.85)."""
    asks = {
        "recruits": is_yes(
            "Does the document in `pages` invite people to apply for rental housing?",
            "The document announces a recruitment of tenants or reserve tenants and says how or when to apply.",
            "The document is something else, such as an application form, a guide, a list of results, "
            "or a notice that only cancels a recruitment.",
            "입주자 모집공고문인지 확인 필요(안내문·서식·결과 발표일 수 있음)"),
        "cancelled": is_no(
            "Does the document in `pages` say that this recruitment is cancelled or withdrawn?",
            "The document states that the recruitment will not take place.",
            "The recruitment goes ahead, even if dates or details were corrected.",
            "모집 취소·철회 공고인지 확인 필요"),
    }
    programmes = ", ".join(RENT.values())
    if not collected.get("rentalType"):
        asks["rentalType"] = one_of("Which rental housing programme does the notice in `pages` recruit for?", RENT,
                                    "공급유형을 원문에서 하나로 정하지 못했습니다.", TYPE_PROBABILITY)
        asks["rentalListed"] = is_yes(
            f"Is the housing programme of the notice in `pages` one of these: {programmes}?",
            "The notice recruits for one of the listed programmes.",
            "The notice recruits for another programme, such as 매입임대 or 전세임대.",
            "공급유형이 분류 목록에 있는지 확인 필요")
        asks["mixedRental"] = is_no(
            "Does the notice in `pages` recruit for more than one rental housing programme at once?",
            "Homes of two or more programmes, such as 국민임대 and 영구임대, are recruited together.",
            "All homes in the notice belong to one programme.",
            "여러 공급유형을 함께 모집하는 공고인지 확인 필요")
    if not collected.get("recruitmentType"):
        asks["recruitmentType"] = one_of("Who does the notice in `pages` recruit?", RECRUIT,
                                         "모집유형(신규·예비)을 원문에서 하나로 정하지 못했습니다.", TYPE_PROBABILITY)
    return Check("공고 성격", {"pages": {str(number): text for number, text in enumerate(texts[:2], 1)}}, asks)


def date_check(field, value, cite, source):
    """수집값이 없는 날짜가 그 절차의 날짜인지, 그 날짜가 든 일정표를 보이고 묻는다.
    표로 읽지 못한 일정은 묻지 않는다. 쪽 글은 표 칸 순서가 섞여 사람도 날짜와 절차를 짝짓지 못한다
    (그렇게 물었을 때 발표일을 계약일이라고 0.96으로 답했다)."""
    needle = checks.compact_text(cite["written"])
    table = next((grid for grid in source.tables(cite["page"])
                  if any(cell and needle in checks.compact_text(cell) for row in grid for cell in row)
                  and sum(len(cell or "") for row in grid for cell in row) <= MAX_TABLE_CHARS), None)
    if table is None:
        return Unanswerable(f"{DATES[field]} {value.isoformat()}을 일정표에서 찾지 못해 어느 절차의 날짜인지 확인할 수 없습니다"
                            f" · {cite['page']}쪽 {cite['quote']}")
    return Check(DATES[field], {"schedule": [[cell or "" for cell in row] for row in table]}, {field: one_of(
        f"According to the table rows in `schedule`, which step of the application process takes place on "
        f"{cite['written']}?", STEPS,
        f"{DATES[field]} {value.isoformat()}이 어느 절차의 날짜인지 원문 확인 필요 · {cite['page']}쪽 {cite['quote']}",
        STEP_PROBABILITY, DATE_STEP[field])})


def supply_reasons(asker, sheet, rows):
    """빠졌을 수 있는 행이 많으면 앞의 몇 줄만 보인다. 표의 뒤쪽이 통째로 빠지면 같은 사유가 수십 줄이 된다."""
    reasons = asker.ask(supply_check(sheet, rows))
    if len(reasons) > MAX_ROW_REASONS:
        reasons = reasons[:MAX_ROW_REASONS] + [f"공급표 행 {len(reasons) - MAX_ROW_REASONS}건 더 확인 필요"]
    return reasons


def supply_check(sheet, rows):
    """추출이 인용하지 않은 공급표 행이 모집 행이면 빠진 행일 수 있다. 수량은 추출 단계가 원문 칸과 이미 대조했다.
    인용된 행이 하나도 없는 표는 묻지 않는다. rows에는 일정표·소득표처럼 공급표가 아닌 표도 있어(표본 9건 중 8건) 그 표들을
    물으면 거의 모든 공고가 보류된다. 통째로 빠진 공급표는 추출 단계의 주택형 누락 경고와 서버의 공고 단지 누락 검사가 잡는다."""
    quotes = [checks.compact_text(item.get("quote") or "") for item in sheet.get("evidence", [])]
    written = {row_id: checks.compact_text(text) for row_id, text in rows.items()}
    cited = {row_id for row_id, text in written.items() if text and any(text in quote for quote in quotes)}
    if not cited:
        return Unanswerable("공급표 근거를 원문 표 행에서 찾지 못해 빠진 행이 없는지 확인할 수 없습니다.")
    first = {}
    for row_id in cited:
        table, row = table_row(row_id)
        first[table] = min(first.get(table, row), row)
    asks = {}
    for row_id, text in rows.items():
        table, row = table_row(row_id)
        # 인용된 행이 있는 표만 공급표로 본다. 첫 인용 행 앞은 머리글이다.
        if row_id in cited or table not in first or row <= first[table] or not written[row_id]:
            continue
        headers = [rows[other] for other in rows if table_row(other)[0] == table and table_row(other)[1] < first[table]]
        page = table.split(":")[0][1:]
        asks[row_id] = is_no(
            {"question": "Does this table row name a housing type and give a number of households recruited for it?",
             "table_headers": headers[-4:], "row": text},
            "The row names a housing type (such as 26A or 36형) and gives how many households or reserve tenants "
            "are recruited for it.",
            "The row is a subtotal or total, a note, or a continuation row that only gives rent, deposit, area "
            "or other details without its own recruitment count.",
            f"공급표 {page}쪽 행이 추출 결과에 빠졌을 수 있음 · {' '.join(text.split())[:80]}")
    return Check("공급표", {"document": "A supply table from a Korean public rental housing recruitment notice."}, asks)


def table_row(row_id):
    table, row = row_id.rsplit(":R", 1)
    return table, int(row)


def eligibility_review(tree, asker):
    """신청자격을 자동 공개해도 되는지와, 원문으로 뜻을 확인하지 못해 서버가 판정에 쓰지 않을 요건의 근거 경로.
    공개하지 않는 경우는 신청자격 추출이 없을 때(단계 실패)뿐이다. 원문 대조 응답을 받지 못한 요건도 확인하지 못한 요건이다.
    요건 하나의 경고(숫자 확인 필요, 근거 문구 없음 등)는 막지 않는다. 추출 단계가 그 요건의 숫자를 이미 비워
    서버가 판정하지 않는 목록으로 두기 때문이다. 근거나 옮길 값이 없는 요건은 묻지 않고 확인하지 못한 요건으로 둔다."""
    data = tree["data"]
    if not data.get("eligibility"):
        return False, []
    quotes = {(cite.get("path") or "").removeprefix("data."): cite.get("quote") for cite in tree.get("evidence", [])}
    pieces = {path: asked_parts(path, requirement) for path, requirement in requirements(data["eligibility"])}
    asks = {}
    for parts in pieces.values():
        questions = {key: (quotes.get(key), meaning(part)) for key, part in parts}
        # 선택지 하나라도 물을 수 없으면 그 선택 요건은 확인할 수 없으니 나머지 선택지도 묻지 않는다.
        if parts and all(isinstance(quote, str) and condition for quote, condition in questions.values()):
            asks |= {key: requirement_ask(quote, condition) for key, (quote, condition) in questions.items()}
    if asks:
        asker.ask(Check("신청자격", {"document": ELIGIBILITY_DOCUMENT}, asks))
    # 응답을 받지 못한 질문(Jev 실패·시간 부족)은 확인하지 못한 것이다. 검수를 기다리게 하지 않고 그 요건을 목록으로 내린 채 공개한다.
    confirmed = {key for key, ask in asks.items() if key in asker.answers and ask.passes(asker.answers[key]["noul"])}
    return True, [path for path, parts in pieces.items() if not parts or any(key not in confirmed for key, _ in parts)]


def requirements(eligibility):
    """서버가 판정에 쓸 수 있는 요건과 그 근거 경로(eligibility.targets[0].requirements[1]).
    서버 ExtractedEligibilityReader가 만드는 경로와 같다. 기타(OTHER)와 판정을 막는 UNRESOLVED는 서버가 판정하지 않아 뺀다."""
    groups = [("eligibility.common", eligibility.get("common") or {})]
    groups += [(f"eligibility.targets[{index}]", target) for index, target in enumerate(eligibility.get("targets") or [])]
    for group, node in groups:
        for index, requirement in enumerate(node.get("requirements") or []):
            if requirement.get("kind") not in ("OTHER", "UNRESOLVED"):
                yield f"{group}.requirements[{index}]", requirement


def asked_parts(path, requirement):
    """요건을 묻는 단위와 그 근거 경로. 선택 요건은 선택지마다 근거가 따로 있어 하나씩 묻고, '아래 중 하나' 같은 안내 문장은 묻지 않는다.
    서버는 선택지 하나라도 판정하지 못하면 그 선택 요건 전체를 목록으로 두므로, 선택지를 모두 확인해야 그 요건을 확인한 것이다."""
    if requirement.get("kind") != "ANY_OF":
        return [(path, requirement)]
    return [(f"{path}.options[{index}]", option) for index, option in enumerate(requirement.get("options") or [])]


def requirement_ask(quote, condition):
    """인용문을 만족하는 사람이 서버가 판정할 조건도 만족하는지 묻는다. 그러면 확인한 조건은 공고문이 받는 사람을 떨어뜨리지 않는다.
    조건이 인용문보다 넓은 것(혼인 중인 자를 '혼인 중이거나 예비신혼부부'로)은 통과한다. 판정 정밀도를 '한번 지원 넣어볼' 수준으로
    잡은 것과 같은 방향이다. 확인하지 못한 요건은 판정에서 빠지므로 확인한 쪽보다 판정이 더 넓다."""
    return is_yes(
        {"question": "Does every applicant who meets the condition in `quote` also meet `condition`?",
         "quote": quote, "condition": condition},
        "Anyone the quoted text accepts also meets `condition`. "
        "`condition` may use other words, or be looser than the quote.",
        "The quoted text accepts someone who fails `condition`: for example `condition` sets a stricter "
        "or opposite limit, is about a different person, or is about another matter.",
        "신청자격 요건의 뜻을 원문에서 확인하지 못했습니다.")


def meaning(requirement):
    """서버가 판정에 쓰는 요건의 뜻(종류·누구의 조건인지·주된 값)을 사람이 읽는 문장으로 옮긴다. 옮길 값이 없으면 None이다.
    코드가 만든 값(기준 연도, 원 단위 금액)과 내부 코드는 옮기지 않는다. 인용문에 없는 값이 보이면 맞는 요건에도 '아니오'라 답한다
    (LH 표본에서 소득·자산·자동차·가족 형태 요건이 그렇게 떨어졌다). 숫자가 원문과 같은지는 추출 단계 코드가 이미 대조했다."""
    kind, scope = requirement.get("kind"), requirement.get("scope")
    owner = OWNERS.get(scope)
    if kind == "AGE":
        return age(requirement)
    if kind == "NO_HOUSE" and scope == "APPLICANT":
        return "The applicant does not own a house (무주택자)."
    if kind == "NO_HOUSE" and scope == "HOUSEHOLD":
        return "No member of the applicant's household owns a house (무주택세대구성원)."
    if kind == "NO_CAR" and scope == "APPLICANT":
        return "The applicant does not own a car."
    if kind == "NO_CAR" and scope == "HOUSEHOLD":
        return "No member of the applicant's household owns a car."
    standard = INCOME_STANDARDS.get(requirement.get("incomeStandard"))
    if kind == "INCOME" and owner and standard and requirement.get("percent"):
        sentence = f"The monthly average income of {owner} is at most {requirement['percent']}% of {standard}."
        sizes = ["some household sizes"] if requirement.get("householdSizeExtraPercent") else []
        return sentence + raised(requirement, sizes)
    # 금액은 원문 표기로 묻는다. maximumKrw는 추출 단계가 amountAsWritten을 바꾼 값이라 둘은 함께 오거나 함께 빈다.
    amount = requirement.get("amountAsWritten")
    if kind == "ASSETS" and owner and amount and requirement.get("maximumKrw"):
        return f"The total assets of {owner} are at most {amount}." + raised(requirement, [])
    if kind == "CAR_VALUE" and owner and amount and requirement.get("maximumKrw"):
        return car_value(requirement, owner, amount)
    if kind == "RESIDENCE" and requirement.get("regions"):
        sentence = f"The applicant lives in {', '.join(requirement['regions'])}"
        months = requirement.get("minimumMonths")
        if months:
            span = f"{months} months"
            if months % 12 == 0:
                span = f"{months // 12} year" + ("s" if months > 12 else "")
            sentence += f" and has lived there for at least {span}"
        return sentence + "."
    if kind == "MARRIAGE_PERIOD" and requirement.get("maximum") is not None:
        return f"The applicant's marriage period (혼인기간) is {requirement['maximum']} years or less."
    families = [FAMILIES.get(value) for value in requirement.get("familyTypes") or []]
    if kind == "FAMILY_TYPE" and families and all(families):
        return f"The applicant is {' or '.join(families)}."
    return STATUSES.get(kind)


def car_value(requirement, owner, amount):
    """서버는 자동차가액을 차량 한 대씩(가족 차량 중 최고 가액) 판정한다. 공고문이 '개별 자동차가액'이라 쓰면 한 대씩으로 묻는다.
    '세대의 자동차가액'으로 물으면 합계로 읽어 '개별 자동차가액' 인용에 0.75였고(LH 표본 2건, 한 대씩은 0.92·0.94),
    '총자산 중 자동차가액' 인용에 한 대씩으로 물으면 0.31~0.39였다(같은 표본 5건, 세대의 자동차가액은 0.79~0.97).
    출산자녀 가산은 말하지 않는다. 기본 상한을 확인하면 서버가 높여 판정하는 기준도 만족하고, 자동차가액 인용문에는 가산이
    따로 적히지 않아 말하면 0.1~0.15 낮아졌다. 가산을 함께 적은 인용문은 확인되지 않고 목록으로 간다."""
    if "개별" in (requirement.get("asWritten") or ""):
        return f"Each car owned by {owner} is worth at most {amount} (개별 자동차가액)."
    return f"The car value (자동차가액) of {owner} is at most {amount}."


def age(requirement):
    """만 나이 범위. 성년(만 19세)을 숫자 대신 '성년자'로 적은 공고가 있어 그 뜻을 함께 적는다.
    막내 자녀 나이는 서버 입력이 '막내 생년월일 또는 출산예정일'이라 태아도 자녀로 센다. 상한만 있으면 공고문이 적는 대로
    'N세 이하 자녀가 있다'로 옮긴다. '막내가 N세 이하'로 물으면 '6세 이하 자녀(태아 포함)를 둔 자'에 '아니오'라 답했다(LH 표본 13건)."""
    low, high = requirement.get("minimum"), requirement.get("maximum")
    scope = requirement.get("scope")
    if scope not in ("APPLICANT", "YOUNGEST_CHILD") or (low is None and high is None):
        return None
    if scope == "YOUNGEST_CHILD" and low is None:
        return f"The applicant has a child aged {high} or younger in full years (만 나이). An unborn child also counts."
    if low is not None and high is not None:
        span = f"between {low} and {high}"
    elif low is not None:
        span = f"{low} or older"
    else:
        span = f"{high} or younger"
    if scope == "YOUNGEST_CHILD":
        return f"The applicant's youngest child, counting an unborn child, is aged {span} in full years (만 나이)."
    adult = " (19 is the age of adulthood, 성년, in Korea)" if low == 19 else ""
    return f"The applicant is aged {span} in full years (만 나이){adult}."


def raised(requirement, reasons):
    """서버가 판정에서 높여 주는 기준(가구원 수 가산, 출산자녀·맞벌이 가산)만 말한다. 장애인 가산처럼 서버가 기본 기준으로 판정하는
    가산은 말하지 않아, 인용문이 그런 가산을 적었으면 확인되지 않고 목록으로 간다."""
    kinds = [item.get("kind") for item in requirement.get("adjustments") or []]
    reasons = list(dict.fromkeys(reasons + [RAISED[kind] for kind in kinds if kind in RAISED]))
    if not reasons:
        return ""
    listed = reasons[0]
    if len(reasons) > 1:
        listed = ", ".join(reasons[:-1]) + " and " + reasons[-1]
    return f" The limit is higher for {listed}."


class Asker:
    """질문 묶음을 Jev에 묻고 통과하지 못한 질문의 사유를 돌려준다. 답은 answers에 남긴다."""

    def __init__(self, judge, deadline, answers):
        self.judge, self.deadline, self.answers = judge, deadline, answers

    def ask(self, check):
        if isinstance(check, Unanswerable):
            return [check.reason]
        reasons, keys = [], list(check.asks)
        for start in range(0, len(keys), QUESTIONS_PER_REQUEST):
            if self.deadline - time.monotonic() < 1:
                return reasons + [f"{check.name} 원문 대조 시간이 모자랍니다."]
            batch = keys[start:start + QUESTIONS_PER_REQUEST]
            try:
                answers = self.judge.ask(check.state, {key: check.asks[key].question for key in batch},
                                         self.deadline - time.monotonic())
            except jev.JevError:
                return reasons + [f"{check.name} 원문 대조 응답을 받지 못했습니다."]
            for key in batch:
                answer = answers[key]
                self.answers[key] = ({"noul": answer} if isinstance(answer, float)
                                     else {"choice": answer.choice, "probability": answer.probability})
                if not check.asks[key].passes(answer):
                    reasons.append(check.asks[key].reason)
        return reasons

