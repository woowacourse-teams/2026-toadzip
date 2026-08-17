# 개발 환경 설정 및 실행

## 사전 준비

- Git
- Docker
- Docker Compose

## 실행

프로젝트 루트의 `.env.example`을 참고해 `.env`를 생성한다.

프로젝트 루트에서 백엔드와 PostgreSQL을 빌드하고 실행한다.

```shell
docker compose up -d --build
```

## 종료

```shell
docker compose down
```
