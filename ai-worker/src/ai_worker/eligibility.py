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
_SPACES = re.compile(r"\s+")
_AMOUNT = re.compile(r"(?:(\d+)억)?(?:(\d+)만)?(?:(\d+)천)?(\d+)?원")
_LIMIT_SUFFIX = re.compile(r"(?:이하|미만|이내|까지)$")
# 민법상 성년(만 19세)은 원문이 나이를 숫자로 적지 않는다.
_ADULT = re.compile(r"성년")
_PREVIOUS_YEAR = re.compile(r"전년도|전년|직전\s*연도|직전년도")
_THIS_YEAR = re.compile(r"당해\s*연도|금년도|올해|해당\s*연도")
_TARGET_PATH = re.compile(r"eligibility\.targets\[(\d+)\]")


@dataclass(frozen=True)
class Extracted:
    """data는 결과의 data.eligibility, evidence는 그 경로의 근거다. conditions·condition_evidence는 applicationCondition 합치기용이다."""

    data: dict | None
    evidence: list[dict] = field(default_factory=list)
    conditions: list[dict] = field(default_factory=list)
    condition_evidence: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def normalize(reply: dict, posted_date: object) -> Extracted:
    """reply는 신청자격 단계 응답(data.eligibility, evidence), posted_date는 모집공고일(YYYY-MM-DD)이다."""
    source = _source(reply)
    quotes = {item.get("path"): _text(item.get("quote")) for item in checks.objects(reply.get("evidence"))}
    warnings: list[str] = []
    year = _posted_year(posted_date)
    common = _group(source.get("common"), "eligibility.common", quotes, year, warnings)
    targets = []
    for index, item in enumerate(checks.objects(source.get("targets"))):
        name = _text(item.get("target")).strip()
        if not name:
            warnings.append(f"eligibility.targets[{index}]: 대상 이름이 없어 뺐습니다.")
            continue
        supply_target = _text(item.get("supplyTarget")).strip() or name
        targets.append({"target": name, "supplyTarget": supply_target,
                        **_group(item, f"eligibility.targets[{index}]", quotes, year, warnings)})
    exclusions = [{"title": _limited(item.get("title")), "body": _limited(item.get("body"))}
                  for item in checks.objects(source.get("exclusions")) if _text(item.get("title")).strip()]
    data = {"common": common, "targets": targets, "exclusions": exclusions}
    evidence = _evidence_for(reply, data)
    conditions, condition_evidence = _conditions(data, evidence)
    return Extracted(data, evidence, conditions, condition_evidence, warnings)


def failed(reason: str) -> Extracted:
    return Extracted(None, warnings=[f"신청자격을 읽지 못했습니다: {reason}"])


def _group(item: object, path: str, quotes: dict, year: int | None, warnings: list[str]) -> dict:
    group = item if isinstance(item, dict) else {}
    requirements = [
        _requirement(requirement, f"{path}.requirements[{index}]", quotes.get(f"{path}.requirements[{index}]", ""),
                     year, warnings, nested=False, quotes=quotes)
        for index, requirement in enumerate(checks.objects(group.get("requirements")))
    ]
    qualifications = [
        {"exactName": _limited(qualification.get("exactName")),
         "legalBasis": _limited(qualification.get("legalBasis")) or None,
         "conditionsAsWritten": _limited(qualification.get("conditionsAsWritten"))}
        for qualification in checks.objects(group.get("qualifications")) if _text(qualification.get("exactName")).strip()
    ]
    summary = _limited(group.get("summary")) or None
    return {"requirements": requirements, "qualifications": qualifications, "summary": summary}


def _requirement(item: dict, path: str, quote: str, year: int | None, warnings: list[str], nested: bool,
                 quotes: dict | None = None) -> dict:
    quotes = quotes or {}
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
    if kind == "FAMILY_TYPE":
        wanted = item.get("familyTypes") or []
        result["familyTypes"] = [value for value in FAMILY_TYPES if value in wanted]
    if kind == "ANY_OF":
        # 선택 요건은 제 근거가 있으면 그 인용으로, 없으면 묶음 근거로 숫자를 대조한다.
        result["options"] = [_requirement(option, f"{path}.options[{index}]",
                                          quotes.get(f"{path}.options[{index}]", quote), year, warnings, nested=True,
                                          quotes=quotes)
                             for index, option in enumerate(checks.objects(item.get("options")))]
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
    if _LIMIT_SUFFIX.sub("", _SPACES.sub("", written)) not in _SPACES.sub("", quote):
        warnings.append(f"{path}: 근거 인용에 없는 금액({written})이라 비웠습니다.")
        return None
    return value


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


def _evidence_for(reply: dict, data: dict) -> list[dict]:
    """남은 항목의 근거만 남긴다. 대상을 뺀 경우 순번이 바뀌므로 원래 대상 순번의 근거를 새 순번으로 옮긴다."""
    source_targets = checks.objects(_source(reply).get("targets"))
    kept = [index for index, item in enumerate(source_targets) if _text(item.get("target")).strip()]
    moves = {old: new for new, old in enumerate(kept)}
    valid = _paths(data)
    evidence = []
    for item in checks.objects(reply.get("evidence")):
        path = _text(item.get("path"))
        target = _TARGET_PATH.match(path)
        if target:
            if int(target.group(1)) not in moves:
                continue
            path = f"eligibility.targets[{moves[int(target.group(1))]}]" + path[target.end():]
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
    """요약을 applicationCondition 합치기 형식으로 바꾼다. 요약의 근거는 그 묶음 첫 근거다."""
    groups = [(None, "eligibility.common", data["common"])] + [
        (target["target"], f"eligibility.targets[{index}]", target) for index, target in enumerate(data["targets"])]
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
