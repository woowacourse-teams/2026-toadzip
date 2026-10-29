# 저장소·운영 검증

Flyway 검사 도구, 배포 설정과 모니터링 구성의 회귀 테스트를 관리합니다.

## 검증 안내

| 검사 | 검증 대상·실행 위치 |
| --- | --- |
| [`ci/test_flyway_migrations.py`](ci/test_flyway_migrations.py) | 임시 Git 이력으로 Flyway 버전 중복, 낮은 버전 추가와 기존 SQL 변경 차단을 검증합니다. 백엔드 CI에서 실행합니다. |
| [`deployment/test_notification_environment.py`](deployment/test_notification_environment.py) | 알림 기능의 기본 비활성화, 빌드·런타임 환경값과 선택적 인증정보 마운트를 검증합니다. 프론트엔드 CI에서 실행합니다. |
| [`deployment/test_bluegreen.py`](deployment/test_bluegreen.py) | POSIX 프론트·백엔드 도구의 후보 준비·전환·복구·공통 잠금·요청 대기·정지한 컨테이너 재시작·정적 파일 보관을 Docker 없이 검증합니다. `python3 -B tests/deployment/test_bluegreen.py`로 실행합니다. |
| [`deployment/test_backend_bluegreen.py`](deployment/test_backend_bluegreen.py) | `--image`로 지정한 실제 백엔드와 격리 PostgreSQL을 사용해 후보 실패·Nginx 오류·Blue/Green 전환·원복·DB 보존을 검증합니다. |
| [`deployment/test_bluegreen_drain.py`](deployment/test_bluegreen_drain.py) | 실제 Linux 도구·Nginx로 긴 프론트/API 요청, 정지·원복, 세 버전 정적 파일, 대기 초과·전환 실패·후보 실패를 검증합니다. |
| [`deployment/test_release_bluegreen.py`](deployment/test_release_bluegreen.py) | CI SHA·빌드 선행·전체 잠금·실패 중단·부분 배포 보고와 워크플로 셸을 검증합니다. 프론트 CI에서 실행합니다. |
| [`deployment/test_release_bluegreen_linux.py`](deployment/test_release_bluegreen_linux.py) | 격리 Git 체크아웃과 실제 Linux/Compose에서 커밋 라벨 빌드·순차 배포·Step Summary를 검증합니다. 수동 실행 검사입니다. |
| [`deployment/test_caddy_ingress.py`](deployment/test_caddy_ingress.py) | 서버 포트·HTTP 프론트·런타임·저장소·기존 TLS 제거를 검증합니다. 프론트 CI에서 실행합니다. |
| [`deployment/test_caddy_ingress_linux.py`](deployment/test_caddy_ingress_linux.py) | 임시 CA와 loopback 포트로 실제 Caddy HTTPS·헤더·Edge 연결·저장소를 확인합니다. 수동 검사입니다. |
| [`deployment/test_prepare_bluegreen.py`](deployment/test_prepare_bluegreen.py) | 서버 런타임 준비·기존 상태 보존·이전 설정 복사·배포 잠금·기본 경로를 검증합니다. 프론트 CI에서 실행합니다. |
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

Docker와 Python 3, 현재 소스에서 빌드한 백엔드 이미지가 필요합니다. 시험용 `docker:cli` Linux 컨테이너에서
Bash·Python을 `apk`로 설치하므로 최초 이미지 다운로드와 패키지 설치 네트워크가 필요합니다. 저장소 루트에서 실행합니다.

```bash
docker build -t toadzip-backend:bluegreen-check ./backend
python3 -B tests/deployment/test_backend_bluegreen.py --image toadzip-backend:bluegreen-check
```

매 실행마다 고유 Compose 프로젝트와 tmpfs PostgreSQL 두 개를 만듭니다. 실제 `.env`와 실행 중인
서비스·볼륨을 사용하지 않습니다. Edge는 임의의 localhost 포트만 열고 백엔드·DB는 호스트 포트를 열지 않습니다.
현재 소스의 `local` 프로필·Flyway·JPA 검증을 사용하며 두 색상에는 같은 이미지 ID를 지정합니다.
실제 `deploy-bluegreen.sh --service backend`로 후보 DB 인증 실패 시 기존 경로 유지와 Green 전환을,
`switch-backend.sh`로 정지된 Blue 재시작·원복을 확인합니다. Green 전환 후 Blue의 정지도 검사합니다. 잘못된 Nginx 설정은 별도 주입 후 복구를 확인합니다.
Linux CLI에 저장소는 읽기 전용, 해당 시험 디렉터리만 쓰기 가능하게 마운트하고 `.env`는 빈 파일로 가립니다.
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

## 긴 요청·정적 파일 버전·원복 통합 검증

저장소 루트에서 `python3 -B tests/deployment/test_bluegreen_drain.py`로 실행합니다.
Docker·Python 3과 최초 이미지·`apk` 패키지 다운로드 네트워크가 필요합니다.
실제 Spring/DB 대신 제어 가능한 HTTP 서버와 세 버전의 정적 프론트 이미지를 격리 환경에 만듭니다.
위의 실제 백엔드 검증과 함께 실행하며 Spring 동작·운영 부하를 대신하는 시험은 아닙니다.

- Edge를 거치는 긴 화면·API 요청의 시작과 완료를 파일 신호로 제어합니다. 새 버전으로 전환된 뒤에도
  이전 요청이 끝나기 전에는 배포 명령이 대기하고 이전 컨테이너가 살아 있는지 확인합니다.
- 요청 완료 후 정지, 같은 이미지의 재시작·원복, 세 버전에 걸친 과거 JS 모듈의 내용·MIME·없는 파일의 404를 확인합니다.
- 대기 초과는 요청을 끊지 않고 이전 경로로 복구하며, 미종료 worker가 남았을 때 다음 후보 재생성을 막는지 확인합니다.
- 건강하지 않은 후보와 전환 후 잘못된 응답은 기존 화면을 유지·복구하는지 확인합니다.

컨테이너는 해당 고유 프로젝트만 정리합니다. 이미지·시험 구성·신호 파일·로그는 로컬 검증 자료로 남깁니다.
실제 서비스의 로그인·지도 SDK·UI 조작이나 여러 릴리스 사이 API 호환성을 검증한 결과로 해석하지 않습니다.

## Linux 릴리스 통합 검증

저장소 루트에서 `python3 -B tests/deployment/test_release_bluegreen_linux.py`로 실행한다.
Docker·Python 3과 이미지·`apk` 패키지를 받는 네트워크가 필요하다. 고유 프로젝트에 시험용 Nginx·HTTP 서버를
기동하고 `.local/` 안에 별도 Git 저장소와 Docker 빌드 컨텍스트를 만든다. 실제 서버·인증정보·외부 Git 원격에는 연결하지 않는다.

Linux 부모 셸의 `flock`을 Python에 전달한 상태에서 실제 릴리스 도구를 실행한다.
두 커밋 라벨 이미지의 빌드, 백엔드·프론트 전환, 이전 컨테이너 정지, 보관된 이전 정적 파일과
기록한 이미지 ID·연결 색상·Actions 요약을 실제 실행 상태와 대조한다.
정상 종료·예외에서 해당 프로젝트의 컨테이너만 정리하고 자료·이미지는 로컬에 남긴다.

GitHub 호스팅 러너·Self-hosted Runner·도메인 TLS·실제 Spring/React 이미지 빌드·운영 DB를 검증한 결과는 아니다.
빠른 회귀 검사는 `python3 -B tests/deployment/test_release_bluegreen.py`로 실행한다.

Caddy 검사의 실행 조건과 범위는 [Caddy 운영](../docs/operations/caddy-ingress.md#로컬-검증)을 따릅니다.
