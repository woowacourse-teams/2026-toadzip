# 환경 설정

Git, Docker, Docker Compose와 Buildx를 준비한다.
로컬 개발은 [로컬 설정](LOCAL_SETUP.md), 개발·운영 서버는 아래 절차를 따른다.
DB와 모니터링은 [DB 설정](../infra/db/SETUP.md), [모니터링 설정](MONITORING_SERVER_SETUP.md)을 따른다.

## 개발·운영 서버

### 환경변수

루트 `.env.example`을 복사해 각 서버의 `.env`를 만든다. 비밀번호는 서버 파일에 직접 설정하며 Git에 넣지 않는다.

| 설정 | 개발 | 운영 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` |
| `PRIMARY_DB_PORT` | `5432` | `5433` |
| 도메인 | `dev.bokduckbang.com` | `bokduckbang.com` |

공통 설정은 다음과 같다.

```dotenv
LOKI_PUSH_URL=http://<모니터링 서버 사설 IP>:3100/loki/api/v1/push
PRIMARY_DB_HOST=<DB 서버 사설 IP>
PRIMARY_DB_PASSWORD=
SHARED_DB_HOST=<DB 서버 사설 IP>
SHARED_DB_PORT=5434
SHARED_DB_PASSWORD=
VITE_NAVER_MAPS_CLIENT_ID=
```

### 실행과 HTTPS

처음에는 HTTP와 백엔드부터 실행한다. HTTPS 구성 전에는 `.env`에 HTTPS용 `COMPOSE_FILE`을 설정하지 않는다.

```shell
docker compose up -d --build
```

[HTTPS 설정](../infra/certbot/README.md)에서 인증서·권한·서버 환경변수·자동 갱신을 구성한다.
개발 EC2에서 검증한 뒤 별도 운영 EC2에 적용하고 운영 인증서는 운영 서버에서 따로 발급한다.
HTTPS 구성 후에도 위 실행 명령을 사용한다. 실제 HTTPS 접속·갱신·로그인 확인을 마쳐야 운영 준비가 끝난다.

### 종료

```shell
docker compose down
```

파일 크기·임시 디스크 설정은 [공고 첨부파일](../backend/docs/announcement-attachments.md#용량과-시간),
로그인은 [소셜 로그인](../backend/docs/user-social-login.md)을 따른다.
