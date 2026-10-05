# 저장소·운영 검증

저장소 공통 규칙을 검사하는 도구와 DB 백업 자동화의 회귀 테스트를 관리합니다.

| 디렉터리·주요 파일 | 역할 |
| --- | --- |
| [`harness/`](harness/) | 한글 판별, 커밋·PR 규칙, 필수 문서·에이전트 구성, 로컬 자료 추적 금지 검사의 정상·실패 사례를 검증합니다. |
| [`backup/`](backup/) | 실제 DB·AWS 접속 없이 백업 스크립트와 감시 코드의 동작을 검증합니다. |
| [`backup/backup-test.sh`](backup/backup-test.sh) | Docker·AWS 명령을 모의 구현해 DB 백업, S3 파일 크기·보관 정책, 실패 알림 처리를 검증합니다. |
| [`backup/monitor-test.py`](backup/monitor-test.py) | AWS 클라이언트 모의 객체로 백업 누락·만료·빈 파일, 버킷 정책, 상태 메트릭 발행을 검증합니다. |

실행 항목은 [CI 워크플로](../.github/workflows/harness-check.yml), 검사 대상은 [공통 스크립트](../scripts/README.md)와 [DB 백업 운영](../infra/db/BACKUP.md)을 참고합니다. 애플리케이션 테스트는 [백엔드 테스트](../backend/src/test/)와 [프론트엔드 소스](../frontend/src/)에 있으며, 실행 기준은 [기여 규칙](../CONTRIBUTING.md)과 [프론트엔드 품질 기준](../frontend/docs/quality-gates.md)을 따릅니다.
