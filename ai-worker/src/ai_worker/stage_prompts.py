"""추출 단계별 프롬프트를 만든다. 문구와 응답 스키마는 prompts/ 폴더의 파일이 원본이다.
본문 단계에는 관리자 스키마에서 그 단계가 맡은 필드만 남겨 보내고, 신청자격 단계에는 신청자격 절의 쪽과 그 지시만 보내며, LH 공고의 공급 단계에는 LH 공급 API 행을 참고 목록으로 함께 보낸다.
모델·문구·쪽 입력을 바꾸면 대표 PDF로 정확도를 다시 확인한다.
"""

import json
from importlib.resources import files

from ai_worker import pages
from ai_worker.eligibility_pages import EligibilityPages
from ai_worker.source_frame import SourceSupplyRow

_PROMPTS = files("ai_worker") / "prompts"


def _prompt_text(name: str) -> str:
    return (_PROMPTS / name).read_text(encoding="utf-8")


_INSTRUCTIONS = _prompt_text("announcement-pdf-extraction.txt")
_SUPPLY_INSTRUCTIONS = _prompt_text("announcement-pdf-supply-extraction.txt")
_SCHEMA = json.loads(_prompt_text("announcement-pdf-extraction-schema.json"))
_TEXT_PREFACE = _prompt_text("text-preface.txt")
_SCHEMA_SUFFIX = _prompt_text("schema-suffix.txt")
_LH_FRAME = _prompt_text("lh-frame.txt")
_ELIGIBILITY_INSTRUCTIONS = _prompt_text("announcement-pdf-eligibility.txt")

# 단계마다 공고와 상관없이 글자 하나 다르지 않은 앞부분. 모델 호출은 이 부분을 캐시해 다음 공고가 싸게 읽는다.
_SUPPLY_HEAD = _TEXT_PREFACE + "[추출 단계: 공급 표]\n" + _SUPPLY_INSTRUCTIONS
_NOTICE_HEAD = _TEXT_PREFACE + "[추출 단계: 공고 본문]\n" + _INSTRUCTIONS + "\n\n"
_ELIGIBILITY_HEAD = "[추출 단계: 신청자격]\n" + _ELIGIBILITY_INSTRUCTIONS + "\n"
FIXED_HEADS = (_SUPPLY_HEAD, _NOTICE_HEAD, _ELIGIBILITY_HEAD)


def _notice_schema() -> str:
    """관리자 스키마에서 본문 단계가 맡은 필드만 남겨 모델이 동시에 채울 필드를 줄인다."""
    schema = json.loads(json.dumps(_SCHEMA))
    root = schema["properties"]
    data = root["data"]
    data_properties = data["properties"]
    data_names = ("announcement", "receptionPlaces", "complexes", "schedules", "unresolvedFields")
    root_names = ("notApplicable", "evidence")
    complex_fields = ("sourceComplexName", "address", "totalHouseholdCount", "parkingCount")

    data["properties"] = {name: data_properties[name] for name in data_names}
    data["properties"]["complexes"] = _project_complex_schema(data_properties["complexes"], complex_fields)
    data["required"] = [name for name in data["required"] if name in data_names]
    schema["properties"] = {name: value for name, value in root.items() if name in (*root_names, "data")}
    schema["required"] = [name for name in schema["required"] if name in schema["properties"]]
    return json.dumps(schema, ensure_ascii=False, indent=2)


def _project_complex_schema(schema: dict, fields: tuple[str, ...]) -> dict:
    item = json.loads(json.dumps(schema))
    properties = item["items"]["properties"]
    item["items"]["properties"] = {name: properties[name] for name in fields}
    item["items"]["required"] = [name for name in item["items"]["required"] if name in fields]
    return item


def supply_prompt(rows: list[SourceSupplyRow], selected: pages.PromptPages) -> str:
    lh_frame = ""
    if rows:
        # 번호를 붙이면 모델이 목록 줄마다 공급행을 만들거나 번호로 맞추려 해서 참고 목록으로만 준다.
        frame = "\n".join(
            f"- 단지={row.complex_label} | 주택형={row.type_name} | 전용={row.exclusive_area} | 공급={row.supply_area} "
            f"| 모집수={row.supplied_unit_count}"
            for row in rows
        )
        lh_frame = _LH_FRAME + "\n[LH 공급 API 행]\n" + frame + "\n\n"
    return _SUPPLY_HEAD + lh_frame + "\n\n" + selected.supply_text


def notice_prompt(selected: pages.PromptPages) -> str:
    # 공급표의 열 역할을 읽었는데 단지명 열이 없으면 단지명이 표 밖(건설위치·블록 제목)에 있다. 표를 읽지 못했으면 단지명 열이 있는지 모른다.
    no_column = selected.supply_tables and not selected.table_structure_fallback
    candidates = "\n".join(f"- {name}" for name in selected.complex_candidates) or (
        "(후보 없음: 공급 표에 단지명 열이 없음)" if no_column else "(후보를 읽지 못함)")
    context = "[공급 표에서 읽은 단지명 후보]\n" + candidates + "\n\n"
    return (_NOTICE_HEAD + context + _SCHEMA_SUFFIX
            + _notice_schema() + "\n\n" + selected.notice_text)


def eligibility_prompt(source: EligibilityPages) -> str:
    """신청자격 단계는 신청자격 절의 쪽만 읽는다. 응답 형식은 지시문에 있어 스키마를 따로 붙이지 않는다(출력이 길수록 느리다).
    공급표가 신청자를 묶음(가군·나군)으로 나눴으면 그 이름을 주어 신청 경로를 공급대상에 잇게 한다."""
    groups = ""
    if source.applicant_groups:
        groups = "[공급표의 신청자 묶음]\n" + "\n".join(f"- {name}" for name in source.applicant_groups) + "\n\n"
    return _ELIGIBILITY_HEAD + groups + source.text
