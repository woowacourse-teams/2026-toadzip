# 개발 환경 설정 및 실행

## 사전 준비

- Git
- Docker
- Docker Compose
- Docker Buildx

## 실행

프로젝트 루트의 `.env.example`을 참고해 `.env`를 생성한다.

### 전체 실행

프로젝트 루트에서 백엔드와 PostgreSQL을 빌드하고 실행한다.

```shell
docker compose up -d --build
```

### PostgreSQL 실행

PostgreSQL만 실행한다.

```shell
docker compose up -d db
```

### 백엔드 실행

백엔드를 빌드하고 실행한다.

```shell
docker compose up -d --build backend
```

## 종료

```shell
docker compose down
```
