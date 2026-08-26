# 로컬 개발 환경

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## DB GUI 연결

```text
Host: 127.0.0.1
Port: ${POSTGRES_PORT:-5433}
Database: toadzip
Username: toadzip
Password: .env의 POSTGRES_PASSWORD
```

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```
