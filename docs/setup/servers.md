# 개발·운영 서버 설정

아래 명령은 각 애플리케이션 서버의 저장소 루트에서 실행한다.
실제 비밀번호는 Git이 추적하지 않는 서버별 `.env`에서만 관리하며 전체 내용을 출력하거나 `source .env`로 실행하지 않는다.

## 환경변수

루트 [환경변수 예시](../../.env.example)를 참고해 `.env`를 준비한다. 공통 설정은 다음과 같다.

```dotenv
LOKI_PUSH_URL=http://<모니터링 서버 사설 IP>:3100/loki/api/v1/push
PRIMARY_DB_HOST=<DB 서버 사설 IP>
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=<DB 서버 사설 IP>
SHARED_DB_PORT=5434
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

공통 설정에 아래의 `SPRING_PROFILES_ACTIVE`와 `PRIMARY_DB_PORT`를 해당 환경 값으로 반드시 추가한다.

| 항목 | 개발 | 운영 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` |
| `PRIMARY_DB_PORT` | `5432` | `5433` |
| 배포 브랜치 | `develop` | `main` |
| 서비스 도메인 | `dev.bokduckbang.com` | `bokduckbang.com` |

DB 포트는 현재 [DB 서버 구성](../operations/database/server.md)의 환경별 호스트 포트다.
실제 서버의 포트를 변경했다면 해당 서버의 설정과 대조한다. 개발·운영 DB와 환경값을 서로 복사하지 않는다.

## 실행

기존 서비스에 개인정보 기능을 처음 배포할 때는 아래 일괄 시작 대신
[개인정보 DB 배포 절차](../operations/database/privacy-deployment.md)를 따른다.
기존 writer 중지, Flyway 적용·DB 점검, 프론트 시작 순서가 필요하다.
현재 자동 배포와 특별 배포의 차이는 [배포 운영](../operations/deployment.md)을 확인한다.

새 빈 DB로 서버를 준비할 때는 다음 명령을 사용한다. 아직 HTTPS를 구성하지 않았다면
`.env`에 HTTPS용 `COMPOSE_FILE`을 설정하지 않는다.

```shell
docker compose up -d --build --wait --wait-timeout 300
```

## HTTPS 적용

개발과 운영은 서로 다른 EC2에서 같은 HTTPS 구성을 사용한다.
개발에서 검증한 뒤 운영에 적용하며 인증서는 각 서버의 도메인으로 별도 발급한다.

인증서 최초 발급·환경변수·권한·갱신·확인은 [HTTPS 운영](../operations/https.md)을 따른다.
해당 절차에서 `.env`의 `COMPOSE_FILE`을 설정한 뒤에는 위 실행 명령을 그대로 사용한다.
실제 HTTPS 접속·인증서 갱신·로그인 확인까지 완료한 뒤 해당 환경의 준비 완료로 판단한다.

## 종료

```shell
docker compose down
```
