# Backend Quality Gates

완료는 현재 작업 트리에서 새로 얻은 증거로만 판정한다.

## 게이트

| 영역 | 통과 조건 |
|---|---|
| 범위 | Goal·Constraints·Done 충족, 관련 없는 변경 없음 |
| 테스트 | 새 행동의 Red 확인, 관련·전체 검사 통과 |
| 구조 | 금지 의존성 없음, 공개 경계와 트랜잭션 명확 |
| 품질 | 백엔드 코드 컨벤션 준수, 책임·이름 명확, 죽은 코드 없음 |
| 보안 | 입력·권한 검증, 비밀·개인정보 노출 없음 |
| 운영 | 실패가 로그·메트릭·트레이스로 진단 가능 |

## 실행 순서

```text
1. 변경한 테스트 또는 가장 가까운 Gradle task
2. 영향 기능 테스트 묶음
3. ./gradlew check
4. 존재하는 계층 의존성 검사 또는 수동 구조 검토
5. git diff --check와 최종 diff 리뷰
```

- 존재하는 검사를 삭제하거나 완화해 통과시키지 않는다.
- flaky test는 재실행 성공만으로 통과 처리하지 않는다.
- 환경 문제로 실행하지 못한 검사는 실패와 구분해 기록한다.
- 실제 명령이 다르면 Gradle task 목록에서 확인한 명령을 사용한다.

## 완료 체크리스트

- [ ] 사용자 관점의 결과가 테스트 또는 실행 증거로 확인된다.
- [ ] Controller·Service·Repository·Domain 의존 방향이 유지된다.
- [ ] API·데이터·설정 변경의 호환성과 복구 경로를 확인했다.
- [ ] 오류·경계값·권한·외부 실패 경로를 검증했다.
- [ ] diff에 비밀, 임시 로그, 생성 산출물과 범위 이탈이 없다.
- [ ] 독립 리뷰의 blocker가 남아 있지 않다.

## 완료 보고

```text
Changed: 사용자 관점 결과와 핵심 파일
Verified: 실행한 명령과 성공·실패 결과
Architecture: 확인한 경계와 구조 검사
Not run: 실행하지 못한 검사와 이유
Risks: 잔여 위험 또는 없음
```

## 하네스 검사

```bash
sh tests/harness/validate-harness-test.sh
sh scripts/validate-harness.sh
```
