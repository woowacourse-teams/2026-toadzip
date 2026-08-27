# 개발 환경 설정 및 실행

## 사전 준비

- Git
- Docker
- Docker Compose
- Docker Buildx

## 실행

프로젝트 루트의 `.env.example`을 참고해 `.env`를 생성한다.

로컬 PostgreSQL 설정은 [로컬 개발 환경](LOCAL_SETUP.md)을 참고한다.

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

### 프론트엔드 컨테이너 실행

프론트엔드는 기존 DB·백엔드 Compose와 독립적으로 실행한다. 지도 설정과 공개
환경값은 [프론트엔드 README](../frontend/README.md)를 참고한다.

지도 없이도 이미지는 빌드되고 실행된다.

```shell
docker compose --project-name toadzip-frontend \
  --file compose.frontend.yaml \
  up --detach --build --wait
```

지도를 표시하려면 Git이 추적하지 않는 `frontend/.env.local`에
`VITE_NAVER_MAPS_CLIENT_ID`를 설정하고 빌드 시 전달한다.

```shell
docker compose --project-name toadzip-frontend \
  --file compose.frontend.yaml \
  --env-file frontend/.env.local \
  up --detach --build --wait
```

기본 접속 주소는 `http://localhost`, 상태 확인 주소는
`http://localhost/healthz`다. 백엔드 연결 전에는 `/api` 요청이 의도적으로
`503` JSON을 반환한다.

## 종료

```shell
docker compose down
```

프론트엔드 컨테이너는 같은 프로젝트 이름과 Compose 파일로 종료한다.

```shell
docker compose --project-name toadzip-frontend \
  --file compose.frontend.yaml down --remove-orphans
```
