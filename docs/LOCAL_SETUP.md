# 로컬 환경 설정

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

## 실행

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```
