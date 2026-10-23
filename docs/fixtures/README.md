# 문서용 예시 입력

관리자 공고 JSON 가져오기 계약을 이해하고 입력을 작성할 때 참고하는 예시다. 내부 DB ID를 추정하거나 실제 운영 데이터로 그대로 등록하는 용도가 아니다.

## 자료 안내

| 자료 | 용도 |
|---|---|
| [정상 예시](announcement-import-v1-valid.json) | v1 공고 JSON의 구조·필드와 정상 입력 예시 |
| [여러 공급행](announcement-import-v1-multiple-supply-rows.json) | 하나의 공고에 여러 공급행을 작성하는 예시 |
| [미확정 값](announcement-import-v1-unresolved.json) | 확인하지 못한 값과 unresolvedFields를 기록하는 예시 |

## 관련 문서

[공고 JSON 가져오기 정책](../features/admin/announcement-import.md)에 따라 공식 원문으로 값을 확인한다. 미확정 값을 추정해 채우지 않으며 예시 파일은 애플리케이션 테스트 fixture의 대체물이 아니다.
