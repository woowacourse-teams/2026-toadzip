# Caddy 서버 진입점

개발·운영 EC2에서 `Client -> Caddy(:443) -> edge-nginx:81 -> frontend/backend:8080` 경로를 사용한다.
[서버 overlay](../../compose.caddy-server.yaml)는 공개 80·443을 Caddy에 배정하고 프론트·legacy 백엔드의 호스트 포트를 제거한다.
메트릭 수집 포트는 Edge에 연결한다. 수집 경로와 접근 제한은 [메트릭 수집 운영](monitoring.md#bluegreen-메트릭-수집)을 따른다.
[로컬 overlay](../../compose.caddy.yaml)의 127.0.0.1:18443 설정은 그대로 유지한다.

## 설정

서버 `.env`에 아래 값을 설정한다. 경로는 해당 EC2의 실제 저장소 경로로 바꾸고 전체 내용을 출력하거나 `source .env`로 실행하지 않는다.
운영은 도메인 `bokduckbang.com`, 프로필 `prod`를 사용한다.

```dotenv
SPRING_PROFILES_ACTIVE=dev
TOADZIP_CADDY_DOMAIN=dev.bokduckbang.com
BLUEGREEN_METRICS_BIND_ADDRESS=<application_private_ip>
EDGE_NGINX_CONF_DIR=/absolute/checkout/infra/runtime/bluegreen/edge
EDGE_NGINX_ASSET_DIR=/absolute/checkout/infra/runtime/bluegreen/assets
COMPOSE_FILE=compose.yaml:compose.bluegreen.yaml:compose.caddy-server.yaml:compose.notification-credentials.yaml
NOTIFICATION_FIREBASE_CREDENTIALS_FILE=/absolute/managed-secrets/firebase-dev.json
SESSION_COOKIE_SECURE=true
ADMIN_CORS_ALLOWED_ORIGIN=https://dev.bokduckbang.com
```

기존 `COMPOSE_FILE=compose.yaml:compose.https.yaml`에 덧붙이지 말고 파일 목록을 교체한다.
DB·OAuth·알림 등 기존 서버 값은 보존한다. Firebase 인증파일은 해당 환경의 기존 관리 파일 경로를 지정하고 backend UID 10001의 읽기 권한을 확인한다.
다른 인증정보 overlay가 필요하면 해당 파일도 명시한다.
기본 경로를 사용하면 위 `EDGE_NGINX_*` 두 항목은 생략할 수 있다.
`BLUEGREEN_METRICS_BIND_ADDRESS`는 이 EC2의 사설 IP이며 서버 overlay에서 필수다.
`python3 -B scripts/prepare-bluegreen.py`가 `infra/runtime/bluegreen/edge/default.conf`와 `assets/`를 준비한다.
서버 overlay는 준비된 경로가 없으면 적용에 실패한다. 자세한 준비는 [배포 도구](../../scripts/README.md#linux-프론트백엔드-bluegreen-도구)를 따른다.
동작 중인 런타임의 활성 색상을 Git 템플릿으로 덮어쓰지 않는다.

프론트는 HTTP `/etc/nginx/nginx.conf`를 사용하고 기존 HTTPS command·환경·인증서 마운트·그룹은 제거한다.
Caddy 뒤에 프론트의 HTTPS 리다이렉트를 남겨 생기는 반복 이동을 방지한다.
Caddy의 `/data`·`/config`는 프로젝트별 named volume에 보존하며 admin API 2019는 공개하지 않는다.
모니터링 서버의 `compose.monitoring.yaml`은 별도 진입점이므로 이 애플리케이션 overlay와 함께 사용하지 않는다.

Caddy는 공개 도메인의 인증서 발급·갱신과 HTTP→HTTPS 이동을 담당한다.
[공식 자동 HTTPS 조건](https://caddyserver.com/docs/automatic-https)에 맞게 DNS, 외부 80·443 접근과 영구 저장소를 준비한다.

## 도메인 이동과 검색 색인

- 운영은 `TOADZIP_CADDY_REDIRECT_DOMAIN=www.bokduckbang.com`을 설정해 www 요청을 기본 도메인으로 308 이동한다. 개발은 비워 둔다. 보조 도메인도 DNS가 이 서버를 가리켜야 인증서를 받는다.
- 운영 호스트 `bokduckbang.com` 외의 모든 응답(페이지·API·첨부)에는 `X-Robots-Tag: noindex`가 붙는다. 개발 서버가 검색 결과에 남지 않게 하는 규칙이다.
- 개발 서버의 robots.txt로 크롤링을 막지 않는다. 막으면 검색엔진이 noindex 헤더를 읽지 못해 이미 색인된 주소가 빠지지 않는다.

Caddyfile은 단일 파일로 마운트되고 Blue/Green 배포는 Caddy를 재시작하지 않는다.
Caddyfile이나 위 환경변수를 바꾼 커밋이 서버 checkout에 반영된 뒤 아래 명령으로 Caddy를 재생성한다. 재생성 중 짧은 연결 끊김이 생길 수 있다.

```shell
docker compose up -d --no-deps --force-recreate caddy
curl --head --max-time 10 https://dev.bokduckbang.com/
```

개발 서버 응답에 `x-robots-tag: noindex`가 있고 운영 응답에는 없는지 확인한다.

## 최초 전환

**최초 80·443 이동에는 짧은 중단이 발생할 수 있다. 이후 앱 배포는 Blue/Green 도구를 사용한다.**
개발 서버에서 먼저 검증하고 자동·수동 배포가 겹치지 않는 상태에서 수행한다.
기존 Git 커밋·이미지·Compose 구성·서버 `.env`를 로컬에 백업하고 인증서·볼륨은 보존한다.

```shell
docker ps --format 'table {{.Names}}\t{{.Ports}}'
sudo ss -lntp '( sport = :80 or sport = :443 )'
docker compose config --quiet
docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

검증 컨테이너는 공개 포트를 열지 않는다. 실제 도메인 발급 성공을 뜻하지도 않는다.
기존 프론트 외 다른 프로세스가 80·443을 소유하면 그 대상에 맞는 전환 계획이 필요하다. 추측해서 종료하지 않는다.
현재 이미지와 실행 중인 백엔드를 유지한 채 Edge 설정·마운트를 준비하고, 프론트를 내부 HTTP로 재생성한 뒤 Caddy를 시작한다.
프론트 재생성 후 Edge가 새 주소를 읽도록 설정 검사와 reload가 필요하다. 이 작업에서 전체 `down`이나 DB 재생성을 실행하지 않는다.

```shell
docker compose up -d --no-deps edge-nginx
docker compose up -d --no-deps --wait frontend
docker compose exec -T edge-nginx nginx -t
docker compose exec -T edge-nginx nginx -s reload
docker compose up -d --no-deps caddy
curl --fail --connect-timeout 5 --max-time 10 https://dev.bokduckbang.com/healthz
curl --fail --connect-timeout 5 --max-time 10 https://dev.bokduckbang.com/api/health
curl --head --connect-timeout 5 --max-time 10 http://dev.bokduckbang.com/
```

도메인 인증서·외부 HTTPS·HTTP 리다이렉트와 브라우저의 지도·공고·PDF·새로고침·사용 중인 로그인을 확인한다.
그 뒤 [자동 배포 설정](deployment.md#bluegreen-서버-준비)의 `DEV_BLUEGREEN_URL`을 설정한다.
Actions는 Caddy·Firebase 인증파일 overlay를 기본으로 전달하므로 서버 인증파일 경로도 준비한다.
`DEV_BLUEGREEN_COMPOSE_OVERRIDES`는 그 외 추가 설정이 있을 때만 지정한다. 운영은 `PROD_*`를 사용한다.

전환 실패 시 새 구성으로 Caddy만 정지하고 백업한 `.env`·Compose·이미지로 프론트 공개 포트를 복원한다.
성공 후 기존 Certbot timer·hook의 이 도메인 담당 여부를 확인해 중복 관리를 정리한다.
다른 도메인의 갱신을 중지하거나 인증서를 삭제하지 않는다.

## 로컬 검증

```shell
python3 -B tests/deployment/test_caddy_ingress.py
python3 -B tests/deployment/test_caddy_ingress_linux.py
python3 -B tests/deployment/test_caddy_robots.py
```

첫 검사는 개발·운영 포트·필수 경로·영구 저장소·기존 TLS 제거를 확인한다.
두 번째 검사는 localhost의 임시 로컬 CA와 무작위 loopback 포트로 TLS·리다이렉트·프론트/API/정적 파일·헤더·Edge 전환·Caddy 재생성 후 저장소를 확인한다.
세 번째 검사는 실제 Caddyfile을 Caddy 내부 CA로 실행해 개발 호스트의 noindex, 운영 호스트의 헤더 없음과 www 이동을 확인한다.
실제 EC2·공개 포트·ACME·외부 DNS 검증을 대신하지 않는다.
