# 개발 서버 설정

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

```dotenv
PRIMARY_DB_HOST=<DB 서버 사설 IP>
PRIMARY_DB_PORT=5432
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=<DB 서버 사설 IP>
SHARED_DB_PORT=5434
SHARED_DB_PASSWORD=
```

## 실행

```shell
docker compose up -d --build backend
```

## 종료

```shell
docker compose down
```
