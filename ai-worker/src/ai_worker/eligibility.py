"""신청자격 단계의 모델 응답을 정리한다. 요건 kind와 칸을 닫힌 목록으로 맞추고, 숫자는 근거 인용에 실제로 있는 값만 남긴다.
금액은 원문 표기(amountAsWritten)를 원 단위(maximumKrw)로, 소득 기준 연도는 원문 표현(incomeYearAsWritten)을 연도(incomeBaseYear)로 코드가 바꾼다.
확인하지 못한 숫자는 비우고 경고로 알린다. 서버는 빈 숫자를 판정하지 않고 직접 확인으로 남긴다.
묶음 요약(summary)은 공급대상 신청 조건(applicationCondition)에 합칠 수 있게 target·condition 목록으로도 돌려준다."""

import re
from dataclasses import dataclass, field

from ai_worker import checks

KINDS = frozenset({"AGE", "NO_HOUSE", "INCOME", "ASSETS", "CAR_VALUE", "NO_CAR", "RESIDENCE", "MARRIAGE_PERIOD",
                   "FAMILY_TYPE", "UNIVERSITY_STUDENT", "JOB_SEEKER", "ANY_OF", "OTHER"})
SCOPES = frozenset({"APPLICANT", "HOUSEHOLD", "APPLICANT_AND_PARENTS", "YOUNGEST_CHILD"})
INCOME_STANDARDS = frozenset({"URBAN_WORKER_MONTHLY_INCOME", "STANDARD_MEDIAN_INCOME"})
FAMILY_TYPES = ("UNMARRIED", "SINGLE_PARENT", "COUPLE_OR_ENGAGED")
ADJUSTMENTS = frozenset({"CHILDBIRTH", "DUAL_INCOME", "DISABILITY", "OTHER"})
_TEXT_LIMIT = 500
_NUMBERS = ("minimum", "maximum", "percent", "householdSizeExtraPercent", "maximumKrw", "minimumMonths")
_SPACES = re.compile(r"\s+")
_AMOUNT = re.compile(r"(?:(\d+)억)?(?:(\d+)만)?(?:(\d+)천)?(\d+)?원")
_LIMIT_SUFFIX = re.compile(r"(?:이하|미만|이내|까지)$")
# 민법상 성년(만 19세)은 원문이 나이를 숫자로 적지 않는다.
_ADULT = re.compile(r"성년")
_PREVIOUS_YEAR = re.compile(r"전년도|전년|직전\s*연도|직전년도")
_THIS_YEAR = re.compile(r"당해\s*연도|금년도|올해|해당\s*연도")


@dataclass(frozen=True)
class Extracted:
    """data는 결과의 data.eligibility, evidence는 그 경로의 근거다. conditions·condition_evidence는 applicationCondition 합치기용이다."""

    data: dict | None
    evidence: list[dict] = field(default_factory=list)
    conditions: list[dict] = field(default_factory=list)
    condition_evidence: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def normalize(reply: dict, posted_date: object, page_texts: dict[int, str] | None = None) -> Extracted:
    """reply는 신청자격 단계 응답(data.eligibility, evidence), posted_date는 모집공고일(YYYY-MM-DD)이다.
    page_texts(원본 쪽 번호 → 글)는 인용이 짧아 담지 못하는 보조 숫자(가구원 가산, 거주 개월)를 근거 쪽에서 대조하는 데 쓴다."""
    source = _source(reply)
    raw_evidence = _raw_evidence(reply)
    page_texts = page_texts or {}
    quotes = {item["path"]: (_text(item.get("quote")), page_texts.get(item.get("page"), "")) for item in raw_evidence}
    warnings: list[str] = []
    year = _posted_year(posted_date)
    # 빼는 항목이 있으면 뒤 항목의 근거 순번이 달라지므로, 원래 경로 → 새 경로를 모아 근거를 옮긴다.
    moves: dict[str, str] = {}
    common = _group(source.get("common"), "eligibility.common", "eligibility.common", quotes, year, warnings, moves)
    targets = []
    for index, item in enumerate(checks.objects(source.get("targets"))):
        name = _text(item.get("target")).strip()
        if not name:
            warnings.append(f"eligibility.targets[{index}]: 대상 이름이 없어 뺐습니다.")
            continue
        supply_target = _text(item.get("supplyTarget")).strip() or name
        old, new = f"eligibility.targets[{index}]", f"eligibility.targets[{len(targets)}]"
        targets.append({"target": name, "supplyTarget": supply_target,
                        **_group(item, old, new, quotes, year, warnings, moves)})
    exclusions = []
    for index, item in enumerate(checks.objects(source.get("exclusions"))):
        if _text(item.get("title")).strip():
            moves[f"eligibility.exclusions[{index}]"] = f"eligibility.exclusions[{len(exclusions)}]"
            exclusions.append({"title": _limited(item.get("title")), "body": _limited(item.get("body"))})
    data = {"common": common, "targets": targets, "exclusions": exclusions}
    evidence = _evidence_for(raw_evidence, data, moves)
    conditions, condition_evidence = _conditions(data, evidence)
    return Extracted(data, evidence, conditions, condition_evidence, warnings)


def failed(reason: str) -> Extracted:
    return Extracted(None, warnings=[f"신청자격을 읽지 못했습니다: {reason}"])


def _group(item: object, path: str, new_path: str, quotes: dict, year: int | None, warnings: list[str],
           moves: dict[str, str]) -> dict:
    """path는 모델 응답의 원래 경로(근거·경고), new_path는 결과의 경로다. 남긴 요건·자격의 원래 경로 → 새 경로를 moves에 둔다."""
    group = item if isinstance(item, dict) else {}
    requirements = [
        _requirement(requirement, f"{path}.requirements[{index}]", quotes.get(f"{path}.requirements[{index}]", ("", "")),
                     year, warnings, nested=False, quotes=quotes)
        for index, requirement in enumerate(checks.objects(group.get("requirements")))
    ]
    for index, requirement in enumerate(requirements):
        moves[f"{path}.requirements[{index}]"] = f"{new_path}.requirements[{index}]"
        for option in range(len(requirement.get("options", []))):
            moves[f"{path}.requirements[{index}].options[{option}]"] = f"{new_path}.requirements[{index}].options[{option}]"
    qualifications = []
    for index, qualification in enumerate(checks.objects(group.get("qualifications"))):
        if _text(qualification.get("exactName")).strip():
            moves[f"{path}.qualifications[{index}]"] = f"{new_path}.qualifications[{len(qualifications)}]"
            qualifications.append({"exactName": _limited(qualification.get("exactName")),
                                   "legalBasis": _limited(qualification.get("legalBasis")) or None,
                                   "conditionsAsWritten": _limited(qualification.get("conditionsAsWritten"))})
    summary = _limited(group.get("summary")) or None
    return {"requirements": requirements, "qualifications": qualifications, "summary": summary}


def _requirement(item: dict, path: str, source: tuple[str, str], year: int | None, warnings: list[str], nested: bool,
                 quotes: dict | None = None) -> dict:
    """source는 (근거 인용, 근거 쪽 글)이다. 한 숫자라도 대조에 실패하면 그 요건의 숫자를 모두 비운다.
    나이 한쪽만 남기면 서버가 빈 쪽을 제한 없음으로 읽어 판정이 넓어지기 때문이다."""
    quotes = quotes or {}
    quote, page = source
    failed_before = len(warnings)
    kind = item.get("kind") if item.get("kind") in KINDS else "OTHER"
    if kind == "ANY_OF" and nested:
        kind = "OTHER"
    result = {"kind": kind, "asWritten": _limited(item.get("asWritten"))}
    scope = item.get("scope") if item.get("scope") in SCOPES else None
    if kind in ("AGE", "NO_HOUSE", "INCOME", "ASSETS", "CAR_VALUE", "NO_CAR"):
        result["scope"] = scope
    if kind in ("AGE", "MARRIAGE_PERIOD"):
        for name in ("minimum", "maximum") if kind == "AGE" else ("maximum",):
            adult = kind == "AGE" and name == "minimum" and item.get(name) == 19 and _ADULT.search(quote + result["asWritten"])
            result[name] = 19 if adult else _quoted_number(item.get(name), quote, f"{path}.{name}", warnings)
    if kind == "INCOME":
        result["incomeStandard"] = item.get("incomeStandard") if item.get("incomeStandard") in INCOME_STANDARDS else None
        result["incomeBaseYear"] = _income_year(item.get("incomeYearAsWritten"), year, f"{path}.incomeYearAsWritten", warnings)
        result["percent"] = _quoted_number(item.get("percent"), quote, f"{path}.percent", warnings)
        result["householdSizeExtraPercent"] = [
            {"householdSize": extra["householdSize"], "percent": extra["percent"]}
            for extra in checks.objects(item.get("householdSizeExtraPercent"))
            if _whole(extra.get("householdSize")) and extra["householdSize"] >= 1 and _whole(extra.get("percent"))
        ]
        # 가구원 가산은 기본 비율에 더한 최종 비율(100 + 20 = 120)이 근거 인용이나 근거 쪽에 있어야 한다.
        for extra in result["householdSizeExtraPercent"]:
            if result["percent"] is not None and not _contains_percent(result["percent"] + extra["percent"], quote, page):
                warnings.append(f"{path}.householdSizeExtraPercent: 근거 쪽에 없는 가구원 가산 비율"
                                f"({extra['householdSize']}인 {result['percent'] + extra['percent']}%)이라 비웠습니다.")
    if kind in ("INCOME", "ASSETS", "CAR_VALUE"):
        result["adjustments"] = [
            {"kind": adjustment["kind"], "asWritten": _limited(adjustment.get("asWritten"))}
            for adjustment in checks.objects(item.get("adjustments")) if adjustment.get("kind") in ADJUSTMENTS
        ]
    if kind in ("ASSETS", "CAR_VALUE"):
        written = _text(item.get("amountAsWritten")).strip()
        result["amountAsWritten"] = written or None
        result["maximumKrw"] = _quoted_amount(written, quote, f"{path}.amountAsWritten", warnings)
    if kind == "RESIDENCE":
        result["regions"] = [region.strip() for region in item.get("regions") or [] if isinstance(region, str) and region.strip()]
        months = item.get("minimumMonths")
        result["minimumMonths"] = months if _whole(months) and months >= 0 else None
        if months is not None and result["minimumMonths"] is None:
            warnings.append(f"{path}.minimumMonths: 개월 수가 아닌 거주기간({months})이라 비웠습니다.")
        elif result["minimumMonths"] is not None and not _contains_months(months, quote, page):
            warnings.append(f"{path}.minimumMonths: 근거 쪽에 없는 거주기간({months}개월)이라 비웠습니다.")
    if kind == "FAMILY_TYPE":
        wanted = item.get("familyTypes") or []
        result["familyTypes"] = [value for value in FAMILY_TYPES if value in wanted]
    if kind == "ANY_OF":
        # 선택 요건은 제 근거가 있으면 그 인용으로, 없으면 묶음 근거로 숫자를 대조한다.
        result["options"] = [_requirement(option, f"{path}.options[{index}]",
                                          quotes.get(f"{path}.options[{index}]", source), year, warnings, nested=True,
                                          quotes=quotes)
                             for index, option in enumerate(checks.objects(item.get("options")))]
    elif len(warnings) > failed_before:
        for name in _NUMBERS:
            if name in result:
                result[name] = [] if name == "householdSizeExtraPercent" else None
        # 거주기간을 비우고 지역만 남기면 서버가 '지역 거주'만 판정해 넓어지므로 지역도 비운다(직접 확인).
        if kind == "RESIDENCE":
            result["regions"] = []
    return result


def amount_krw(written: str) -> int | None:
    """원문 금액 표기(10,800만원, 3억 3,700만원, 37,080천원, 1억원)를 원으로 바꾼다. 읽을 수 없으면 None이다."""
    compact = _LIMIT_SUFFIX.sub("", _SPACES.sub("", written).replace(",", ""))
    match = _AMOUNT.fullmatch(compact)
    if not match or not any(match.groups()):
        return None
    eok, man, cheon, won = (int(part) if part else 0 for part in match.groups())
    return eok * 100_000_000 + man * 10_000 + cheon * 1_000 + won


def _quoted_amount(written: str, quote: str, path: str, warnings: list[str]) -> int | None:
    if not written:
        return None
    value = amount_krw(written)
    if value is None:
        warnings.append(f"{path}: 금액 표기({written})를 원 단위로 읽지 못했습니다.")
        return None
    amount = re.escape(_LIMIT_SUFFIX.sub("", _SPACES.sub("", written)))
    # 10,800만원 안의 800만원처럼 숫자 중간에서 맞는 것은 인정하지 않는다.
    # 공고문이 빈칸 양식처럼 '( 24,500 )만원'으로 적어도 같은 금액으로 본다.
    if not re.search(rf"(?<![\d,억]){amount}", re.sub(r"[()\s]", "", quote)):
        warnings.append(f"{path}: 근거 인용에 없는 금액({written})이라 비웠습니다.")
        return None
    return value


def _contains_percent(value: int, quote: str, page: str) -> bool:
    """짧은 인용은 숫자만, 쪽 글은 단위가 붙은 비율(120%, 120퍼센트)만 인정한다. 쪽 글의 '1순위' 같은 숫자로 통과하지 않게 한다."""
    return (str(value) in re.findall(r"\d+", quote.replace(",", ""))
            or re.search(rf"(?<!\d){value}\s*(?:%|퍼센트)", page) is not None)


def _contains_months(months: int, quote: str, page: str) -> bool:
    """거주기간은 'N개월', 'Y년', 'Y년 M개월'처럼 단위가 붙은 형태만 인정한다."""
    years, rest = divmod(months, 12)
    patterns = [rf"(?<!\d){months}\s*개월"]
    if years and not rest:
        patterns.append(rf"(?<!\d){years}\s*년")
    if years and rest:
        patterns.append(rf"(?<!\d){years}\s*년\s*{rest}\s*개월")
    return any(re.search(pattern, text) for pattern in patterns for text in (quote, page))


def _quoted_number(value: object, quote: str, path: str, warnings: list[str]) -> int | None:
    if value is None:
        return None
    if not _whole(value) or value < 0:
        warnings.append(f"{path}: 숫자가 아닌 값이라 비웠습니다.")
        return None
    if str(value) not in re.findall(r"\d+", quote.replace(",", "")):
        warnings.append(f"{path}: 근거 인용에 없는 숫자({value})라 비웠습니다.")
        return None
    return value


def _income_year(written: object, posted_year: int | None, path: str, warnings: list[str]) -> int | None:
    text = _text(written).strip()
    if not text:
        return None
    digits = re.search(r"(?<!\d)(20\d{2})(?!\d)", text)
    if digits:
        return int(digits.group(1))
    if posted_year is not None and _PREVIOUS_YEAR.search(text):
        return posted_year - 1
    if posted_year is not None and _THIS_YEAR.search(text):
        return posted_year
    warnings.append(f"{path}: 소득 기준 연도({text})를 정하지 못했습니다.")
    return None


def _posted_year(posted_date: object) -> int | None:
    match = re.match(r"(20\d{2})-\d{2}-\d{2}$", _text(posted_date))
    return int(match.group(1)) if match else None


def _raw_evidence(reply: dict) -> list[dict]:
    """모델은 근거를 최상위 evidence 대신 data.evidence에 두거나, 경로 앞에 data.·$.를 붙이기도 한다. 둘 다 받는다."""
    data = reply.get("data") if isinstance(reply.get("data"), dict) else {}
    items = []
    for item in [*checks.objects(reply.get("evidence")), *checks.objects(data.get("evidence"))]:
        path = re.sub(r"^\$\.?|^data\.", "", _text(item.get("path")).strip())
        if path.startswith("eligibility."):
            items.append({**item, "path": path})
    return items


def _evidence_for(raw_evidence: list[dict], data: dict, moves: dict[str, str]) -> list[dict]:
    """남은 항목의 근거만 새 경로로 옮겨 남긴다. 뺀 항목의 근거는 버린다."""
    valid = _paths(data)
    evidence = []
    for item in raw_evidence:
        path = moves.get(item["path"])
        if path in valid:
            evidence.append({**item, "path": path})
    return checks.deduplicate(evidence)


def _paths(data: dict) -> set[str]:
    paths = set()
    groups = [("eligibility.common", data["common"])] + [
        (f"eligibility.targets[{index}]", target) for index, target in enumerate(data["targets"])]
    for path, group in groups:
        paths |= {f"{path}.requirements[{index}]" for index in range(len(group["requirements"]))}
        paths |= {f"{path}.requirements[{index}].options[{option}]" for index, requirement in enumerate(group["requirements"])
                  for option in range(len(requirement.get("options", [])))}
        paths |= {f"{path}.qualifications[{index}]" for index in range(len(group["qualifications"]))}
    paths |= {f"eligibility.exclusions[{index}]" for index in range(len(data["exclusions"]))}
    return paths


def _conditions(data: dict, evidence: list[dict]) -> tuple[list[dict], list[dict]]:
    """요약을 applicationCondition 합치기 형식으로 바꾼다. 대상은 그 경로가 속한 공급대상(supplyTarget)이라
    하위 경로(대학생 계층의 취업준비생)의 요약도 공급표 대상(대학생)에 붙는다. 요약의 근거는 그 묶음 첫 근거다."""
    groups = [(None, "eligibility.common", data["common"])] + [
        (target["supplyTarget"], f"eligibility.targets[{index}]", target) for index, target in enumerate(data["targets"])]
    conditions, condition_evidence = [], []
    for target, path, group in groups:
        if not group["summary"]:
            continue
        source = next((item for item in evidence if item["path"].startswith(path + ".")), None)
        if source:
            condition_evidence.append({**source, "path": f"applicationConditions[{len(conditions)}]"})
        conditions.append({"target": target, "condition": group["summary"]})
    return conditions, condition_evidence


def _source(reply: dict) -> dict:
    data = reply.get("data") if isinstance(reply.get("data"), dict) else {}
    return data["eligibility"] if isinstance(data.get("eligibility"), dict) else {}


def _whole(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _limited(value: object) -> str:
    return _SPACES.sub(" ", _text(value)).strip()[:_TEXT_LIMIT]
