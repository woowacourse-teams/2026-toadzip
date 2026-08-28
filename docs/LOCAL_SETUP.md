# 로컬 환경 설정

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

```dotenv
PRIMARY_DB_HOST=db
PRIMARY_DB_PORT=5432
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=db-shared
SHARED_DB_PORT=5432
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```
