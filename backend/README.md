# 백엔드

공공주택·공고 조회, 외부 데이터 수집·정제, 사용자·관리자 인증과 알림 신청을 처리하는 Java·Spring Boot 애플리케이션이다.

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [환경 설정](../docs/setup/README.md) | 로컬·개발·운영 실행 준비와 환경값 |
| [백엔드 개발 기준](../docs/development/backend/README.md) | 계층·저장소·보안·테스트·관측의 구현 기준 |
| [코드 컨벤션](CODE_CONVENTION.md) | Java 스타일·객체·계층별 책임과 테스트 작성 규칙 |
| [API·오류 계약](../docs/development/api.md) | 요청·응답·검증과 예외 매핑 |
| [기능 명세](../docs/features/README.md) | 수집·공고·관리자·검색·알림·개인정보의 현재 계약 |
| [운영](../docs/operations/README.md) | 배포·DB 전환·모니터링과 복구 |
| [AI 작업 지침](AGENTS.md) | 백엔드 변경 제한·필수 문서와 읽기 전용 협업 |

## 관련 문서

기여·Git 절차는 [기여 가이드](../CONTRIBUTING.md)를 따른다. 전체 테스트의 Docker·Testcontainers 전제와 DB 격리는 [백엔드 검증](../docs/development/backend/testing.md)에서 확인한다.
