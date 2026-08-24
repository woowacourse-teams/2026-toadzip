# Testing Strategy

> 현재는 기본 규칙만 확정한다. 세부 테스트 전략과 fixture·대역 기준은 추후 팀 합의로 확장한다.

## 테스트 피라미드

| 대상 | 기본 형태 | 검증 내용 |
|---|---|---|
| Domain | 빠른 단위 테스트 | 행위, 불변식, 경계값 |
| Service | 유스케이스 테스트 | 흐름, Repository 협력, 실패 전파 |
| Controller | 계약 테스트 | 검증, 상태, 오류 매핑 |
| Repository | DB 통합 테스트 | 매핑, 쿼리, 제약조건 |
| 외부 연동 | 계약·통합 테스트 | 요청, 응답, timeout, 변환 |

## 작업 순서

Red → Green → Refactor 순서와 완료 증거는 [development-cycle.md](development-cycle.md)를 따른다.
테스트 이름, 단일 동작과 Given-When-Then 규칙은 [CODE_CONVENTION.md](../CODE_CONVENTION.md)가 원본이다.

## 테스트 작성

- 구현 세부사항보다 공개 행동과 결과를 검증한다.
- 시간, 난수, ID와 외부 시스템은 제어 가능한 경계로 주입한다.
- 실제 경계가 중요한 곳을 무분별한 mock으로 대체하지 않는다.
- 같은 fixture가 의도를 가리면 테스트별 최소 데이터로 줄인다.

## 오류와 회귀

- 버그는 실제 증상을 재현하는 테스트로 시작한다.
- 입력 경계, 부재, 중복, 권한, timeout과 외부 실패를 검증한다.
- 예외 타입만 보지 않고 상태 변화와 외부 효과를 함께 확인한다.
- flaky test는 재실행 성공으로 통과시키지 않고 원인을 격리한다.

## 실행 순서

```text
가까운 테스트 → 기능 테스트 묶음 → ./scripts/test-postgres.sh
```

환경 때문에 실행하지 못한 검사는 실패와 구분해 미검증으로 보고한다.
