# 환경 설정

Git, Docker, Docker Compose와 Buildx를 준비한다. 로컬·개발·운영 환경의 비밀번호는 각 `.env`에 설정하고 Git에 넣지 않는다.

## 개인정보 최초 배포

기존 서비스에 개인정보 기능을 처음 배포할 때는 [개인정보 배포](../backend/docs/operations/PRIVACY.md)를 따른다.
기존 writer를 중지하고 `사전 검사 → 백엔드 기동 → 사후 검사 → 프론트 기동` 순서로 교체한다.
아래 일괄 실행 명령은 새 빈 DB를 준비할 때 사용한다.

서버·프론트·배포 설정을 포함한 같은 릴리스를 사용한다. 구형 화면과 새 알림 API를 혼용하지 않는다.
저장소 밖 자동 배포가 있다면 통합 릴리스 준비 전 중간 변경이 배포되지 않도록 확인한다.

## 로컬

### 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

```dotenv
SPRING_PROFILES_ACTIVE=local
LOKI_PUSH_URL=http://loki:3100/loki/api/v1/push
GRAFANA_ADMIN_PASSWORD=
PRIMARY_DB_HOST=db
PRIMARY_DB_PORT=5432
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=db-shared
SHARED_DB_PORT=5432
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

DB·Grafana 비밀번호는 로컬 `.env`에 채운다. Grafana 비밀번호가 비면 Compose 실행이 실패한다.

로그인은 [로그인 설정](../backend/docs/features/USER.md#공급자와-환경-설정)을 따른다.

### 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml up -d --build
```

새 빈 primary DB는 백엔드 시작 시 Flyway가 초기 스키마와 후속 마이그레이션을 적용한다.
기존 DB에 Flyway 이력이 없다면 먼저 백업하고 [DB 운영](../backend/docs/operations/DATABASE.md#flyway)의 스키마·제약 확인과 필요한 보정을 마친다.
개인정보 앱은 이력이 없는 기존 DB를 자동 보정하지 않고 기동을 차단한다.
최초 Flyway 도입은 백업·격리 검증·별도 승인을 거쳐 먼저 완료한다.

### 관리자 계정

로컬 관리자 계정이 없으면 `.env`에 다음 값을 설정하고 백엔드를 한 번 기동한다.
계정 생성 후 `ADMIN_BOOTSTRAP_ENABLED=false`로 되돌린다.

```dotenv
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_LOGIN_IDENTIFIER=로컬_관리자_식별자
ADMIN_PASSWORD=로컬_관리자_비밀번호
```

관리자 단지·공고 작업은 [데이터 관리](../backend/docs/operations/ADMIN_DATA.md#수정삭제)를 따른다.

### 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml down
```

## 개발·운영 서버

### 환경변수

루트 `.env.example`을 복사해 각 서버의 `.env`를 만든다. 비밀번호는 서버 파일에 직접 설정하며 Git에 넣지 않는다.

| 설정 | 개발 | 운영 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` |
| `PRIMARY_DB_PORT` | `5432` | `5433` |
| 도메인 | `dev.bokduckbang.com` | `bokduckbang.com` |

공통 설정은 다음과 같다.

```dotenv
LOKI_PUSH_URL=http://<모니터링 서버 사설 IP>:3100/loki/api/v1/push
PRIMARY_DB_HOST=<DB 서버 사설 IP>
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=<DB 서버 사설 IP>
SHARED_DB_PORT=5434
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

### 실행과 HTTPS

새 빈 DB를 준비할 때는 HTTP와 백엔드부터 실행한다. HTTPS 구성 전에는 `.env`에 HTTPS용 `COMPOSE_FILE`을 설정하지 않는다.

```shell
docker compose up -d --build --wait --wait-timeout 300
```

[HTTPS 설정](../infra/certbot/README.md)에서 인증서·권한·서버 환경변수·자동 갱신을 구성한다.
개발 EC2에서 검증한 뒤 별도 운영 EC2에 적용하고 운영 인증서는 운영 서버에서 따로 발급한다.
HTTPS 구성 후에도 위 실행 명령을 사용한다. 실제 HTTPS 접속·갱신·로그인 확인을 마쳐야 운영 준비가 끝난다.

### 종료

```shell
docker compose down
```

파일 크기·임시 디스크 설정은 [공고 첨부파일](../backend/docs/features/ANNOUNCEMENTS.md#용량과-시간),
로그인은 [소셜 로그인](../backend/docs/features/USER.md#로그인)을 따른다.

## 모니터링

### 모니터링 서버

`infra/monitoring/.env.example`을 참고해서 `infra/monitoring/.env`를 생성한다.

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=
```

#### 실행

```shell
docker compose --env-file infra/monitoring/.env -f compose.monitoring.yaml up -d
```

#### 종료

```shell
docker compose --env-file infra/monitoring/.env -f compose.monitoring.yaml down
```

### 로그와 메트릭

관리 포트 `8081`의 `/actuator/prometheus`에서 HTTP 요청 수·시간과 기능별 지표를 확인한다.
HTTP 로그의 `event=http.request.completed`, method·path·status·durationMs와 오류 응답의 `traceId`로 요청을 찾는다.
수집 지표 해석은 [공고 수집](../backend/docs/operations/ANNOUNCEMENT_COLLECTION.md#성능-확인)을 따른다.

| 구성 | 파일 |
|---|---|
| 로그 전송·저장 | [Alloy](../infra/alloy/config.alloy), [Loki](../infra/loki/loki-config.yml) |
| 메트릭 수집 | [Prometheus](../infra/prometheus/prometheus.yml) |
| 대시보드 데이터 소스 | [Grafana](../infra/grafana/provisioning/datasources/prometheus.yml) |
