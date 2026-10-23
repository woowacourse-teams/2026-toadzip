# 백엔드 에이전트 지도

루트 [AGENTS.md](../AGENTS.md)의 공통 지침에 더해 `backend/` 작업에 적용한다.

## 필수 개발 기준

- Java·Spring 코드 변경은 [CODE_CONVENTION.md](CODE_CONVENTION.md)와 [아키텍처·계층 경계](../docs/development/backend/architecture.md)를 따른다.
- 구현은 [개발 흐름](../docs/development/backend/testing.md#개발-흐름)의 Red → Green → Refactor와 설계 게이트를 따르고, 완료 전 [검증 기준](../docs/development/backend/testing.md#게이트)의 증거를 새로 확인한다.
- API·예외는 [공통 계약](../docs/development/api.md), DB 변경은 [영속성·트랜잭션](../docs/development/backend/persistence.md)을 따른다.
- 기능별 정책과 운영 절차는 [공용 문서 목차](../docs/README.md)에서 찾는다.

## 변경 제한

- 생성자 주입과 불변 객체를 우선하고 예외를 성공으로 위장하지 않는다.
- production dependency, 공개 계약, 데이터 변경은 사전 승인을 받는다.

## 읽기 전용 협업

역할의 실행 원본은 [.codex/agents/](../.codex/agents/)다.

| 역할 | 맡길 일과 반환할 근거 |
|---|---|
| `backend_explorer` | 실행 흐름·영향 범위를 조사하고 결론별 파일·심볼과 미확인 사항을 반환한다. |
| `backend_architect` | 모듈·API·트랜잭션 경계를 검토하고 선택지·권고·트레이드오프·자동 검증 방법을 반환한다. |
| `backend_reviewer` | 정확성·회귀·보안을 검토하고 심각도순 발견·재현법·안전한 수정 경로를 반환한다. |

- 메인 에이전트가 요구사항과 결정, 구현, 파일 소유권, 통합과 최종 검증을 책임진다.
- 위임할 때 목표, 읽을 경로·범위, 입력 근거·확정된 계약, 제약, 기대 결과와 완료 조건을 전달한다. 수정 파일·소유권·기대 변경·검증 계획은 메인이 관리한다.
- 독립적인 조사·설계 검토·리뷰만 위임한다. 서브에이전트는 읽기 전용이며 파일을 수정하거나 전체 작업의 완료를 선언하지 않는다.
- 순차 의존성이 있으면 앞 작업의 계약을 확정한 뒤 다음 작업을 시작한다.
- 메인은 반환 결과를 목표·완료 조건과 대조하고 공유 인터페이스·중복 구현을 확인한다. 최종 작업 트리에서 검증하고 독립 리뷰의 blocker를 해결하거나 근거 있게 기각한다.
