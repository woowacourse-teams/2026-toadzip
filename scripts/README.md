# 운영 스크립트

DB 점검·전환·복원, HTTPS 검증과 지역 카탈로그 생성에 사용하는 도구다.

## 조회와 검증

| 파일 | 용도 |
|---|---|
| [check-notification-schema.sh](check-notification-schema.sh), [검사 SQL](check-notification-schema.sql) | 선택한 개발·운영 DB의 알림 스키마와 Flyway 이력 읽기 전용 검사 |
| [test-https.py](test-https.py) | 임시 인증서로 HTTPS·Nginx 갱신 hook 검증. `--config-only`는 설정 검사만 실행 |
| [regions/import_catalog.py](regions/import_catalog.py) | 공식 자료에서 지역 CSV·JSON과 출처 정보 생성 |

## DB 변경

| 파일 | 실행 조건 |
|---|---|
| [flyway-login-constraint-prepare.sql](flyway-login-constraint-prepare.sql) | Flyway 이력이 없는 기존 DB의 로그인 고유 제약 이름 변경 |
| [flyway-login-constraint-finalize.sql](flyway-login-constraint-finalize.sql) | 지정 마이그레이션 성공 후 중복된 이전 제약 제거 |
| [reset-restored-notifications.sql](reset-restored-notifications.sql) | 격리된 복원 DB의 알림 신청·이메일 설정·취소 요청 삭제 |

각 SQL의 대상 DB 확인과 실행 전제를 따른다.
DB 점검은 [알림 데이터](../docs/NOTIFICATION_DATA.md), 전환은 [Flyway 도입](../backend/docs/flyway-adoption.md), 복원 후 정리는 [알림 보관·취소](../docs/notification-retention-and-cancellation.md)를 확인한다.
HTTPS 검증은 [HTTPS 가이드](../infra/certbot/README.md), 지역 갱신은 [지역 카탈로그](../backend/docs/region-search.md)를 따른다.
