# Backend Harness Map

`AGENTS.md`는 시작점이고 이 디렉터리가 백엔드 작업 기준의 원본이다.
작업에 필요한 문서만 읽고, 코드로 확인 가능한 사실을 문서에서 반복하지 않는다.

## 작업별 라우팅

| 작업 | 읽을 문서 | 확인할 것 |
|---|---|---|
| 모든 작업 | `backend-context.md` | 제품 범위, 용어, 데이터 신뢰성 |
| 새 기능·버그 | `development-cycle.md` | 작업 계약, TDD, 검증 순서 |
| 구조 변경 | `architecture.md`, `module-boundaries.md` | 책임과 허용 의존성 |
| Java·Spring | `code-conventions.md` | 객체, 타입, Spring 사용 |
| Git 작업 | `git-conventions.md` | 이슈, 브랜치, 커밋, PR |
| HTTP API | `api-conventions.md` | 요청, 응답, 오류 계약 |
| DB 변경 | `persistence.md` | 모델, 쿼리, 트랜잭션 |
| 테스트 | `testing.md` | 테스트 범위와 대역 기준 |
| 보안 변경 | `security.md` | 인증, 인가, 개인정보, 비밀 |
| 운영 변경 | `observability.md` | 로그, 메트릭, 트레이스 |
| 작업 위임 | `agent-collaboration.md` | 역할과 파일 소유권 |
| 완료 판단 | `quality-gates.md` | 실행할 검사와 보고 증거 |

## 빠른 흐름

```text
Context → 관련 코드·테스트 탐색 → Red → Green → Refactor → Verify → Review
```

## 문서 작성 규칙

- 하네스가 필수로 지정한 문서는 질문 하나에 답하고 70줄 이하로 유지한다.
- 제품 README, 기여 가이드와 하네스 밖의 문서에는 70줄 제한을 적용하지 않는다.
- 이유는 짧은 문단, 매핑은 표, 순서는 번호, 완료 조건은 체크리스트로 쓴다.
- 동일 규칙을 복제하지 않고 원본 문서에 링크한다.
- 코드와 문서가 다르면 코드 동작을 확인하고 같은 변경에서 문서를 고친다.
- 반복 실패에서 나온 규칙은 가능하면 Gradle, 테스트, CI로 승격한다.

## 자동 검사

```bash
sh tests/harness/validate-harness-test.sh
sh tests/harness/validate-commit-message-test.sh
sh tests/harness/validate-pr-test.sh
sh scripts/validate-harness.sh
```
