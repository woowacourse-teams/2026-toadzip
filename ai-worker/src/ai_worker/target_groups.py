"""공급대상 분류(닫힌 상수, 여러 개). 공고문 공급대상 표기와 신청자격 경로 이름에서 분류를 읽는다.

서버(SupplyTargetGroup)와 같은 상수·연결 규칙(linked)이다. 경로 이름으로 공급대상을 맞추지 못하면 분류로 잇고, 관리자는 PDF 보정·
공급표 화면에서 분류를 고친다.
주거약자(HOUSING_VULNERABLE)는 다른 분류와 함께 붙는 구분이다(주거약자용 신혼부부 = 신혼부부 + 주거약자).
모르는 낱말이 섞인 표기(청년 창업인, 전체)는 분류를 정하지 않는다(빈 목록). 부분 글자로 맞추면 '청년 창업인'이 청년이 된다.
"""

import re

GROUPS = (
    "GENERAL",
    "YOUTH",
    "UNIVERSITY_STUDENT",
    "NEWLYWED",
    "PRE_NEWLYWED",
    "SINGLE_PARENT",
    "ELDERLY",
    "HOUSING_BENEFIT_RECIPIENT",
    "GA_CLASS",
    "NA_CLASS",
    "EXISTING_RESIDENT",
    "INDUSTRIAL_WORKER",
    "HOUSING_VULNERABLE",
)

_NAMES = {
    "일반": "GENERAL", "일반공급": "GENERAL", "일반공급대상": "GENERAL",
    "청년": "YOUTH",
    # 행복주택 대학생 계층은 취업준비생을 포함한다(신청자격 프롬프트: name 취업준비생 → supplyTarget 대학생).
    "대학생": "UNIVERSITY_STUDENT", "취업준비생": "UNIVERSITY_STUDENT",
    "신혼부부": "NEWLYWED", "신혼": "NEWLYWED",
    "예비신혼부부": "PRE_NEWLYWED",
    "한부모": "SINGLE_PARENT", "한부모가족": "SINGLE_PARENT",
    "고령자": "ELDERLY",
    "주거급여수급자": "HOUSING_BENEFIT_RECIPIENT",
    # 영구임대 신청자 묶음. 가군은 생계·의료급여수급자 등, 나군은 그 밖의 입주 가능자다.
    "가군": "GA_CLASS", "생계의료급여수급자": "GA_CLASS",
    "나군": "NA_CLASS",
    "기존거주자": "EXISTING_RESIDENT",
    "산업단지근로자": "INDUSTRIAL_WORKER", "산단근로자": "INDUSTRIAL_WORKER",
}
# 분류를 바꾸지 않는 낱말. '소득'은 같은 대상의 소득 구분 표기(청년 소득(○))다.
_IGNORED = {"등", "소득", "계층"}
_JOINED = (
    (re.compile(r"「\s*([가나])\s*」\s*군"), r"\1군"),
    (re.compile(r"생계\s*[·ㆍ・,]?\s*의료급여\s*수급자"), "생계의료급여수급자"),
    (re.compile(r"주거급여\s*수급자"), "주거급여수급자"),
    (re.compile(r"예비\s+신혼부부"), "예비신혼부부"),
    (re.compile(r"한부모\s+가족"), "한부모가족"),
    (re.compile(r"일반\s+공급"), "일반공급"),
    (re.compile(r"산업단지\s*근로자"), "산업단지근로자"),
)
_BRACKETED = re.compile(r"\([^)]*\)|\[[^\]]*\]")
_SEPARATOR = re.compile(r"[\s·ㆍ・․,/_]+")


def groups(label: object) -> list[str]:
    """표기의 분류(GROUPS 순서). 정할 수 없으면 빈 목록이다."""
    if not isinstance(label, str) or not label.strip():
        return []
    text = label
    for pattern, replacement in _JOINED:
        text = pattern.sub(replacement, text)
    found = {"HOUSING_VULNERABLE"} if "주거약자" in text else set()
    # 괄호 안은 같은 대상의 설명·구분이다(가군(생계·의료급여수급자 등), 청년(소득 없음), 고령자(주거약자용)).
    text = re.sub(r"주거약자용?", " ", _BRACKETED.sub(" ", text))
    tokens = [token for token in _SEPARATOR.split(text) if token and token not in _IGNORED]
    for token in tokens:
        group = _NAMES.get(token)
        if group is None:
            return []
        found.add(group)
    return [group for group in GROUPS if group in found]


def linked(route: object, target: object) -> bool:
    """신청자격 경로 분류가 공급대상 분류에 이어지는지. 둘 다 분류가 있고 주거약자 구분이 같으며, 나머지 분류가 하나라도 겹치거나 둘 다
    주거약자뿐이어야 한다. 주거약자용 신혼부부 경로는 주거약자용이 아닌 신혼부부 대상에, 신혼부부 경로는 주거약자용 대상에 잇지 않는다."""
    if not isinstance(route, list) or not isinstance(target, list) or not route or not target:
        return False
    wanted, offered = set(route), set(target)
    if ("HOUSING_VULNERABLE" in wanted) != ("HOUSING_VULNERABLE" in offered):
        return False
    wanted.discard("HOUSING_VULNERABLE")
    offered.discard("HOUSING_VULNERABLE")
    return bool(wanted & offered) or not wanted and not offered
