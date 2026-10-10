# 개발 서버 설정

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

```dotenv
SPRING_PROFILES_ACTIVE=dev
LOKI_PUSH_URL=http://<모니터링 서버 사설 IP>:3100/loki/api/v1/push
PRIMARY_DB_HOST=<DB 서버 사설 IP>
PRIMARY_DB_PORT=5432
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=<DB 서버 사설 IP>
SHARED_DB_PORT=5434
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

## 실행

기존 서비스에 개인정보 기능을 처음 배포할 때는 아래 일괄 시작 명령 대신
[개인정보 DB 배포 절차](../backend/docs/operations/PRIVACY.md)를 따른다. 기존 writer 중지,
Flyway 적용과 DB 점검, 프론트 시작 순서가 필요하다.

새 빈 DB로 서버를 준비할 때는 아래 명령을 사용한다. 아직 HTTPS를 구성하지 않았다면
`.env`에 HTTPS용 `COMPOSE_FILE`을 설정하지 않는다.

```shell
docker compose up -d --build --wait --wait-timeout 300
```

## HTTPS 적용

개발 서버 도메인은 `dev.bokduckbang.com`이다. 개발과 운영은 서로 다른 EC2에서
같은 HTTPS 구성을 사용한다.

인증서 최초 발급, 서버 환경변수, 권한, 갱신과 확인 절차는
[Certbot 공통 적용 안내](../infra/certbot/README.md)를 따른다. 해당 절차에서
`.env`의 `COMPOSE_FILE`을 설정한 뒤에는 위 실행 명령을 그대로 사용한다.

## 종료

```shell
docker compose down
```
