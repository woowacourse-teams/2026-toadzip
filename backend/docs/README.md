# Backend Harness Map

[backend/AGENTS.md](../AGENTS.md)는 시작점이고 이 디렉터리는 백엔드 전용 보충 지침이다.
기존 원본 문서와 내용이 겹치면 원본을 우선하고 여기서 반복하지 않는다.

## 원본 문서

| 주제 | 원본 |
|---|---|
| 서비스 맥락 | [SERVICE_OVERVIEW.md](../../SERVICE_OVERVIEW.md) |
| Java 코드 규칙 | [CODE_CONVENTION.md](../CODE_CONVENTION.md) |
| Git·기여 규칙 | [CONTRIBUTING.md](../../CONTRIBUTING.md) |
| 개발 환경 | [SETUP.md](../../docs/SETUP.md) |

## 작업별 라우팅

| 작업 | 읽을 문서 | 확인할 것 |
|---|---|---|
| 새 기능·버그 | [development-cycle.md](development-cycle.md) | 작업 계약, TDD, 검증 순서 |
| 구조 변경 | [architecture.md](architecture.md), [layer-boundaries.md](layer-boundaries.md) | 계층 책임과 허용 의존성 |
| Java·Spring | [CODE_CONVENTION.md](../CODE_CONVENTION.md) | 코드 스타일과 객체 규칙 |
| Git 작업 | [CONTRIBUTING.md](../../CONTRIBUTING.md) | 이슈, 브랜치, 커밋, PR |
| HTTP API | [api-conventions.md](api-conventions.md) | 요청, 응답, 오류 계약 |
| DB 변경 | [persistence.md](persistence.md) | 모델, 쿼리, 트랜잭션 |
| 테스트 | [testing.md](testing.md) | 테스트 범위와 대역 기준 |
| 보안 변경 | [security.md](security.md) | 인증, 인가, 개인정보, 비밀 |
| 운영 변경 | [observability.md](observability.md) | 로그, 메트릭, 트레이스 |
| 작업 위임 | [agent-collaboration.md](agent-collaboration.md) | 역할과 읽기 범위 |
| 완료 판단 | [quality-gates.md](quality-gates.md) | 실행할 검사와 보고 증거 |

## 상황별 빠른 라우팅

| 상황 | 빠른 순서 |
|---|---|
| 새 기능 | 서비스 맥락 → 작업 계약·탐색 → 설계 게이트 → 관련 규칙 → Red-Green-Refactor → 품질 게이트 |
| 버그 수정 | 재현 테스트 → 최소 수정 → 관련 테스트 → 회귀 검증 → 품질 게이트 |
| 리팩터링 | 기존 테스트 → 계층 경계 → 구조 개선 → 동작 보존 검증 → 품질 게이트 |
| 코드 리뷰 | 계층 경계 → 관련 규칙 → 코드 규칙 → 품질 게이트 |
| 조사 작업 | 범위 확정 → 읽기 전용 탐색 → 사실·추론 구분 → 파일 근거가 있는 요약 |
| Git 작업 | 이슈 → 작업 브랜치 → 작은 커밋 → PR → 리뷰 → Merge Commit |

## 문서 작성 규칙

- 하네스 전용 문서는 질문 하나에 답하고 70줄 이하로 유지한다.
- 원본 문서와 하네스 밖의 문서에는 70줄 제한을 적용하지 않는다.
- 이유는 짧은 문단, 매핑은 표, 순서는 번호, 완료 조건은 체크리스트로 쓴다.
- 동일 규칙을 복제하지 않고 원본 문서에 링크한다.
- 반복 실패에서 나온 규칙은 가능하면 Gradle, 테스트, CI로 승격한다.

## 자동 검사

```bash
sh tests/harness/validate-harness-test.sh
sh tests/harness/validate-commit-message-test.sh
sh tests/harness/validate-pr-test.sh
sh scripts/validate-harness.sh
```
