# 공통 스크립트

배포 설정을 검사하고 DB 전환·복원 작업을 지원하는 스크립트를 관리합니다.

## 스크립트 안내

| 스크립트 | 용도·조건 |
| --- | --- |
| [`check-flyway-migrations.py`](check-flyway-migrations.py) | Git에 커밋된 Flyway SQL의 버전 중복, develop보다 낮거나 같은 신규 버전, 기존 SQL의 수정·삭제·이름 변경을 검사합니다. |
| [`test-https.py`](test-https.py) | 임시 인증서로 로컬 HTTPS·Nginx 갱신 hook을 검증합니다. `--config-only`는 Compose 설정과 hook 검사만 수행합니다. |
| [`prepare-bluegreen.py`](prepare-bluegreen.py) | `infra/runtime/bluegreen/` 설정·정적 파일 디렉터리를 준비합니다. 기존 상태는 덮어쓰지 않습니다. |
| [`release-bluegreen.py`](release-bluegreen.py) | CI 커밋·서버 준비를 확인하고 두 이미지를 빌드한 뒤 공통 잠금 안에서 백엔드·프론트를 순차 배포합니다. [CI 운영 절차](../docs/operations/deployment.md)를 따릅니다. |
| [`rollback-bluegreen.py`](rollback-bluegreen.py) | 보관된 프론트·백엔드 이미지의 지정 커밋을 확인하고 복구합니다. [수동 실행·기록·제약](../docs/operations/deployment.md#직전-배포-롤백)을 따릅니다. |
| [`deploy-bluegreen.sh`](deploy-bluegreen.sh), [`switch-frontend.sh`](switch-frontend.sh), [`switch-backend.sh`](switch-backend.sh) | Linux/POSIX용 프론트·백엔드 배포·전환 명령입니다. [`bluegreen.py`](bluegreen.py)의 공통 잠금·복구 로직을 사용합니다. |
| [`check-notification-schema.sh`](check-notification-schema.sh), [`check-notification-schema.sql`](check-notification-schema.sql) | 선택한 개발·운영 DB의 알림 스키마와 Flyway 이력을 읽기 전용으로 검사합니다. |
| [`check-privacy-schema.sh`](check-privacy-schema.sh), [`check-privacy-schema.sql`](check-privacy-schema.sql) | 개인정보 배포 전후 PRIMARY DB의 폐기된 변경 흔적·독립 Flyway 이력·신규 전용 테이블을 읽기 전용으로 검사합니다. [배포 순서](../docs/operations/database/privacy-deployment.md)를 따릅니다. |
| [`flyway-login-constraint-prepare.sql`](flyway-login-constraint-prepare.sql) | **DB 변경:** Flyway 이력이 없는 기존 DB에서 로그인 식별자 고유 제약의 이름을 바꿔 전환을 준비합니다. |
| [`flyway-login-constraint-finalize.sql`](flyway-login-constraint-finalize.sql) | **DB 변경:** 지정된 Flyway 마이그레이션 성공 후 중복된 기존 고유 제약을 제거합니다. |
| [`reset-restored-notifications.sql`](reset-restored-notifications.sql) | **데이터 삭제:** 격리된 복원 DB에서 알림 신청·이메일 설정·미완료 취소 요청을 비웁니다. |

## 사용 기준

### Linux 프론트·백엔드 Blue/Green 도구

Bash, Python 3, Docker Compose가 필요합니다. Blue/Green 서비스를 정의한
`compose.bluegreen.yaml`과 실행 중인 Edge·프론트·백엔드·DB를 사용합니다.
`--service frontend`가 기본값이며, `--service backend`를 지정하면 백엔드만 배포합니다.
각 명령은 선택한 서비스의 upstream만 변경하며 프론트와 백엔드를 한 번에 전환하지 않습니다.
전환 후 이전 컨테이너를 정지하므로 서버에서는 Edge/Caddy가 실제 진입점인 상태에서 실행합니다.
기존 프론트 컨테이너의 공개 포트로 직접 들어오는 요청이 남아 있으면 먼저 진입점 전환을 완료해야 합니다.
아래 초기 준비는 기존 프론트엔드·백엔드가 실행 중인 로컬 검증 환경을 기준으로 합니다.
실제 서버의 최초 진입점 전환·HTTPS·CI 연결 절차는 별도로 준비해야 합니다.

설정 원본은 `infra/nginx/edge/default.conf`에 두고, 준비 명령이 `infra/runtime/bluegreen/edge/default.conf`를 생성합니다.
기존 런타임 파일·연결 색상·정적 파일은 보존합니다. 배포에는 `.local` 디렉터리가 필요하지 않습니다.
아래 Edge 재생성은 마운트를 바꾸기 위한 최초 준비이며, Edge가 실제 사용자 요청을 받는 서버에서
그대로 실행하면 일시 중단될 수 있습니다. 런타임 디렉터리는 Docker 실행 호스트에 있어야 합니다.

```bash
python3 -B scripts/prepare-bluegreen.py
export EDGE_NGINX_CONF_DIR="$(pwd)/infra/runtime/bluegreen/edge"
export EDGE_NGINX_ASSET_DIR="$(pwd)/infra/runtime/bluegreen/assets"
docker compose up -d --no-deps --force-recreate edge-nginx

# 이미 빌드한 로컬 이미지 이름으로 교체합니다. 기존 frontend에서도 첫 Blue로 전환할 수 있습니다.
bash scripts/deploy-bluegreen.sh --image toadzip-frontend:release-533

# 백엔드 후보 기동·전환. 기존 backend에서도 첫 Blue로 전환할 수 있습니다.
bash scripts/deploy-bluegreen.sh --service backend --image toadzip-backend:release-533

# 기존 Blue/Green 컨테이너로 명시적 전환·원복
bash scripts/switch-frontend.sh --color blue
bash scripts/switch-backend.sh --color blue
```

기존 런타임 설정은 준비 명령이 덮어쓰지 않습니다. 다른 경로에서 옮길 때는
`python3 -B scripts/prepare-bluegreen.py --from-config /기존경로/edge/default.conf`로 원본을 복사할 수 있습니다.
이 명령은 기존 파일·마운트를 옮기거나 삭제하지 않습니다. 기존 Edge에는 새 마운트를 별도 적용해야 합니다. 이전 버전의 런타임에는
[`infra/nginx/edge/default.conf`](../infra/nginx/edge/default.conf)의 `/assets/`·`@frontend_assets` 위치와
`mjs` 타입을 반영하되 현재 `frontend_active`·`backend_active`는 유지한 뒤 `nginx -t`·reload를 수행합니다.
Edge에는 설정 디렉터리와 별도로 `/var/cache/toadzip/assets` 읽기 전용 마운트가 필요합니다.
새 마운트는 컨테이너 재생성이 필요하므로 실제 진입점 전환 전 초기 준비에서 적용합니다.
이미 Edge가 공개 진입점이면 위 재생성 명령을 무중단 변경으로 간주하지 않습니다.
도구는 Edge가 시험용 정적 파일을 실제 제공하는지 확인하며, 구형 설정에서는 프론트 배포를 중단합니다.

- 배포는 현재 전달 대상의 반대 색상을 선택합니다. `--image`를 로컬 이미지 ID로 고정하고,
  후보만 `--no-build --pull never --no-deps`로 재생성하여 healthy를 기다립니다.
- 준비부터 전환 확인·실패 복구까지 동일한 `deploy.lock`을 유지합니다. 프론트·백엔드·전환 명령이
  같은 잠금을 사용하므로 두 작업을 동시에 실행할 수 없습니다. 잠금은 실제 Edge 설정 디렉터리의
  부모에 저장하여 별칭 경로로 같은 설정을 가리켜도 공유합니다.
- 새 설정은 임시 파일에서 원자적으로 교체하고, 실제 마운트 내용·`nginx -t`·reload를 확인합니다.
  고유 요청의 대상 컨테이너 로그, 프론트 응답과 백엔드 UP 응답으로 실제 전달을 검증합니다.
  백엔드는 대상 컨테이너의 8081 관리 health와 DB를 조회하는 공고 목록 API도 확인합니다.
  `/__edge-probe-*`의 200 또는 `/api/edge-probe-*`의 404 요청 로그는 전달 확인을 위한 정상 동작입니다.
  `/assets/edge-archive-probe-*` 파일은 아카이브 마운트·실제 제공 경로를 확인한 직후 제거합니다.
- 전환 이후 실패·일반 인터럽트 시 이전 설정을 복구하고 이전 컨테이너의 요청 수신까지 확인합니다.
  강제 종료·호스트 장애는 자동 복구를 보장하지 않습니다. 실패 시 출력된 `.conf.bak`을 사용해
  런타임 설정을 복구하고 `nginx -t` 후 reload·응답을 확인합니다.
- reload 직전 Edge worker의 PID와 시작 시각을 저장하고, 이 세대가 모두 종료된 뒤 이전 컨테이너를
  최대 40초의 종료 유예로 정지합니다. 삭제하거나 이미지를 정리하지 않습니다.
  [Nginx graceful reload](https://nginx.org/en/docs/control.html)는 이전 worker에서 기존 요청을 마칩니다.
  `--drain-timeout` 기본 330초 안에 끝나지 않으면 이전 경로로 복구하고 이전 컨테이너를 유지합니다.
  다음 배포도 남아 있는 draining worker를 먼저 기다리므로 사용 중인 후보를 재생성하지 않습니다.
  장기 스트림·WebSocket은 대기 시간을 넘길 수 있으며 강제로 종료하지 않습니다.
- 명시적 전환·원복은 정지된 기존 컨테이너를 `docker start`로 재시작하고 healthy를 기다립니다.
  저장된 이미지·환경을 그대로 사용하며 최신 태그로 재생성하지 않습니다. 다음 배포가 같은 색상을
  재생성하면 그보다 오래된 컨테이너로의 원복은 이 명령만으로 할 수 없습니다.
- 후보 기동·전환 검증·요청 종료 대기 실패는 이전 컨테이너를 정지하지 않습니다.
  정리 단계의 Docker 오류·강제 종료 감지는 새 경로를 유지한 채 실패로 보고합니다.
  실제 컨테이너 상태를 확인한 뒤 복구하고, 성공한 배포로 간주하지 않습니다.
- 프론트 배포는 현재·후보·재생성할 이전 색상의 `/usr/share/nginx/html/assets/`를 런타임 `assets/`에 복사합니다.
  Edge가 과거·현재 파일을 직접 제공하므로 이전 컨테이너를 정지하거나 색상을 재사용해도 기존 화면의
  지연 로딩 파일을 받을 수 있습니다. 같은 URL에 다른 내용이 있으면 배포를 거부합니다.
  Vite의 해시 파일을 대상으로 하며 `/index.html`·서비스 워커·`/region-boundaries/` 등 고정 URL은 보관하지 않습니다.
  아카이브는 자동 삭제하지 않습니다. 디스크 사용량을 관찰하고 활성 구버전 화면의 유지 기간을 결정한 뒤
  별도 승인된 정리 절차를 준비합니다. 이전 화면과 새 API의 계약 호환성은 릴리스별로 확인합니다.
- 기본 프로젝트는 `2026-toadzip`, 런타임은 `infra/runtime/bluegreen`, 확인 주소는
  `http://127.0.0.1:18080`입니다. `--project`, `--runtime`, `--url`로 변경할 수 있습니다.
  `--compose-override`는 여러 번 지정할 수 있습니다. 예: `--compose-override compose.local.yaml`.
  인증서 검증을 생략하는 옵션은 제공하지 않으며, 확인 주소의 리다이렉트를 따라가지 않습니다.
- 배포·롤백 도구는 공통 잠금을 사용합니다. 도구 실행 중에는 런타임 설정을 수동으로 변경하지 않습니다.
- 회귀 검사는 저장소 루트에서 `python3 -B tests/deployment/test_bluegreen.py`로 실행합니다.
  Docker·실제 `.env`를 사용하지 않고 후보 실패·전환 실패·원복·프론트/백엔드 잠금 경합을 검사합니다.
  실제 Linux 컨테이너에서 Bash 명령을 실행하는 백엔드 통합 시험은 [검증 안내](../tests/README.md#실제-백엔드-bluegreen-로컬-검증)를 따릅니다.
- 백엔드 후보 기동 시 Flyway가 먼저 실행됩니다. 자동 복구는 **요청 전달 설정만** 되돌리며 이미 반영된
  DB 변경은 취소하지 않습니다. 서로 다른 릴리스의 공존·앱 원복 가능성은 후보 기동 전에 확인합니다.
  [특별 DB 배포](../docs/operations/deployment.md#db-전환계약-변경이-필요한-특별-배포)와
  [알림 동시 실행](../docs/operations/notifications.md#bluegreen-동시-실행)의 플래그·ADC 전달 조건을 따릅니다.

### 공통 검사·DB 도구

검사 기준은 [기여 규칙](../CONTRIBUTING.md), DB 점검은 [알림 데이터 안내](../docs/operations/notifications.md), HTTPS 검증은 [HTTPS 가이드](../docs/operations/https.md)를 참고합니다. Flyway 전환·복원 SQL은 각 파일의 실행 전제와 대상 DB 확인 조건을 따릅니다. 복원 SQL은 기존 알림 자료를 삭제하므로 개인정보 신규 테이블의 배포·파기 절차로 사용하지 않습니다.

Flyway 검사는 저장소 루트에서 `python3 scripts/check-flyway-migrations.py --base-ref origin/develop`로 실행합니다.
`origin/develop`은 로컬에 가져온 기준이므로 최신 상태인지 확인합니다. 검사 대상은 작업 파일이 아닌 `HEAD`의 커밋입니다.
`db/migration`과 `db/privacy`는 이력이 독립적이므로 신규 `V` 버전을 각 경로의 develop 최신 `V` 버전과 비교합니다. 기존 baseline(`B`) SQL도 변경할 수 없고,
동일한 `B` 버전의 중복을 차단합니다. baseline과 일반(`V`) SQL이 같은 버전을 사용하는 기존 구조는 허용합니다.
백엔드 CI는 develop 대상 PR의 병합 결과를 해당 PR의 develop 기준 커밋과 비교합니다. 그 외 PR·배포 실행은 버전 중복을 검사합니다.
이후 develop이 변경되면 최신 기준으로 검사를 다시 통과하도록 PR을 갱신해야 합니다. 이 검사는 실제 DB의 적용 이력이나 SQL 실행 결과를 검증하지 않습니다.
