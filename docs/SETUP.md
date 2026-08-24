# 개발 환경 설정 및 실행

## 사전 준비

- Git
- Docker
- Docker Compose
- Docker Buildx

## 실행

프로젝트 루트의 `.env.example`을 복사해 개발자별 `.env`를 생성한다. `.env`는 Git에
추적하지 않으며 PostgreSQL 비밀번호를 포함한다.

```shell
cp .env.example .env
```

아래 명령은 기본 Compose 파일과 로컬 오버레이를 함께 사용한다. 오버레이는 PostgreSQL을
`127.0.0.1`에만 노출하고 백엔드의 `local` 프로필을 활성화한다.

### 전체 실행

프로젝트 루트에서 백엔드와 PostgreSQL을 빌드하고 실행한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

### PostgreSQL 실행

PostgreSQL만 실행한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d db
```

### 백엔드 실행

백엔드를 빌드하고 실행한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build backend
```

## 상태와 스키마 확인

서비스 상태와 백엔드 기동 로그를 확인한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml ps
docker compose -f compose.yaml -f compose.local.yaml logs backend
```

현재 JPA 엔티티가 생성한 PostgreSQL `public` 스키마 테이블을 확인한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml exec db sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \
  \"SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;\""
```

`local` 프로필은 Hibernate의 `update` 전략으로 로컬 스키마를 맞춘다. 이는 운영 환경의
마이그레이션 수단이 아니다.

## DBeaver 연결

DBeaver에서 PostgreSQL 연결을 만들고 다음 값을 입력한다.

| 항목 | 값 |
| --- | --- |
| Host | `127.0.0.1` |
| Port | `.env`의 `POSTGRES_PORT` (기본값 `5432`) |
| Database | `.env`의 `POSTGRES_DB` |
| Username | `.env`의 `POSTGRES_USER` |
| Password | `.env`의 `POSTGRES_PASSWORD` |

연결 후 `Databases` → `<POSTGRES_DB>` → `Schemas` → `public` → `Tables`에서 테이블을 조회한다.

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```

이 명령은 컨테이너만 종료하고 로컬 PostgreSQL 데이터 볼륨은 유지한다.

## 로컬 데이터 초기화

다음 명령은 **로컬 PostgreSQL 볼륨과 모든 로컬 데이터를 삭제**한다. 복구할 수 없는 데이터만
초기화할 때 실행한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml down -v
```
