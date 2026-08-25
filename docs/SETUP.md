# 개발 환경 설정 및 실행

## 사전 준비

- Git
- Docker
- Docker Compose
- Docker Buildx

## 실행

프로젝트 루트의 `.env.example`을 참고해 `.env`를 생성한다.

로컬 실행은 `compose.local.yaml`을 함께 사용한다. 백엔드는 `local` 프로필로 실행되며,
Hibernate `ddl-auto: update`가 현재 JPA 엔티티에 필요한 테이블을 생성하거나 갱신한다.
생성된 데이터는 Docker의 `postgres_data` 볼륨에 보존된다.

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

### PostgreSQL 접속

```text
Host: 127.0.0.1
Port: .env의 POSTGRES_PORT (기본값 5433)
Database: .env의 POSTGRES_DB
Username: .env의 POSTGRES_USER
Password: .env의 POSTGRES_PASSWORD
```

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```
