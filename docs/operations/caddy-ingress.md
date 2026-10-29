# Caddy 서버 진입점

개발·운영 EC2에서 `Client -> Caddy(:443) -> edge-nginx:81 -> frontend/backend:8080` 경로를 사용한다.
[서버 overlay](../../compose.caddy-server.yaml)는 공개 80·443을 Caddy에 배정하고 프론트의 호스트 포트를 제거한다.
[로컬 overlay](../../compose.caddy.yaml)의 127.0.0.1:18443 설정은 그대로 유지한다.

## 설정

서버 `.env`에 아래 값을 설정한다. 경로는 해당 EC2의 실제 저장소 경로로 바꾸고 전체 내용을 출력하거나 `source .env`로 실행하지 않는다.
운영은 도메인 `bokduckbang.com`, 프로필 `prod`를 사용한다.

```dotenv
SPRING_PROFILES_ACTIVE=dev
TOADZIP_CADDY_DOMAIN=dev.bokduckbang.com
EDGE_NGINX_CONF_DIR=/absolute/checkout/infra/runtime/bluegreen/edge
EDGE_NGINX_ASSET_DIR=/absolute/checkout/infra/runtime/bluegreen/assets
COMPOSE_FILE=compose.yaml:compose.bluegreen.yaml:compose.caddy-server.yaml
SESSION_COOKIE_SECURE=true
ADMIN_CORS_ALLOWED_ORIGIN=https://dev.bokduckbang.com
```

기존 `COMPOSE_FILE=compose.yaml:compose.https.yaml`에 덧붙이지 말고 파일 목록을 교체한다.
DB·OAuth·알림 등 기존 서버 값은 보존한다. 별도 인증정보 overlay가 필요하면 해당 파일도 명시한다.
기본 경로를 사용하면 위 `EDGE_NGINX_*` 두 항목은 생략할 수 있다.
`python3 -B scripts/prepare-bluegreen.py`가 `infra/runtime/bluegreen/edge/default.conf`와 `assets/`를 준비한다.
서버 overlay는 준비된 경로가 없으면 적용에 실패한다. 자세한 준비는 [배포 도구](../../scripts/README.md#linux-프론트백엔드-bluegreen-도구)를 따른다.
동작 중인 런타임의 활성 색상을 Git 템플릿으로 덮어쓰지 않는다.

프론트는 HTTP `/etc/nginx/nginx.conf`를 사용하고 기존 HTTPS command·환경·인증서 마운트·그룹은 제거한다.
Caddy 뒤에 프론트의 HTTPS 리다이렉트를 남겨 생기는 반복 이동을 방지한다.
Caddy의 `/data`·`/config`는 프로젝트별 named volume에 보존하며 admin API 2019는 공개하지 않는다.
모니터링 서버의 `compose.monitoring.yaml`은 별도 진입점이므로 이 애플리케이션 overlay와 함께 사용하지 않는다.

Caddy는 공개 도메인의 인증서 발급·갱신과 HTTP→HTTPS 이동을 담당한다.
[공식 자동 HTTPS 조건](https://caddyserver.com/docs/automatic-https)에 맞게 DNS, 외부 80·443 접근과 영구 저장소를 준비한다.

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
그 뒤 [자동 배포 설정](deployment.md#bluegreen-서버-준비)의 `DEV_BLUEGREEN_URL`을 설정하고,
`DEV_BLUEGREEN_COMPOSE_OVERRIDES`에 `compose.caddy-server.yaml`을 지정한다. 운영은 `PROD_*`를 사용한다.

전환 실패 시 새 구성으로 Caddy만 정지하고 백업한 `.env`·Compose·이미지로 프론트 공개 포트를 복원한다.
성공 후 기존 Certbot timer·hook의 이 도메인 담당 여부를 확인해 중복 관리를 정리한다.
다른 도메인의 갱신을 중지하거나 인증서를 삭제하지 않는다.

## 로컬 검증

```shell
python3 -B tests/deployment/test_caddy_ingress.py
python3 -B tests/deployment/test_caddy_ingress_linux.py
```

첫 검사는 개발·운영 포트·필수 경로·영구 저장소·기존 TLS 제거를 확인한다.
두 번째 검사는 localhost의 임시 로컬 CA와 무작위 loopback 포트로 TLS·리다이렉트·프론트/API/정적 파일·헤더·Edge 전환·Caddy 재생성 후 저장소를 확인한다.
실제 EC2·공개 포트·ACME·외부 DNS 검증을 대신하지 않는다.
