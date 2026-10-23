# 공통 스크립트

배포 설정을 검사하고 DB 전환·복원 작업을 지원하는 스크립트를 관리합니다.

## 스크립트 안내

| 스크립트 | 용도·조건 |
| --- | --- |
| [`check-flyway-migrations.py`](check-flyway-migrations.py) | Git에 커밋된 Flyway SQL의 버전 중복, develop보다 낮거나 같은 신규 버전, 기존 SQL의 수정·삭제·이름 변경을 검사합니다. |
| [`test-https.py`](test-https.py) | 임시 인증서로 로컬 HTTPS·Nginx 갱신 hook을 검증합니다. `--config-only`는 Compose 설정과 hook 검사만 수행합니다. |
| [`check-notification-schema.sh`](check-notification-schema.sh), [`check-notification-schema.sql`](check-notification-schema.sql) | 선택한 개발·운영 DB의 알림 스키마와 Flyway 이력을 읽기 전용으로 검사합니다. |
| [`check-privacy-schema.sh`](check-privacy-schema.sh), [`check-privacy-schema.sql`](check-privacy-schema.sql) | 개인정보 배포 전후 PRIMARY DB의 폐기된 변경 흔적·독립 Flyway 이력·신규 전용 테이블을 읽기 전용으로 검사합니다. [배포 순서](../docs/operations/database/privacy-deployment.md)를 따릅니다. |
| [`flyway-login-constraint-prepare.sql`](flyway-login-constraint-prepare.sql) | **DB 변경:** Flyway 이력이 없는 기존 DB에서 로그인 식별자 고유 제약의 이름을 바꿔 전환을 준비합니다. |
| [`flyway-login-constraint-finalize.sql`](flyway-login-constraint-finalize.sql) | **DB 변경:** 지정된 Flyway 마이그레이션 성공 후 중복된 기존 고유 제약을 제거합니다. |
| [`reset-restored-notifications.sql`](reset-restored-notifications.sql) | **데이터 삭제:** 격리된 복원 DB에서 알림 신청·이메일 설정·미완료 취소 요청을 비웁니다. |

## 사용 기준

검사 기준은 [기여 규칙](../CONTRIBUTING.md), DB 점검은 [알림 데이터 안내](../docs/operations/notifications.md), HTTPS 검증은 [HTTPS 가이드](../docs/operations/https.md)를 참고합니다. Flyway 전환·복원 SQL은 각 파일의 실행 전제와 대상 DB 확인 조건을 따릅니다. 복원 SQL은 기존 알림 자료를 삭제하므로 개인정보 신규 테이블의 배포·파기 절차로 사용하지 않습니다.

Flyway 검사는 저장소 루트에서 `python3 scripts/check-flyway-migrations.py --base-ref origin/develop`로 실행합니다.
`origin/develop`은 로컬에 가져온 기준이므로 최신 상태인지 확인합니다. 검사 대상은 작업 파일이 아닌 `HEAD`의 커밋입니다.
`db/migration`과 `db/privacy`는 이력이 독립적이므로 신규 `V` 버전을 각 경로의 develop 최신 `V` 버전과 비교합니다. 기존 baseline(`B`) SQL도 변경할 수 없고,
동일한 `B` 버전의 중복을 차단합니다. baseline과 일반(`V`) SQL이 같은 버전을 사용하는 기존 구조는 허용합니다.
백엔드 CI는 develop 대상 PR의 병합 결과를 해당 PR의 develop 기준 커밋과 비교합니다. 그 외 PR·배포 실행은 버전 중복을 검사합니다.
이후 develop이 변경되면 최신 기준으로 검사를 다시 통과하도록 PR을 갱신해야 합니다. 이 검사는 실제 DB의 적용 이력이나 SQL 실행 결과를 검증하지 않습니다.
