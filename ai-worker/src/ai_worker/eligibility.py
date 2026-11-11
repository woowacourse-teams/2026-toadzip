"""신청자격 단계의 모델 응답을 서버가 읽는 계약(data.eligibility와 그 근거)으로 바꾼다.

모델은 요건을 한 번씩만 쓰고 신청 경로가 id로 가리키는 간결한 형식으로 답한다(출력 토큰이 추출 시간을 좌우한다).
이 모듈이 그것을 모든 경로의 공통 요건(common)과 경로별 요건(targets)으로 펼친다. 요건의 text는 원문 표기(asWritten)이자 근거 인용이다.

원문에서 확인한 것만 판정에 쓰이게 한다. text의 낱말이 모두 근거 쪽 글에 있어야 근거가 붙고(없으면 서버가 직접 확인으로 둔다),
주 숫자(나이·비율·금액·혼인기간)는 그 text 안에, 보조 숫자(가구원 수별 비율, 거주 개월)는 text나 근거 쪽 글에 단위와 함께 있어야 한다.
가구원 수 가산은 기준표의 다른 줄에 따로 적히기도 해서, 신청자격 쪽 어디든 '1인 20%p'처럼 가구원 수와 가산값이 짝으로 적혀 있어도 인정한다.
금액은 문구에 금액이 둘 이상이면 그 요건의 낱말(총자산, 자동차) 바로 뒤 금액이어야 하고, 근거 쪽에서 더 큰 금액의 일부가 아니어야 한다.
한 숫자라도 확인하지 못하면 그 요건의 숫자를 모두 비우고 경고로 알린다. 금액은 원 단위(maximumKrw)로, 소득 기준 연도는
원문 표현(전년도)을 모집공고일 기준 연도(incomeBaseYear)로 코드가 바꾼다.
경로 요약(summary)은 공급대상 신청 조건(applicationCondition)에 합칠 수 있게 target·condition 목록으로도 돌려준다.
"""

import copy
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
# 글 안의 금액 표기. 10,800만원 안의 800만원처럼 숫자 중간에서 시작하지 않는다.
_AMOUNT_IN_TEXT = re.compile(r"(?<![\d,억만천])(?:\d[\d,]*억)?(?:\d[\d,]*만)?(?:\d[\d,]*천)?(?:\d[\d,]*)?원")
_LIMIT_SUFFIX = re.compile(r"(?:이하|미만|이내|까지)$")
# 민법상 성년(만 19세)은 원문이 나이를 숫자로 적지 않는다.
_ADULT = re.compile(r"(?<!미)성년")
_PREVIOUS_YEAR = re.compile(r"전년도|전년|직전\s*연도|직전년도")
_THIS_YEAR = re.compile(r"당해\s*연도|금년도|올해|해당\s*연도")
# 가구원 수별 비율만 적은 원문에서 기본 비율로 볼 수 있는 가장 작은 가구원 수('3인 이상')
_BASE_HOUSEHOLD_SIZE = 3
# 가산값 짝('1인 10%p')이 무엇의 수인지 알려 주는 앞쪽 낱말. 출산자녀 수 가산은 가구원 수 가산이 아니다.
_EXTRA_LABEL = re.compile(r"세대원|가구원|자녀|출산|출생")
_EXTRA_LABEL_REACH = 80
# 모집 순위 한정 문구에 적힌 순위 숫자('1순위', '1·2순위', '1순위 및 2순위')와 한정하는 말
_RANKS_WRITTEN = re.compile(r"\d(?:\s*(?:[·ㆍ,/~]|및|와|과|또는)\s*\d)*\s*순위")
_RANK_LIMITING = re.compile(r"에\s*한\s*(?:합니다|하여|하며|함|해|정)|만\s*(?:을\s*)?(?:신청|모집|접수|공급|받)")
# 금액이 여럿 적힌 문구에서 그 요건의 금액 앞에 오는 낱말
_AMOUNT_WORDS = {"ASSETS": ("총자산", "자산"), "CAR_VALUE": ("자동차", "차량")}
# 모델 응답에서 요건이 빠졌을 때 그 자리에 두는 직접 확인 요건. 요건을 그냥 빼면 남은 요건만으로 판정되어 판정이 넓어진다.
_MISSING_REQUIREMENT = "모델 응답에서 빠진 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요."
_PER_ROUTE_REQUIREMENT = "신청 대상마다 다른 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요."


@dataclass(frozen=True)
class Extracted:
    """data는 결과의 data.eligibility, evidence는 그 경로의 근거(원본 쪽 번호)다. conditions·condition_evidence는 applicationCondition 합치기용이다."""

    data: dict | None
    evidence: list[dict] = field(default_factory=list)
    conditions: list[dict] = field(default_factory=list)
    condition_evidence: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def normalize(reply: dict, posted_date: object, source_pages: list[int], page_texts: dict[int, str]) -> Extracted:
    """reply는 신청자격 단계 응답(requirements, qualifications, routes, rankLimit, exclusions)이다.
    posted_date는 모집공고일(YYYY-MM-DD), source_pages는 첨부 순서대로의 원본 쪽 번호, page_texts는 원본 쪽 번호 → 글이다."""
    if not isinstance(reply.get("requirements"), list) or not isinstance(reply.get("routes"), list):
        return failed("AI 모델 응답의 신청자격 형식이 맞지 않습니다.")
    source = _Source(source_pages, page_texts, _posted_year(posted_date))
    warnings: list[str] = []
    answer = _Answer(reply, source, warnings)
    written = _routes(reply)
    routes = _recruited(written, reply.get("rankLimit"), source, warnings)
    named = [route for route in routes if route.name]
    evidence: list[dict] = []
    shared: list[str] = []
    if len(routes) == 1 and not named:
        # 대상별로 갈리지 않는 공고다. 요건·자격·요약이 모두 공고 전체에 적용된다.
        common = answer.group("eligibility.common", routes[0].requires, routes[0].qualifications, routes[0].summary, evidence)
    else:
        if len(named) < len(routes):
            warnings.append(f"신청자격: 이름이 없는 신청 경로 {len(routes) - len(named)}개를 뺐습니다.")
        # 경로가 둘 이상이면 모든 경로가 가리키는 요건이 공고 전체 요건이다. 경로가 하나면 그 대상의 요건으로 둔다(공급표에
        # 다른 대상이 있으면 서버가 그 대상을 '경로 없음'으로 알린다). 경로가 없으면 응답의 요건·자격 전부를 공고 전체로 본다.
        # 이름 없는 경로만 있으면 대상으로 나눌 수 없어, 그 경로들이 모두 가리킨 요건만 공고 전체 요건으로 둔다.
        if len(named) != 1:
            basis = named or routes
            shared = [name for name in answer.requirement_ids(basis) if all(name in route.requires for route in basis)]
        common = answer.group("eligibility.common", shared, [] if routes else answer.qualification_ids(), None, evidence)
        if not named and any(route.qualifications or set(route.requires) - set(shared) for route in routes):
            # 이름 없는 경로들의 서로 다른 요건은 실을 곳이 없다. 공통 요건만으로 충족되지 않게 직접 확인을 둔다.
            common["requirements"].append({"kind": "OTHER", "asWritten": _PER_ROUTE_REQUIREMENT})
    targets = [{"target": route.name, "supplyTarget": route.supply_target or route.name,
                **answer.group(f"eligibility.targets[{index}]", [name for name in route.requires if name not in shared],
                               route.qualifications, route.summary, evidence)}
               for index, route in enumerate(named)]
    answer.warn_unused(written)
    exclusions = []
    for item in checks.objects(reply.get("exclusions")):
        title = _limited(item.get("title"))
        if not title:
            continue
        body = _limited(item.get("body"))
        page = source.page_with(item, body or title)
        if page is not None:
            evidence.append({"path": f"eligibility.exclusions[{len(exclusions)}]", "page": page, "quote": body or title})
        exclusions.append({"title": title, "body": body})
    data = {"common": common, "targets": targets, "exclusions": exclusions}
    conditions, condition_evidence = _conditions(data, evidence)
    return Extracted(data, evidence, conditions, condition_evidence, list(dict.fromkeys(warnings)))


def failed(reason: str) -> Extracted:
    return Extracted(None, warnings=[f"신청자격을 읽지 못했습니다: {reason}"])


@dataclass(frozen=True)
class _Source:
    pages: list[int]
    texts: dict[int, str]
    posted_year: int | None

    def page_with(self, item: dict, text: str) -> int | None:
        """text의 낱말이 모두 있는 원본 쪽 번호. 항목이 가리킨 첨부 쪽을 먼저 보고, 거기 없으면 나머지 쪽을 앞에서부터 본다.
        모델은 첨부 순번 대신 원본 쪽 번호를 적기도 한다. 문구가 원문에 있는지가 근거이고 쪽 번호는 찾아갈 곳일 뿐이라, 문구가 실제로 있는 쪽을 근거 쪽으로 쓴다.
        낱말마다 찾는 기준은 마지막 근거 검사(checks.missing_words)와 같다."""
        if not text.split():
            return None
        attached = item.get("page")
        cited = [self.pages[attached - 1]] if _whole(attached) and 1 <= attached <= len(self.pages) else []
        for page in [*cited, *(page for page in self.pages if page not in cited)]:
            if not checks.missing_words(text, self.texts.get(page, "")):
                return page
        return None

    def states_size_extra(self, size: int, extra: int) -> bool:
        """신청자격 쪽 어디든 '1인 20%p', '1인 가구 20%p'처럼 가구원 수와 가산값이 짝으로 적혀 있는지.
        '2인 이상 20%p', 출산자녀 줄의 '1인 10%p'(출산자녀 수 가산)는 가구원 수 가산이 아니라 맞추지 않는다."""
        pattern = re.compile(rf"(?<!\d){size}인(가구)?{extra}%(?:[pP]|포인트)")
        for text in self.texts.values():
            compact = checks.compact_text(text)
            for match in pattern.finditer(compact):
                # '1인 10%p'는 출산자녀 줄에도 있다. 짝에 '가구'가 없으면 바로 앞 낱말이 세대원·가구원일 때만 가구원 수 가산이다.
                labels = _EXTRA_LABEL.findall(compact[max(0, match.start() - _EXTRA_LABEL_REACH):match.start()])
                if match[1] or (labels and labels[-1] in ("세대원", "가구원")):
                    return True
        return False


@dataclass(frozen=True)
class _Route:
    name: str
    supply_target: str
    rank: int | None
    requires: list[str]
    qualifications: list[str]
    summary: str | None


def _routes(reply: dict) -> list[_Route]:
    return [_Route(_limited(item.get("name")), _limited(item.get("supplyTarget")),
                   item.get("rank") if _whole(item.get("rank")) else None,
                   _names(item.get("requires")), _names(item.get("anyOfQualifications")),
                   _limited(item.get("summary")) or None)
            for item in checks.objects(reply.get("routes"))]


def _recruited(routes: list[_Route], limit: object, source: _Source, warnings: list[str]) -> list[_Route]:
    """이번 모집이 받지 않는 순위의 경로를 뺀다. 한정 문구가 원문 쪽에 있고, 그 문구에 적힌 순위가 ranks와 같고,
    한정하는 말('…에 한합니다', '…만 신청')이 있을 때만 뺀다. 순위 설명 문장이나 더 많은 순위를 받는다는 문장으로는 빼지 않는다.
    모델이 한정을 잘못 읽으면 정상 경로가 빠지므로, 확인하지 못하면 경로를 모두 남기고 경고한다."""
    if not isinstance(limit, dict):
        return routes
    ranks = [rank for rank in limit.get("ranks") or [] if _whole(rank)] if isinstance(limit.get("ranks"), list) else []
    text = _limited(limit.get("text"))
    written = {int(digit) for match in _RANKS_WRITTEN.finditer(text) for digit in re.findall(r"\d", match.group())}
    stated = bool(ranks) and written == set(ranks) and _RANK_LIMITING.search(text)
    if not stated or source.page_with(limit, text) is None:
        warnings.append("신청자격: 모집 순위 한정 문구를 원문에서 확인하지 못해 모든 순위의 신청 경로를 남겼습니다.")
        return routes
    kept = [route for route in routes if route.rank is None or route.rank in ranks]
    # 순위가 붙은 경로가 하나도 남지 않으면 모델이 경로의 순위를 잘못 붙인 것이다.
    if len(kept) < len(routes) and not any(route.rank is not None for route in kept):
        warnings.append("신청자격: 모집 순위 한정에 맞는 신청 경로가 없어 모든 순위의 경로를 남겼습니다.")
        return routes
    dropped = [route.name or "이름 없는 경로" for route in routes if route not in kept]
    if dropped:
        warnings.append(f"신청자격: 이번 모집은 {'·'.join(str(rank) for rank in ranks)}순위만 받아 다른 순위 경로를 뺐습니다"
                        f"({', '.join(dropped)}).")
    return kept


class _Answer:
    """모델 응답의 요건·자격을 id로 찾아 계약 모양으로 바꾼다. 같은 요건을 여러 경로가 가리켜도 한 번만 바꾼다."""

    def __init__(self, reply: dict, source: _Source, warnings: list[str]):
        self.source, self.warnings = source, warnings
        self.raw_requirements = _by_id(reply.get("requirements"))
        self.raw_qualifications = _by_id(reply.get("qualifications"))
        self.converted: dict[str, tuple[dict, list[dict | None]] | None] = {}

    def requirement_ids(self, routes: list[_Route]) -> list[str]:
        """경로가 가리키는 요건 id를 응답에 적힌 순서로. 경로가 없으면 다른 요건의 선택지가 아닌 요건 전부다."""
        if routes:
            return [name for name in self.raw_requirements if any(name in route.requires for route in routes)]
        return [name for name in self.raw_requirements if name not in self._option_ids()]

    def qualification_ids(self) -> list[str]:
        return list(self.raw_qualifications)

    def group(self, path: str, requirement_ids: list[str], qualification_ids: list[str], summary: str | None,
              evidence: list[dict]) -> dict:
        """묶음(공고 전체나 경로 하나)의 계약 값을 만들고, 원문에서 확인한 항목의 근거를 evidence에 더한다."""
        requirements, qualifications = [], []
        missing = {"kind": "OTHER", "asWritten": _MISSING_REQUIREMENT}
        for name in dict.fromkeys(requirement_ids):
            converted = self._requirement(name)
            if converted is None:
                self.warnings.append(f"신청자격: 경로가 가리킨 요건({name})이 응답에 없어 직접 확인으로 남겼습니다.")
                if missing not in requirements:
                    requirements.append(dict(missing))
                continue
            value, quotes = converted
            for suffix, quote in zip(["", *(f".options[{index}]" for index in range(len(quotes) - 1))], quotes):
                if quote:
                    evidence.append({"path": f"{path}.requirements[{len(requirements)}]{suffix}", **quote})
            # 같은 요건을 여러 경로가 가리켜도 결과의 묶음마다 따로 둔다(뒤 단계가 한 묶음만 고쳐도 다른 묶음이 바뀌지 않게).
            requirements.append(copy.deepcopy(value))
        for name in dict.fromkeys(qualification_ids):
            item = self.raw_qualifications.get(name)
            exact_name = _limited(item.get("name")) if item else ""
            if not exact_name:
                self.warnings.append(f"신청자격: 경로가 가리킨 직접 확인 자격({name})에 이름이 없어 뺐습니다.")
                continue
            text = _limited(item.get("text"))
            page = self.source.page_with(item, text or exact_name)
            if page is not None:
                evidence.append({"path": f"{path}.qualifications[{len(qualifications)}]", "page": page,
                                 "quote": text or exact_name})
            qualifications.append({"exactName": exact_name, "legalBasis": _limited(item.get("law")) or None,
                                   "conditionsAsWritten": text})
        return {"requirements": requirements, "qualifications": qualifications, "summary": summary}

    def warn_unused(self, routes: list[_Route]) -> None:
        """경로가 있는데 어느 경로도 가리키지 않은 요건·자격은 결과에 없다. 모델이 연결을 빠뜨린 것일 수 있어 알린다.
        routes는 모델이 쓴 경로 전부다. 모집하지 않는 순위라 뺀 경로의 요건은 빠뜨린 것이 아니다."""
        if not routes:
            return
        used = {name for route in routes for name in route.requires} | self._option_ids()
        qualified = {name for route in routes for name in route.qualifications}
        for label, key, unused in (
                ("요건", "text", [item for name, item in self.raw_requirements.items() if name not in used]),
                ("직접 확인 자격", "name", [item for name, item in self.raw_qualifications.items() if name not in qualified])):
            if unused:
                written = ", ".join(f"'{_short(item.get(key))}'" for item in unused)
                self.warnings.append(f"신청자격: 어느 신청 경로에도 쓰이지 않은 {label} {len(unused)}개를 뺐습니다({written}).")

    def _option_ids(self) -> set[str]:
        return {name for item in self.raw_requirements.values() if item.get("kind") == "ANY_OF"
                for name in _names(item.get("options"))}

    def _requirement(self, name: str) -> tuple[dict, list[dict | None]] | None:
        """(계약의 요건, [요건 근거, 선택 요건 근거…])를 돌려준다. 근거는 원문에서 확인한 것만 있고 아니면 None이다."""
        if name not in self.converted:
            item = self.raw_requirements.get(name)
            if item is None:
                self.converted[name] = None
            else:
                value, quote = _requirement(item, self.source, self.warnings, nested=False)
                quotes = [quote]
                if value["kind"] == "ANY_OF":
                    options = [self._option(option) for option in _names(item.get("options"))]
                    value["options"] = [option for option, _ in options]
                    quotes += [option_quote for _, option_quote in options]
                self.converted[name] = (value, quotes)
        return self.converted[name]

    def _option(self, name: str) -> tuple[dict, dict | None]:
        """응답에 없는 선택 요건은 OTHER로 채운다. 빼면 남은 선택지만으로 판정된다."""
        if name not in self.raw_requirements:
            self.warnings.append(f"신청자격: 선택 요건({name})이 응답에 없어 직접 확인으로 남겼습니다.")
            return {"kind": "OTHER", "asWritten": _MISSING_REQUIREMENT}, None
        return _requirement(self.raw_requirements[name], self.source, self.warnings, nested=True)


def _requirement(item: dict, source: _Source, warnings: list[str], nested: bool) -> tuple[dict, dict | None]:
    """한 숫자라도 확인하지 못하면 그 요건의 숫자를 모두 비운다. 나이 한쪽만 남기면 서버가 빈 쪽을 제한 없음으로 읽어 판정이 넓어진다."""
    text = _limited(item.get("text"))
    kind = item.get("kind") if item.get("kind") in KINDS else "OTHER"
    if kind == "ANY_OF" and nested:
        kind = "OTHER"
    page = source.page_with(item, text)
    page_text = source.texts.get(page, "") if page is not None else ""
    label = f"신청자격 요건 '{_short(text)}'"
    if nested and page is None:
        # 서버는 선택 요건마다 근거를 보지 않는다. 원문에 없는 선택 요건은 조건으로 바꾸지 못하는 OTHER로 두어 ANY_OF 전체가 직접 확인이 되게 한다.
        warnings.append(f"{label}: 근거 쪽에서 이 문구를 찾지 못해 직접 확인으로 남깁니다.")
        return {"kind": "OTHER", "asWritten": text}, None
    problems: list[str] = []
    result = {"kind": kind, "asWritten": text}
    scope = item.get("scope") if item.get("scope") in SCOPES else None
    if kind in ("AGE", "NO_HOUSE", "INCOME", "ASSETS", "CAR_VALUE", "NO_CAR"):
        result["scope"] = scope
    if kind in ("AGE", "MARRIAGE_PERIOD"):
        for name in ("minimum", "maximum") if kind == "AGE" else ("maximum",):
            adult = kind == "AGE" and name == "minimum" and item.get(name) == 19 and _ADULT.search(text)
            result[name] = 19 if adult else _number_in_text(item.get(name), text, problems)
    if kind == "INCOME":
        result["incomeStandard"] = item.get("standard") if item.get("standard") in INCOME_STANDARDS else None
        result["incomeBaseYear"] = _income_year(item.get("year"), source.posted_year, problems)
        result["percent"], result["householdSizeExtraPercent"] = _income_percents(item, text, page_text, source, problems)
    if kind in ("INCOME", "ASSETS", "CAR_VALUE"):
        result["adjustments"] = [
            {"kind": adjustment["kind"], "asWritten": _limited(adjustment.get("text"))}
            for adjustment in checks.objects(item.get("adjustments")) if adjustment.get("kind") in ADJUSTMENTS
        ]
    if kind in ("ASSETS", "CAR_VALUE"):
        written = _text(item.get("amount")).strip()
        result["amountAsWritten"] = written or None
        result["maximumKrw"] = _amount_in_text(written, text, page_text, _AMOUNT_WORDS[kind], problems)
    if kind == "RESIDENCE":
        result["regions"] = [region.strip() for region in item.get("regions") or [] if isinstance(region, str) and region.strip()]
        months = item.get("months")
        result["minimumMonths"] = months if _whole(months) and months >= 0 else None
        if months is not None and result["minimumMonths"] is None:
            problems.append(f"개월 수가 아닌 거주기간({months})")
        elif result["minimumMonths"] is not None and not _contains_months(months, text, page_text):
            problems.append(f"원문 쪽에 없는 거주기간({months}개월)")
    if kind == "FAMILY_TYPE":
        wanted = item.get("familyTypes") or []
        result["familyTypes"] = [value for value in FAMILY_TYPES if value in wanted]
    if page is None:
        warnings.append(f"{label}: 근거 쪽에서 이 문구를 찾지 못해 직접 확인으로 남깁니다.")
    if problems or page is None:
        for name in _NUMBERS:
            if name in result:
                result[name] = [] if name == "householdSizeExtraPercent" else None
        # 거주기간을 비우고 지역만 남기면 서버가 '지역 거주'만 판정해 넓어지므로 지역도 비운다(직접 확인).
        if kind == "RESIDENCE":
            result["regions"] = []
    if problems:
        warnings.append(f"{label}: 숫자를 확인하지 못해 직접 확인으로 남깁니다({', '.join(problems)}).")
    return result, ({"page": page, "quote": text} if page is not None else None)


def _income_percents(item: dict, text: str, page_text: str, source: _Source,
                     problems: list[str]) -> tuple[int | None, list[dict]]:
    """기본 비율과 가구원 수별 가산(기본 비율에 더하는 값)을 돌려준다. 모델은 원문 방식대로 최종 비율(percentBySize)이나
    가산값(extraBySize)을 낸다. 기본 비율 없이 가구원 수별 최종 비율만 있으면 가장 큰 가구원 수('3인 이상')의 비율을 기본으로 본다.
    기본 비율은 text에, 가구원 수별 최종 비율은 text나 근거 쪽 글에 단위와 함께 있어야 한다. 가산값은 최종 비율 대신 신청자격 쪽 어디든
    '가구원 수 + 가산값' 짝('1인 20%p')으로 적혀 있어도 된다(요건 문장과 기준표가 다른 쪽에 있는 공고)."""
    finals = {entry["size"]: entry["percent"] for entry in checks.objects(item.get("percentBySize"))
              if _whole(entry.get("size")) and entry["size"] >= 1 and _whole(entry.get("percent"))}
    extras = {entry["size"]: entry["extra"] for entry in checks.objects(item.get("extraBySize"))
              if _whole(entry.get("size")) and entry["size"] >= 1 and _whole(entry.get("extra"))}
    base = item.get("percent")
    if base is None and finals and max(finals) >= _BASE_HOUSEHOLD_SIZE:
        base = finals.pop(max(finals))
    # 기본 비율은 주 숫자라 요건 문구 안에 있어야 한다. 쪽 글의 다른 대상 비율로 통과하면 판정이 넓어진다.
    base = _number_in_text(base, text, problems)
    if base is None:
        if finals or extras:
            problems.append("기본 소득 비율이 없는 가구원 수별 비율")
        return None, []
    by_size = {**{size: base + extra for size, extra in extras.items()}, **finals}
    for size, final in by_size.items():
        added = size not in finals and source.states_size_extra(size, extras[size])
        if not added and not _contains_percent(final, text, page_text):
            problems.append(f"원문 쪽에 없는 가구원 수별 소득 비율({size}인 {final}%)")
    return base, [{"householdSize": size, "percent": final - base} for size, final in sorted(by_size.items()) if final != base]


def amount_krw(written: str) -> int | None:
    """원문 금액 표기(10,800만원, 3억 3,700만원, 37,080천원, 1억원)를 원으로 바꾼다. 읽을 수 없으면 None이다."""
    compact = _LIMIT_SUFFIX.sub("", _SPACES.sub("", written).replace(",", ""))
    match = _AMOUNT.fullmatch(compact)
    if not match or not any(match.groups()):
        return None
    eok, man, cheon, won = (int(part) if part else 0 for part in match.groups())
    return eok * 100_000_000 + man * 10_000 + cheon * 1_000 + won


def _amount_in_text(written: str, text: str, page_text: str, words: tuple[str, ...], problems: list[str]) -> int | None:
    """금액은 표기가 아니라 값으로 대조한다(24,500만원 = 245,000,000원). 공고문이 빈칸 양식처럼 '( 24,500 )만원'으로 적어도 같은 금액이다.
    문구에 금액이 둘 이상이면('총자산 …원 및 자동차 …원 이하') words(그 요건의 낱말) 바로 뒤 금액만 인정한다.
    page_text(근거 쪽 글)에서 그 표기가 더 큰 금액의 일부('3억 3,700만원'의 '3,700만원')면 인정하지 않는다."""
    if not written:
        return None
    value = amount_krw(written)
    if value is None:
        problems.append(f"원 단위로 읽지 못한 금액 표기({written})")
        return None
    compact = re.sub(r"[()\s]", "", text)
    # '세대구성원', '전원'처럼 숫자 없이 '원'으로 끝나는 낱말은 금액이 아니다.
    found = [(start, expression, amount) for start, expression, amount in
             ((match.start(), match.group(), amount_krw(match.group())) for match in _AMOUNT_IN_TEXT.finditer(compact))
             if amount is not None]
    if value not in {amount for _, _, amount in found}:
        problems.append(f"문구에 없는 금액({written})")
        return None
    if len({amount for _, _, amount in found}) > 1:
        after = next((compact.find(word) for word in words if word in compact), None)
        if after is None or next((amount for start, _, amount in found if start > after), None) != value:
            problems.append(f"문구의 여러 금액 중 이 요건의 금액인지 확인하지 못한 금액({written})")
            return None
    page = re.sub(r"[()]", "", checks.compact_text(page_text))
    if page and not any(re.search(r"(?<![\d,억만천])" + re.escape(expression), page)
                        for _, expression, amount in found if amount == value):
        problems.append(f"원문 쪽에서 더 큰 금액의 일부인 금액({written})")
        return None
    return value


def _contains_percent(value: int, text: str, page: str) -> bool:
    """요건 문구는 숫자만, 쪽 글은 단위가 붙은 비율(120%, 120퍼센트)만 인정한다. 쪽 글의 '1순위' 같은 숫자로 통과하지 않게 한다."""
    return (str(value) in re.findall(r"\d+", text.replace(",", ""))
            or re.search(rf"(?<!\d){value}\s*(?:%|퍼센트)", page) is not None)


def _contains_months(months: int, text: str, page: str) -> bool:
    """거주기간은 'N개월', 'Y년', 'Y년 M개월'처럼 단위가 붙은 형태만 인정한다."""
    years, rest = divmod(months, 12)
    # '1년 6개월'의 '1년'이나 '6개월'만 맞아 12·6개월로 통과하면 기간이 짧아져 판정이 넓어지므로, 합친 표기의 일부는 인정하지 않는다.
    patterns = [rf"(?<!\d)(?<!년)(?<!년 ){months}\s*개월"]
    if years and not rest:
        patterns.append(rf"(?<!\d){years}\s*년(?!\s*\d+\s*개월)")
    if years and rest:
        patterns.append(rf"(?<!\d){years}\s*년\s*{rest}\s*개월")
    return any(re.search(pattern, source) for pattern in patterns for source in (text, page))


def _number_in_text(value: object, text: str, problems: list[str]) -> int | None:
    if value is None:
        return None
    if not _whole(value) or value < 0:
        problems.append(f"숫자가 아닌 값({value})")
        return None
    if str(value) not in re.findall(r"\d+", text.replace(",", "")):
        problems.append(f"문구에 없는 숫자({value})")
        return None
    return value


def _income_year(written: object, posted_year: int | None, problems: list[str]) -> int | None:
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
    problems.append(f"정하지 못한 소득 기준 연도({text})")
    return None


def _posted_year(posted_date: object) -> int | None:
    match = re.match(r"(20\d{2})-\d{2}-\d{2}$", _text(posted_date))
    return int(match.group(1)) if match else None


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


def _by_id(value: object) -> dict[str, dict]:
    return {item["id"]: item for item in checks.objects(value) if isinstance(item.get("id"), str) and item["id"].strip()}


def _names(value: object) -> list[str]:
    return [name for name in value if isinstance(name, str)] if isinstance(value, list) else []


def _whole(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _limited(value: object) -> str:
    return _SPACES.sub(" ", _text(value)).strip()[:_TEXT_LIMIT]


def _short(value: object) -> str:
    text = _limited(value)
    return text if len(text) <= 40 else text[:40] + "…"
