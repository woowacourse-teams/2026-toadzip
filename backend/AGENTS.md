# 백엔드 작업 지침

서비스 목적은 [SERVICE_OVERVIEW.md](../SERVICE_OVERVIEW.md), 작업별 안내는 [문서 목록](docs/README.md)에서 확인한다.
Git·로컬 자료·전체 테스트 실행은 루트 [기여 규칙](../CONTRIBUTING.md)을 따른다.

## 개발 기준

| 작업 | 기준 |
|---|---|
| Java 코드 | [코드 컨벤션](CODE_CONVENTION.md) |
| 패키지·의존성·저장 | [아키텍처](docs/architecture.md) |
| HTTP·인증·오류 | [API 규칙](docs/exception-handling.md) |
| 로그·메트릭 | [로그](docs/logging-convention.md) |
| 검증 | [테스트와 검증](docs/quality-gates.md) |

현재 요구에 필요한 최소 범위를 구현하고 기존 사용자 변경을 보존한다.
production dependency, 공개 계약과 데이터 변경은 사전 승인을 받는다.
테스트·정적 검사·보안 게이트를 약화해 통과시키지 않는다.

## 에이전트 역할

메인이 구현·통합·최종 검증을 책임진다. 조사와 리뷰 역할은 읽기 전용으로 사용한다.
역할 정의는 루트 `.codex/agents/`에 있다.

- `backend_explorer`: 실행 흐름과 영향 범위 조사
- `backend_architect`: API·데이터·트랜잭션 검토
- `backend_reviewer`: 정확성·회귀·보안 검토
