# 로컬 환경 설정

아래 명령은 저장소 루트에서 실행한다. 기본 실행은 앱과 PostgreSQL만 준비하며 모니터링 컨테이너를 띄우지 않는다.

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

```dotenv
SPRING_PROFILES_ACTIVE=local
LOKI_PUSH_URL=http://loki:3100/loki/api/v1/push
PRIMARY_DB_HOST=db
PRIMARY_DB_PORT=5432
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=db-shared
SHARED_DB_PORT=5432
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

`PRIMARY_DB_PASSWORD`, `SHARED_DB_PASSWORD`는 Git이 추적하지 않는 로컬 `.env`에 직접 채운다.
실제 비밀번호를 `.env.example`이나 문서에 기록하지 않는다. `LOKI_PUSH_URL`은 Compose 설정에
필요한 값이며, 아래 기본 실행에서는 Alloy·Loki가 시작되지 않아 로그를 전송하지 않는다.
로컬 HTTP에서는 HTTPS용 `COMPOSE_FILE`을 설정하지 않는다.

사용자 소셜 로그인 설정은
[백엔드 소셜 로그인 문서](../features/authentication/social-login.md)의 로컬 설정 절차를 따른다.

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build --wait db db-shared backend frontend
```

새 빈 primary DB는 백엔드 시작 시 Flyway가 초기 스키마와 후속 마이그레이션을 적용한다.
기존 로컬 DB에 Flyway 이력이 없다면 먼저 백업하고
[Flyway 도입 절차](../operations/database/flyway.md)의 스키마·제약 확인을 진행한다.
개인정보 기능이 포함된 앱은 이력이 없는 기존 DB를 자동 보정하지 않고 기동을 차단한다.
기존 기능의 Flyway 도입은 백업·격리 검증·별도 승인을 거쳐 먼저 완료한다.
[개인정보 배포 경계](../operations/database/privacy-deployment.md)를 확인한다.

## 관리자 데이터 등록 수동 검증

로컬 관리자 계정이 없다면 `.env`에 다음 값을 설정한 뒤 백엔드를 최초 한 번
기동한다. 계정 생성 후에는 `ADMIN_BOOTSTRAP_ENABLED=false`로 되돌린다. 실제
비밀번호는 Git이 추적하는 파일에 기록하지 않는다.

```dotenv
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_LOGIN_IDENTIFIER=로컬_관리자_식별자
ADMIN_PASSWORD=로컬_관리자_비밀번호
```

백엔드와 PostgreSQL을 기동하고, 별도 터미널에서 프론트엔드 개발 서버를 실행한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build --wait db db-shared backend
cd frontend
npm ci
npm run dev
```

브라우저에서 `http://localhost:5173/admin`을 열고 다음 순서로 확인한다.

1. 로컬 관리자 계정으로 로그인한다.
2. 단지 기본 정보, 주소, 시설 정보를 입력하고 `단지 저장`을 누른다.
3. 저장 중에는 버튼이 비활성화되고, 완료 후 성공 메시지와 선택 단지가 표시되는지 확인한다.
4. 선택 단지가 방금 저장한 단지인지 확인한다.
5. 공고 기본 정보, 접수처, 단일 공급행을 입력하고 `공고 저장`을 누른다.
6. 저장 중에는 버튼이 비활성화되고, 완료 후 성공 메시지가 표시되는지 확인한다.
7. 브라우저 네트워크 패널에서 각 POST 직전에 `/api/admin/auth/csrf`를 호출하고,
   응답의 동적 헤더 이름과 토큰 및 세션 쿠키를 POST에 포함했는지 확인한다.

저장된 세 테이블과 외래 키는 다음 쿼리로 확인한다. PostgreSQL 사용자나 DB 이름을
변경했다면 명령의 기본값도 같은 값으로 바꾼다.

```shell
docker compose -f compose.yaml -f compose.local.yaml exec db \
  psql -U "${PRIMARY_DB_USERNAME:-toadzip}" -d "${PRIMARY_DB_NAME:-toadzip}" -c '
    SELECT
      hc.id AS housing_complex_id,
      a.id AS announcement_id,
      sr.id AS supply_row_id,
      sr.housing_complex_id AS supply_row_housing_complex_id,
      sr.announcement_id AS supply_row_announcement_id,
      sr.housing_type_id
    FROM supply_rows sr
    JOIN housing_complexes hc ON hc.id = sr.housing_complex_id
    JOIN announcements a ON a.id = sr.announcement_id
    ORDER BY sr.id DESC
    LIMIT 1;
  '
```

`supply_row_housing_complex_id`와 `housing_complex_id`,
`supply_row_announcement_id`와 `announcement_id`가 각각 같고,
`housing_type_id`가 `null`이어야 한다.

## 선택적인 로컬 로그 수집

로그 수집이 필요할 때만 같은 Compose 프로젝트에 Alloy·Prometheus·Loki를 추가한다.
모니터링 파일은 Grafana 비밀번호를 설정 검사하므로 루트 `.env`에 `GRAFANA_ADMIN_PASSWORD`도
채워야 한다. 이는 HTTPS용 값이 아니며 기본 앱 실행에는 필요 없다.

```shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml up -d alloy prometheus loki
```

공통 네트워크에서 Alloy가 `http://loki:3100/loki/api/v1/push`로 전송한다.
이 명령은 Grafana와 Caddy를 실행하지 않는다. 모니터링 파일의 Caddy는 80·443포트를 사용해
기본 프론트와 충돌하며, Grafana도 실제 모니터링 도메인·HTTPS 쿠키 설정을 사용한다.
로컬에서 전체 모니터링 Compose를 앱과 함께 무조건 올리지 않는다.
별도 모니터링 서버 구성은 [모니터링 운영](../operations/monitoring.md)을 따른다.

운영 서비스를 사용하지 않는 격리 수집 회귀 검증은 저장소 루트의
`python3 tests/monitoring/log_collection.py`로 수행할 수 있다. 테스트 전용 컨테이너와 볼륨을
생성·정리하므로 Docker·로컬 포트·디스크 여유를 먼저 확인한다.

## 종료

기본 앱만 실행했다면 다음 명령을 사용한다. DB 볼륨을 보존하며 `-v`는 붙이지 않는다.

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```

선택적 로그 수집까지 실행했다면 같은 프로젝트의 전체 로컬 구성을 함께 종료한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml down
```
