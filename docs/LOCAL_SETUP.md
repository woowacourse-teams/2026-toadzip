# 로컬 개발 환경

## 환경 변수

프로젝트 루트에서 예시 파일을 복사한다. `.env`는 Git에 추적하지 않는다.

```shell
cp .env.example .env
```

## 실행

로컬 오버레이로 PostgreSQL 포트를 열고 백엔드의 `local` 프로필을 활성화한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml up -d --build
```

## 테이블 확인

`local` 프로필은 Hibernate `update` 전략으로 현재 엔티티의 테이블을 생성한다.

```shell
docker compose -f compose.yaml -f compose.local.yaml exec db sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \
  \"SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;\""
```

## 종료

```shell
docker compose -f compose.yaml -f compose.local.yaml down
```

로컬 PostgreSQL 데이터는 볼륨에 유지된다.
