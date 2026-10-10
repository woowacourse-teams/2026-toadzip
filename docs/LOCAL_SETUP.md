# 로컬 환경 설정

## 환경변수

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
실제 비밀번호는 `.env.example`이나 문서에 넣지 않는다.

사용자 소셜 로그인 설정은
[백엔드 소셜 로그인 문서](../backend/docs/user-social-login.md)의 로컬 설정 절차를 따른다.

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml up -d --build
```

새 빈 primary DB는 백엔드 시작 시 Flyway가 초기 스키마와 후속 마이그레이션을 적용한다.
기존 로컬 DB에 Flyway 이력이 없다면 먼저 백업하고
[Flyway 도입 절차](../backend/docs/flyway-adoption.md)의 스키마·제약 확인을 진행한다.
백엔드 기동 시 자동 기준선과 후속 마이그레이션이 적용된다. 알려진 제약 충돌이 있는 DB는
문서의 수동 보정 절차가 필요하다.

## 관리자 계정

로컬 관리자 계정이 없으면 `.env`에 다음 값을 설정하고 백엔드를 한 번 기동한다.
계정 생성 후 `ADMIN_BOOTSTRAP_ENABLED=false`로 되돌린다. 실제 비밀번호는 Git에 넣지 않는다.

```dotenv
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_LOGIN_IDENTIFIER=로컬_관리자_식별자
ADMIN_PASSWORD=로컬_관리자_비밀번호
```

관리자 단지·공고 작업은 [데이터 관리](../backend/docs/admin-data-management.md)를 따른다.

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml down
```
