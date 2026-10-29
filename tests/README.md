# 저장소·운영 검증

Flyway 검사 도구, 배포 설정과 모니터링 구성의 회귀 테스트를 관리합니다.

## 검증 안내

| 검사 | 검증 대상·실행 위치 |
| --- | --- |
| [`ci/test_flyway_migrations.py`](ci/test_flyway_migrations.py) | 임시 Git 이력으로 Flyway 버전 중복, 낮은 버전 추가와 기존 SQL 변경 차단을 검증합니다. 백엔드 CI에서 실행합니다. |
| [`deployment/test_notification_environment.py`](deployment/test_notification_environment.py) | 알림 기능의 기본 비활성화, 빌드·런타임 환경값과 선택적 인증정보 마운트를 검증합니다. 프론트엔드 CI에서 실행합니다. |
| [`deployment/test_backend_bluegreen.py`](deployment/test_backend_bluegreen.py) | `--image`로 지정한 실제 백엔드와 격리 PostgreSQL을 사용해 후보 실패·Nginx 오류·Blue/Green 전환·원복·DB 보존을 검증합니다. |
| [`deployment/test_backend_health.py`](deployment/test_backend_health.py) | Compose의 healthcheck·시작 순서와 상태 응답 판정을 검증합니다. 별도로 실행하는 검사입니다. |
| [`monitoring/`](monitoring/) | 대시보드 조회식·요청 필드와 DB 호스트·PostgreSQL·로그 수집 구성을 검증합니다. 별도로 실행하며 일부 검사는 Docker가 필요합니다. |

## 실행과 결과 확인

자동 실행 항목과 명령의 원본은 [백엔드 CI](../.github/workflows/backend-ci.yml)와 [프론트엔드 CI](../.github/workflows/frontend-ci.yml)입니다.
모니터링 검증의 준비·실행·정리 방법은 [모니터링 운영 안내](../docs/operations/monitoring.md)를 따릅니다.
애플리케이션 테스트는 [백엔드 테스트](../backend/src/test/)와 [프론트엔드 소스](../frontend/src/)에 있으며,
실행 기준은 [기여 규칙](../CONTRIBUTING.md)과 [프론트엔드 품질 기준](../docs/development/frontend/testing.md)을 따릅니다.

아래 명령은 저장소 루트에서 실행합니다. 알림 배포 설정은 `python3 tests/deployment/test_notification_environment.py`로 검증합니다.
개인 `.env` 없이 공개 fixture만 사용해 기본 비활성화, 빌드/런타임 전달, 선택적 ADC 읽기 전용 마운트를 확인합니다.

Flyway CI 검사는 `python3 -B tests/ci/test_flyway_migrations.py`로 검증합니다.
DB·원격 저장소에 연결하지 않으며, `.local/`에 만든 테스트용 Git 저장소만 사용하고 종료 시 정리합니다.

## 실제 백엔드 Blue/Green 로컬 검증

Docker와 Python 3, 현재 소스에서 빌드한 백엔드 이미지가 필요합니다. 저장소 루트에서 실행합니다.

```bash
docker build -t toadzip-backend:bluegreen-check ./backend
python3 -B tests/deployment/test_backend_bluegreen.py --image toadzip-backend:bluegreen-check
```

매 실행마다 고유 Compose 프로젝트와 tmpfs PostgreSQL 두 개를 만듭니다. 실제 `.env`와 실행 중인
서비스·볼륨을 사용하지 않습니다. Edge는 임의의 localhost 포트만 열고 백엔드·DB는 호스트 포트를 열지 않습니다.
현재 소스의 `local` 프로필·Flyway·JPA 검증을 사용하며 두 색상에는 같은 이미지 ID를 지정합니다.
후보 DB 인증 실패 시 기존 경로 유지, 잘못된 Nginx 설정 복구, Green 전환과 Blue 원복을 확인합니다.
고유 API 요청을 해당 백엔드 로그에서 찾고, DB를 조회하는 공고 목록 API도 함께 검사합니다.

생성·큐 처리·정리 알림 작업은 두 백엔드에서 활성화하지만, 외부 FCM 발송은 끕니다.
빈 DB의 스케줄러 기동 검사는 알림 데이터 경합 검증을 대신하지 않습니다.
`backend/`의 `NotificationMaterializationConcurrencyIntegrationTest`, `NotificationDeliveryIntegrationTest`,
`NotificationScheduleIntegrationTest`와 `NotificationMaintenanceIntegrationTest`도 실행합니다.
외부 발송에는 lease 만료·결과 기록 실패 시 재전송 가능성이 있습니다.

요청 관찰 로그·시험용 Compose·설정 백업은 `.local/toadzip-backend-check-*`에 남깁니다.
정상 종료·일반 예외에서 해당 프로젝트만 내리며 tmpfs 데이터는 사라집니다. 강제 종료 후에는 출력된 프로젝트
이름으로 잔여 컨테이너를 확인합니다. 동시 요청 관찰에서 실패가 없다는 결과는 실제 부하의 무중단을 보장하지 않습니다.
서로 다른 릴리스의 스키마 호환성, 장기 요청의 종료와 운영 메모리는 별도로 검증해야 합니다.
이 도구는 로컬 검증용이며 배포 자동화나 서버 롤백 명령으로 사용하지 않습니다.
