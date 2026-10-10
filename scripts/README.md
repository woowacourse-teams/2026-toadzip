# 공통 스크립트

배포 설정을 검사하고 DB 전환·복원 작업을 지원하는 스크립트를 관리합니다.

| 주요 파일 | 역할 |
| --- | --- |
| [`test-https.py`](test-https.py) | 임시 인증서로 로컬 HTTPS·Nginx 갱신 hook을 검증합니다. `--config-only`는 Compose 설정과 hook 검사만 수행합니다. |
| [`check-notification-schema.sh`](check-notification-schema.sh), [`check-notification-schema.sql`](check-notification-schema.sql) | 선택한 개발·운영 DB의 알림 스키마와 Flyway 이력을 읽기 전용으로 검사합니다. |
| [`check-privacy-schema.sh`](check-privacy-schema.sh), [`check-privacy-schema.sql`](check-privacy-schema.sql) | 개인정보 배포 전후 PRIMARY DB의 폐기된 변경 흔적·독립 Flyway 이력·신규 전용 테이블을 읽기 전용으로 검사합니다. [배포 순서](../backend/docs/privacy-deployment.md)를 따릅니다. |
| [`flyway-login-constraint-prepare.sql`](flyway-login-constraint-prepare.sql) | **DB 변경:** Flyway 이력이 없는 기존 DB에서 로그인 식별자 고유 제약의 이름을 바꿔 전환을 준비합니다. |
| [`flyway-login-constraint-finalize.sql`](flyway-login-constraint-finalize.sql) | **DB 변경:** 지정된 Flyway 마이그레이션 성공 후 중복된 기존 고유 제약을 제거합니다. |
| [`reset-restored-notifications.sql`](reset-restored-notifications.sql) | **데이터 삭제:** 격리된 복원 DB에서 알림 신청·이메일 설정·미완료 취소 요청을 비웁니다. |

검사 기준은 [기여 규칙](../CONTRIBUTING.md), DB 점검은 [알림 데이터 안내](../docs/NOTIFICATION_DATA.md), HTTPS 검증은 [HTTPS 가이드](../infra/certbot/README.md)를 참고합니다. Flyway 전환·복원 SQL은 각 파일의 실행 전제와 대상 DB 확인 조건을 따릅니다. 복원 SQL은 기존 알림 자료를 삭제하므로 개인정보 신규 테이블의 배포·파기 절차로 사용하지 않습니다.
