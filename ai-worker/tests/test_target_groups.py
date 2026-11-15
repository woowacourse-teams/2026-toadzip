"""공급대상 분류: 공고문 표기와 신청자격 경로 이름을 닫힌 상수로 읽는다."""

import json

import pytest

from ai_worker import target_groups
from ai_worker.target_groups import groups
from conftest import CONTRACT


# 표기와 분류는 서버 이행(V20261116_01 백필)·화면과 함께 쓰는 계약 예시다. 표기는 로컬 DB의 실제 공급대상 표기다.
GROUP_CONTRACT = json.loads((CONTRACT / "supply-target-groups.json").read_text(encoding="utf-8"))


def test_constants_are_the_contract_constants_in_the_same_order():
    assert list(target_groups.GROUPS) == GROUP_CONTRACT["groups"]


@pytest.mark.parametrize("case", GROUP_CONTRACT["labels"], ids=lambda case: case["label"])
def test_label_becomes_its_contract_groups(case):
    # 구분 기호(·, 쉼표, 띄어쓰기, ․, 전각 공백)가 달라도 같은 분류이고, 모르는 낱말이 섞이면 분류를 정하지 않는다.
    assert groups(case["label"]) == case["groups"]


@pytest.mark.parametrize("label", ["", "  ", None])
def test_empty_label_has_no_groups(label):
    assert groups(label) == []


@pytest.mark.parametrize(("route", "target", "expected"), [
    (["NEWLYWED"], ["NEWLYWED", "PRE_NEWLYWED", "SINGLE_PARENT"], True),
    (["NEWLYWED"], ["NEWLYWED", "SINGLE_PARENT", "HOUSING_VULNERABLE"], False),
    (["NEWLYWED", "HOUSING_VULNERABLE"], ["NEWLYWED", "SINGLE_PARENT"], False),
    (["NEWLYWED", "HOUSING_VULNERABLE"], ["NEWLYWED", "SINGLE_PARENT", "HOUSING_VULNERABLE"], True),
    (["HOUSING_VULNERABLE"], ["HOUSING_VULNERABLE"], True),
    (["HOUSING_VULNERABLE"], ["ELDERLY", "HOUSING_VULNERABLE"], False),
    (["YOUTH"], ["UNIVERSITY_STUDENT"], False),
    ([], ["YOUTH"], False),
    (["YOUTH"], [], False),
    (None, ["YOUTH"], False),
])
def test_route_links_a_target_only_with_the_same_housing_vulnerable_mark_and_a_shared_group(route, target, expected):
    assert target_groups.linked(route, target) is expected
