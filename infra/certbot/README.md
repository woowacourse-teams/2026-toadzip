# HTTPS 설정과 인증서 갱신

EC2의 Certbot이 인증서를 발급·갱신하고, Docker 안의 Nginx가 그 인증서를 읽어
HTTPS 요청을 받는다. 브라우저의 `/api` 요청은 기존처럼 Nginx에서 백엔드로 전달한다.
호스트에 Nginx를 추가로 설치하지 않는다.

개발과 운영은 **서로 다른 EC2**다. 같은 절차를 각 서버에서 실행하되 도메인과
해당 서버의 `.env`를 구분한다. 개발에서 먼저 확인한 뒤 운영에 적용한다.

| 환경 | 도메인 |
| --- | --- |
| 개발 | `dev.bokduckbang.com` |
| 운영 | `bokduckbang.com` |

## 구성

| 위치 | 관리할 내용 |
| --- | --- |
| Git | Compose 설정, `frontend/nginx/`의 HTTP·HTTPS·공통 요청 처리 설정, 갱신 후 Nginx 반영 스크립트, 이 문서 |
| 각 EC2 | 실제 `.env`, `/var/www/certbot` 확인 파일, `/etc/letsencrypt` 인증서·개인키, 전용 권한 그룹, 설치된 갱신 스크립트와 자동 갱신 일정 |

최초 발급은 `compose.yaml`과 `compose.certbot.yaml`, 발급 후 정상 운영은
`compose.yaml`과 `compose.https.yaml`을 사용한다. HTTPS 설정에도 확인 파일 경로가
있으므로 정상 운영에서 파일 세 개를 함께 지정하지 않는다. 인증서·개인키·실제
`.env`는 Git에 넣지 않는다.

EC2의 `.env`에 추가할 설정은 세 가지다. 로컬 개발에는 필요하지 않다.

| 설정 | 뜻 |
| --- | --- |
| `TOADZIP_TLS_DOMAIN` | 이 EC2가 서비스할 도메인. 인증서 이름·경로와 같아야 한다. |
| `TOADZIP_TLS_GID` | Nginx에 인증서 읽기 권한을 주는 서버 그룹의 숫자 ID. 아래에서 조회한다. |
| `COMPOSE_FILE` | 평소 `docker compose` 명령이 HTTPS 설정도 함께 읽도록 지정한다. |

## 처음 설정

### 1. 확인과 백업

서버의 기존 배포 커밋 SHA를 기록하고 배포할 버전을 확인한다.
개발 서버는 `develop`, 운영 서버는 `main`에 반영된 버전을 사용한다.
브랜치 반영과 릴리스 순서는 [기여 규칙](../../CONTRIBUTING.md#이슈와-브랜치-생성)을 따른다.
아래 명령은 프로젝트 루트에서 실행한다. 같은 Compose 프로젝트에서 `backend`가
실행 중이어야 한다. 초기 서버라면
[환경 설정](../../docs/SETUP.md)에서 대상 서버의 안내를 따라 HTTP와 백엔드부터 준비한다.

```shell
cd /home/ec2-user/2026-toadzip
git rev-parse HEAD
sudo docker compose version
sudo docker compose ps
sudo certbot --version
sudo certbot certificates
```

- 도메인의 DNS A 레코드가 이 EC2의 공개 주소를 가리키는지 확인한다. AAAA 레코드가
  있다면 IPv6로도 연결되어야 한다. 사용하지 않는 잘못된 AAAA 레코드는 정리한다.
- EC2 보안 그룹과 서버 방화벽에서 외부 TCP 80·443 접속을 허용한다. 80은 갱신에도
  사용하므로 HTTPS 전환 후에도 유지한다.
- 백엔드 `8080`에 인터넷에서 직접 접근할 수 없는지 확인한다. 열려 있으면 Nginx의
  HTTPS를 거치지 않고 HTTP로 API에 접근할 수 있다. 관리 포트 `8081`은 필요한
  모니터링 서버의 사설 주소나 보안 그룹에서만 접근하도록 범위를 확인한다.
  호스트 포트를 바꾼 서버는 실제 포트를 기준으로 점검한다. Compose에 포트가
  연결되어 있다는 사실만으로 인터넷 접근 가능 여부를 판단하지 않는다.
- Certbot 설치 방식과 버전을 확인한다. 미설치라면 EC2 OS에 맞게 먼저 설치하며,
  설치 방식이 다른 Certbot을 중복 설치하지 않는다.
- 기존 인증서가 있다면 이름·도메인·만료일과 저장된 인증 방식을 확인한다. 이 구성은
  인증서 이름이 도메인과 같아야 한다. `-0001` 등이 붙은 인증서를 무작정 추가하거나
  기존 인증서를 삭제하지 않는다. 여기서는 상태만 확인한다. 갱신 방식 변경은
  **2단계의 HTTP 확인 파일이 외부에서 제공되는 것을 검증한 뒤** 3단계에서 진행한다.

현재 설정과 프론트엔드 이미지를 보관한다. `.env` 내용은 출력하지 않는다.

```shell
toadzip_https_backup=$(mktemp -d /home/ec2-user/toadzip-https-backup.XXXXXX)
chmod 700 "$toadzip_https_backup"
cp -p .env "$toadzip_https_backup/.env"
chmod 600 "$toadzip_https_backup/.env"
cp -p compose.yaml "$toadzip_https_backup/compose.yaml"
toadzip_frontend_image=$(sudo docker inspect --format '{{.Image}}' "$(sudo docker compose ps -q frontend)")
sudo docker image tag "$toadzip_frontend_image" toadzip-frontend:before-https
printf '백업 위치: %s\n' "$toadzip_https_backup"
```

현재 터미널의 명령에 재사용할 도메인을 지정한다. 개발에서는 아래 값을 사용하고,
운영에서는 `bokduckbang.com`으로 바꾼다. 새로 접속하면 다시 지정한다.

```shell
toadzip_tls_domain=dev.bokduckbang.com
```

### 2. 도메인 확인

Certbot의 `webroot` 방식은 특정 URL에서 확인 파일을 읽을 수 있는지 검사한다.
EC2에 폴더를 만들고, Nginx가 이 폴더를 읽을 수 있도록 연결한다.

```shell
sudo install -d -o root -g root -m 0755 /var/www/certbot/.well-known/acme-challenge
sudo docker compose -f compose.yaml -f compose.certbot.yaml config --quiet
sudo docker compose -f compose.yaml -f compose.certbot.yaml build frontend
sudo docker compose -f compose.yaml -f compose.certbot.yaml up -d --no-deps frontend
```

새 Nginx 설정은 이미지에 포함되므로 첫 적용 때 `build frontend`가 필요하다.
`.env`에 `COMPOSE_FILE`이 있어도 위 명령은 `-f`로 명시한 두 파일을 사용한다.
프론트엔드 컨테이너 재생성 중에는 잠시 연결이 끊길 수 있다.

```shell
printf '%s\n' 'toadzip-certbot-check' | sudo tee /var/www/certbot/.well-known/acme-challenge/toadzip-check >/dev/null
curl --fail --connect-timeout 5 --max-time 10 "http://$toadzip_tls_domain/.well-known/acme-challenge/toadzip-check"
curl --fail --connect-timeout 5 --max-time 10 "http://$toadzip_tls_domain/healthz"
```

첫 요청은 `toadzip-certbot-check`, 두 번째는 `ok`여야 한다. **외부 컴퓨터에서도**
같은 URL에 접속해 확인한다. 실패하면 DNS·포트·컨테이너 문제를 해결한 뒤 발급한다.
확인 후 시험 파일을 지운다.

```shell
sudo rm /var/www/certbot/.well-known/acme-challenge/toadzip-check
```

### 3. 인증서와 읽기 권한

2단계의 외부 HTTP 확인이 성공한 뒤, 인증서 유무에 따라 아래 둘 중 하나를 진행한다.

#### 최초 발급

기존 인증서가 없는 최초 발급은 아래 명령으로 진행한다. 안내에 따라 담당 이메일과
약관 동의를 입력한다. 실패하면 원인을 해결하며 반복 강제 발급하지 않는다.

```shell
sudo certbot certonly --webroot -w /var/www/certbot \
  --cert-name "$toadzip_tls_domain" -d "$toadzip_tls_domain"
```

#### 기존 인증서

위 최초 발급 명령을 반복하지 않는다. 인증서 이름이 해당 도메인과 일치하는지 먼저
확인한다. 다른 이름이거나 여러 도메인을 포함한다면 기존 사용처와 각 도메인의 확인
경로부터 점검한다. 이름이나 도메인을 임의로 바꾸지 않는다.

이미 `webroot` 방식과 `/var/www/certbot`을 사용한다면 변경 없이 다음 권한 준비로
진행한다. 갱신 설정을 바꿔야 한다면 Certbot 2.3.0 이상에서는 다음 명령을 사용한다.

```shell
sudo certbot reconfigure --cert-name "$toadzip_tls_domain" \
  --authenticator webroot --webroot-path /var/www/certbot
```

시험 인증 서버에서 갱신 검증이 성공해야 새 설정이 저장된다.
이 명령은 기존의 웹서버 설치 플러그인이나 중지·시작 hook을 모두 제거하는 명령은
아니다. 이전 Nginx 설치나 서버 중지를 전제로 한 설정이 있다면 현재 Docker 구성에
맞게 정리하고 검증한다. 실패한 상태로 HTTPS 적용 단계로 넘어가지 않는다.
2.2.0 이하 버전은 [Certbot의 버전별 갱신 설정 변경 절차](https://eff-certbot.readthedocs.io/en/stable/using.html#modifying-the-renewal-configuration-of-existing-certificates)를
따른다. 갱신 설정 파일을 직접 고치거나 강제 발급을 반복하지 않는다.

#### 읽기 권한

Nginx는 일반 사용자로 실행된다. 개인키를 모두에게 공개하는 대신 `toadzip-tls`
그룹에 읽기 권한을 주고, 컨테이너 Nginx에 그 그룹을 추가한다.

```shell
getent group toadzip-tls >/dev/null || sudo groupadd --system toadzip-tls
sudo chgrp -R toadzip-tls "/etc/letsencrypt/live/$toadzip_tls_domain" "/etc/letsencrypt/archive/$toadzip_tls_domain"
sudo chmod 0750 "/etc/letsencrypt/live/$toadzip_tls_domain" "/etc/letsencrypt/archive/$toadzip_tls_domain"
sudo find "/etc/letsencrypt/archive/$toadzip_tls_domain" -maxdepth 1 -type f -name 'privkey*.pem' -exec chmod 0640 {} +
sudo find "/etc/letsencrypt/archive/$toadzip_tls_domain" -maxdepth 1 -type f ! -name 'privkey*.pem' -exec chmod 0644 {} +
getent group toadzip-tls | cut -d: -f3
```

마지막 출력이 `TOADZIP_TLS_GID` 값이다. 각 서버에서 별도로 조회한다. 개인키 내용은
읽거나 복사하지 않는다. Certbot은 갱신 시 개인키의 그룹과 권한을 유지한다.
컨테이너에는 해당 도메인의 `live`와 `archive` 폴더만 읽기 전용으로 연결한다.
두 폴더를 같은 절대 경로에 연결해 인증서 심볼릭 링크가 계속 동작하게 한다.

### 4. HTTPS 적용

편집기로 프로젝트 루트 `.env`에 다음 값을 추가하거나 기존 값을 수정한다.
`조회한_그룹_번호`는 위 숫자로 바꾼다. 운영에서는 도메인과 URL을 `bokduckbang.com`으로
바꾼다. 같은 이름의 설정을 중복 추가하지 않는다.

```dotenv
TOADZIP_TLS_DOMAIN=dev.bokduckbang.com
TOADZIP_TLS_GID=조회한_그룹_번호
COMPOSE_FILE=compose.yaml:compose.https.yaml
SESSION_COOKIE_SECURE=true
ADMIN_CORS_ALLOWED_ORIGIN=https://dev.bokduckbang.com
```

관리자 화면을 다른 주소에서 서비스한다면 `ADMIN_CORS_ALLOWED_ORIGIN`에는 실제 관리자
HTTPS 출처를 지정한다. `.env`는 Compose가 직접 읽는다. **`source .env`로 실행하지
않으며**, 전체 내용을 채팅이나 로그에 출력하지 않는다.

`USER_OAUTH_ENABLED=true`인 서버는 아래 설정도 HTTPS로 바꾼다.
OAuth 활성화는 사용할 공급자의 설정을 마친 뒤 진행한다.

```dotenv
USER_OAUTH_REDIRECT_BASE_URL=https://dev.bokduckbang.com
USER_OAUTH_SUCCESS_URL=https://dev.bokduckbang.com/
USER_OAUTH_FAILURE_URL=https://dev.bokduckbang.com/?login=failed
```

사용 중인 Google·Kakao 콘솔에도 각각 `https://도메인/api/auth/oauth2/callback/google`,
`https://도메인/api/auth/oauth2/callback/kakao` 콜백 URL을 등록한다. NAVER Maps
애플리케이션에도 해당 HTTPS Web 서비스 URL을 등록한다. Client ID가 바뀌면
프론트엔드 이미지를 다시 빌드해야 한다.

임시 컨테이너에서 설정과 인증서 읽기 권한을 검사한다. 이 명령은 서비스 포트를
열지 않는다. 기존 `backend`가 실행 중이어야 백엔드 이름 확인도 성공한다.

```shell
chmod 600 .env
sudo docker compose config --quiet
sudo docker compose run --rm --no-deps frontend nginx -t -c /tmp/https.conf
```

성공한 뒤 프론트엔드와 환경변수가 바뀐 백엔드를 재생성한다. 기존 서비스 이미지가
준비된 상태에서 실행하며, 이때 잠시 서비스가 끊길 수 있다. Nginx가 새 백엔드
주소를 읽도록 백엔드를 먼저 적용한 뒤 프론트엔드를 적용한다.

```shell
sudo docker compose up -d --no-deps backend
sudo docker compose up -d --no-deps frontend
sudo docker compose ps
sudo docker compose exec -T frontend nginx -t -c /tmp/https.conf
curl --fail --connect-timeout 5 --max-time 10 "https://$toadzip_tls_domain/healthz"
curl --fail --connect-timeout 5 --max-time 10 "https://$toadzip_tls_domain/api/health"
curl --head --connect-timeout 5 --max-time 10 "http://$toadzip_tls_domain/"
```

HTTPS `/healthz`는 `ok`, HTTPS `/api/health`는 `{"status":"UP"}`가 정상 응답이다.
HTTP `/`는 해당 도메인의 HTTPS로 `308` 이동해야 한다.
HTTP `/healthz`와 `/.well-known/acme-challenge/`는 상태 확인과 갱신을 위해 유지한다.
`curl -k`로 인증서 오류를 숨기지 않는다.

외부 브라우저에서 홈·단지 조회·공고·PDF 미리보기·지도와 직접 URL 새로고침을 확인한다.
관리자 로그인·세션 유지·사용 중인 소셜 로그인도 확인한다. 기존 HTTP 세션 사용자는
다시 로그인해야 할 수 있다. 개발·운영 결과를 각각 기록한다.

### 5. 자동 갱신 확인

Certbot이 새 인증서를 저장하면 실행 중인 Nginx도 다시 읽어야 한다. 갱신 성공 후
실행되는 `deploy hook`으로 저장소의 스크립트를 설치한다. 같은 이름의 기존 파일이
있다면 먼저 확인·백업하고 교체한다.

```shell
sudo install -d -o root -g root -m 0755 /etc/letsencrypt/renewal-hooks/deploy
sudo install -o root -g root -m 0755 infra/certbot/reload-nginx.sh \
  /etc/letsencrypt/renewal-hooks/deploy/toadzip-nginx.sh
sudo env RENEWED_LINEAGE="/etc/letsencrypt/live/$toadzip_tls_domain" \
  /etc/letsencrypt/renewal-hooks/deploy/toadzip-nginx.sh
sudo certbot renew --cert-name "$toadzip_tls_domain" --dry-run --run-deploy-hooks
```

`RENEWED_LINEAGE`는 갱신된 인증서 폴더를 알려주는 Certbot의 값이다. `.env`에 넣는
설정이 아니며 위 수동 실행에서만 직접 전달한다. 스크립트는 그 폴더를 연결한
프론트엔드 하나를 찾아 설정 검사 후 재적용한다. 실행 중인 대상이 여러 개이거나
연결된 대상이 모두 정지되어 있으면 오류로 알리고, 관련 없는 인증서는 건너뛴다.
스크립트 원본을 바꾼 배포에서는 위 `install` 명령으로 다시 설치한다.

`--dry-run`은 시험 인증 서버를 사용한다. `--run-deploy-hooks`는 실제 사용 중인
인증서로 재적용 스크립트도 시험한다. 명령 종료 코드뿐 아니라 Certbot 출력과 hook
오류도 확인한다.

자동 갱신은 Certbot 설치 방식에 따라 이미 등록되어 있을 수 있다. 아래 조회와 함께
기존 cron 등록도 확인하고 `certbot renew`가 실제로 주기 실행되는지 확인한다.

```shell
sudo systemctl list-timers --all '*certbot*' '*letsencrypt*'
```

정상 일정이 있으면 그대로 사용한다. 없다면 해당 EC2의 설치 방식에 맞춰 일정을
마련하고 시험한다. 이 저장소는 별도 timer를 미리 설치하지 않는다. **갱신 시험이
성공하고 자동 실행 일정까지 확인되어야 자동 갱신 구성이 완료된 것이다.** 80번 포트
유지와 갱신 작업의 실패 로그 확인도 운영 점검에 포함한다.

## 운영

### 재배포

HTTPS 전환을 마친 서버에서는 루트 `.env`의 `COMPOSE_FILE` 덕분에 기존 명령을 유지한다.
명령에 `-f compose.yaml`만 붙이면 HTTPS 설정이 빠지므로 주의한다.

```shell
sudo docker compose config --quiet
sudo docker compose up -d --build
sudo docker compose exec -T frontend nginx -t -c /tmp/https.conf
sudo docker compose exec -T frontend nginx -s reload -c /tmp/https.conf
curl --fail --connect-timeout 5 --max-time 10 "https://$toadzip_tls_domain/api/health"
```

새 터미널에서는 앞서 설명한 `toadzip_tls_domain` 값을 먼저 지정한다. 백엔드만
재생성된 경우에도 Nginx가 새 백엔드 주소를 읽도록 검사 성공 후 reload한다.
백엔드 시작이 끝난 뒤 API 상태 확인이 성공하는지 확인한다.

Nginx 설정은 이미지에 포함되며 HTTPS 설정은 컨테이너 시작 때 `/tmp/https.conf`로
만들어진다. 컨테이너 내부 파일을 직접 고치지 않는다. 인증서 갱신은 이미지 재빌드
없이 hook이 반영한다. 도메인·그룹 값을 바꿀 때는 컨테이너를 재생성한다.

### 장애와 복구

전환 전 검사에서 실패하면 적용을 멈추고 기존 HTTP 서비스를 유지한다. 적용 후
장애가 생기면 Nginx 로그에서 인증서 경로·권한·백엔드 연결 문제를 확인한다.
급히 HTTP로 복귀해야 한다면 `.env` 백업을 복구하고 HTTP 구성을 명시한다.
백업 경로 변수는 같은 터미널에서만 유지된다.

```shell
cp -p "$toadzip_https_backup/.env" .env
chmod 600 .env
sudo docker compose -f compose.yaml -f compose.certbot.yaml up -d --no-deps backend
sudo docker compose -f compose.yaml -f compose.certbot.yaml up -d --no-deps frontend
```

이는 새 이미지의 HTTP 구성으로 돌아가는 절차다. 이미지 자체 문제라면 보관한
`toadzip-frontend:before-https` 이미지와 이전 Git 버전의 구성을 함께 복원한다.
HTTP는 인증정보를 안전하게 전송하지 못하며 HTTPS 주소·Secure 쿠키·OAuth와도 맞지
않는다. HTTP 복귀를 로그인 기능의 정상 복구로 간주하지 말고 HTTPS 복구를 우선한다.

## 로컬 검증

실제 인증서나 EC2 접속 없이 임시 인증서로 구성을 검사한다. Python 3.9 이상,
Docker Engine·CLI 26 이상([volume subpath 지원](https://docs.docker.com/engine/release-notes/26.0/#new)),
Compose v2 이상, OpenSSL과 curl이 필요하다.
프로젝트 루트에서 실행한다.

```shell
docker build -t toadzip-frontend:https-test frontend
python3 scripts/test-https.py --image toadzip-frontend:https-test
```

관련 검사와 프론트엔드 필수 검사는 저장소 지침을 따른다. 로컬 성공은 실제 인증서
발급, 외부 443 접속, EC2 자동 갱신이나 로그인 성공을 증명하지 않는다.

## 참고

- [Certbot webroot 방식](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot)
- [Certbot 인증서 권한](https://eff-certbot.readthedocs.io/en/stable/using.html#where-are-my-certificates)
- [Certbot 갱신과 hook](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)
- [Docker Compose의 COMPOSE_FILE](https://docs.docker.com/compose/how-tos/environment-variables/envvars/#compose_file)
