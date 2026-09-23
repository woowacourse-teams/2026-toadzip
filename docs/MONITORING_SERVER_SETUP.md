# 모니터링 서버 설정

## 환경변수

`infra/monitoring/.env.example`을 참고해서 `infra/monitoring/.env`를 생성한다.

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=
```

## 실행

```shell
docker compose --env-file infra/monitoring/.env -f compose.monitoring.yaml up -d
```

## 종료

```shell
docker compose --env-file infra/monitoring/.env -f compose.monitoring.yaml down
```
