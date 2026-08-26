# 로컬 개발 환경

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## API 문서

로컬 프로필에서만 Swagger UI와 OpenAPI 명세를 제공한다.

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

`.env`에서 `BACKEND_PORT`를 변경했다면 URL의 `8080`을 같은 포트로 바꾼다.

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
