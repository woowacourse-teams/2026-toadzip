"""추출 단계별 프롬프트를 만든다. 문구와 응답 스키마는 prompts/ 폴더의 파일이 원본이다.
본문 단계에는 관리자 스키마에서 그 단계가 맡은 필드만 남겨 보내고, LH 공고의 공급 단계에는 LH 공급 API 행 틀을 함께 보낸다.
모델·문구·쪽 입력을 바꾸면 대표 PDF로 정확도를 다시 확인한다.
"""

import json
from importlib.resources import files

from ai_worker import pages
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


def _notice_schema() -> str:
    """관리자 스키마에서 본문 단계가 맡은 필드만 남겨 모델이 동시에 채울 필드를 줄인다."""
    schema = json.loads(json.dumps(_SCHEMA))
    root = schema["properties"]
    data = root["data"]
    data_properties = data["properties"]
    data_names = ("announcement", "receptionPlace", "complexes", "schedules", "unresolvedFields")
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
        frame = "\n".join(
            f"{number}. 주택형={row.type_name} | 전용={row.exclusive_area} | 공급={row.supply_area} | 모집수={row.supplied_unit_count}"
            for number, row in enumerate(rows, start=1)
        )
        lh_frame = _LH_FRAME + "\n[LH 공급 API 행]\n" + frame + "\n\n"
    return _TEXT_PREFACE + "[추출 단계: 공급 표]\n" + _SUPPLY_INSTRUCTIONS + lh_frame + "\n\n" + selected.supply_text


def notice_prompt(selected: pages.PromptPages) -> str:
    candidates = "\n".join(f"- {name}" for name in selected.complex_candidates) or "(후보를 읽지 못함)"
    context = "[공급 표에서 읽은 단지명 후보]\n" + candidates + "\n\n"
    return (_TEXT_PREFACE + "[추출 단계: 공고 본문]\n" + _INSTRUCTIONS + "\n\n" + context + _SCHEMA_SUFFIX
            + _notice_schema() + "\n\n" + selected.notice_text)
