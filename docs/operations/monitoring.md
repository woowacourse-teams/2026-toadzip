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

## Blue/Green 메트릭 수집

Prometheus는 기존 애플리케이션 서버의 `8081/actuator/prometheus`를 계속 수집한다.
Edge Nginx가 이 요청을 현재 API 요청을 받는 백엔드의 관리 포트 `8081`로 전달한다.
메트릭은 Caddy를 거치지 않으며, 두 색상이 모두 실행 중이어도 활성 색상 한쪽만 수집한다.
기존 대상 주소와 `job`·`environment`·`application`·`instance` 라벨을 유지하므로 Grafana 조회식은 변경하지 않는다.

### 설정의 역할

| 설정 | 동작과 이유 |
|---|---|
| 애플리케이션 서버 `.env`의 `BLUEGREEN_METRICS_BIND_ADDRESS` | 모니터링 서버가 접근하는 **애플리케이션 서버 자신의 사설 IP**다. 모니터링 서버 IP나 공개 IP, `0.0.0.0`을 넣지 않는다. 서버 overlay에서 필수이며 미설정 시 Compose 구성을 중단한다. 보안 그룹의 포트 소스도 모니터링 서버로 제한해야 한다. |
| `MANAGEMENT_PORT` | 호스트 수집 포트다. 기본값 `8081`을 유지한다. 기존 Prometheus 대상과 다른 값으로 바꾸지 않는다. |
| `compose.caddy-server.yaml` | Edge에 위 주소·포트를 연결한다. legacy `backend`의 호스트 `8080`·`8081` 공개는 제거하여 포트 충돌을 방지한다. 컨테이너 내부 포트와 일반 로컬 Compose 구성은 유지한다. |
| `backend_metrics_active` | API용 `backend_active`와 같은 컨테이너를 가리키고 관리 포트만 `8081`을 사용한다. 배포·색상 전환·수동 롤백에서 두 경로를 하나의 설정 파일로 함께 바꾼다. |
| 메트릭 전용 listener | `8081`에서 정확히 `/actuator/prometheus`만 전달하고 다른 경로는 404로 거부한다. 공개 HTTP·HTTPS 경로에 관리 API를 추가하지 않는다. |
| `keepalive_timeout 0` | 메트릭 클라이언트 연결을 계속 유지하지 않아 전환 후 이전 worker에 수집 요청이 머무르는 것을 방지한다. |
| 연결·응답 제한과 우회 금지 | 연결 3초·응답 대기 5초를 적용하고 proxy cache와 다음 upstream 재시도를 끈다. 활성 앱 장애를 이전 색상이나 캐시 응답으로 숨기지 않는다. |
| `X-Toadzip-Metrics-Upstream` | 관리 응답에 실제 upstream 주소를 기록한다. 배포 도구가 선택한 컨테이너의 IP·포트와 비교해 잘못된 수집 대상을 거부한다. |

배포 전에는 서버 Compose가 요구하는 Edge 관리 포트가 실제로 같은 주소·포트에 연결됐는지 확인한다.
전환 후에는 실제 API 도착과 함께 메트릭의 upstream·Content-Type·Prometheus 형식을 확인한다.
후보 메트릭 검증에 실패하면 이전 API·메트릭 설정으로 함께 복구하고 기존 컨테이너를 정지하지 않는다.

### 기존 서버 최초 적용

다음은 기존 Blue/Green 서버에 필요한 한 번의 준비 작업이다. 새 배포 도구는 메트릭 경로가 없는 구형
런타임에서 사전 검사를 중단한다. 수정본 병합에 따른 자동 배포와 준비 작업이 겹치지 않도록 적용 시간을 조율한다.

1. 현재 런타임과 Edge의 이미지·마운트·포트 구성, 이전 Compose 버전을 복구할 수 있도록 보관한다.
   실제 프로젝트명·추가 Compose 파일·런타임 경로를 그대로 사용하며 비밀값을 로그에 출력하지 않는다.
2. 서버 `.env`에 위 사설 IP를 설정하고 모니터링 서버만 접근하도록 기존 보안 그룹 제한을 유지한다.
   실행 중인 legacy `backend`가 아직 호스트 `8081`을 사용하면 별도의 포트 소유권 전환이 필요하다.
   활성 백엔드를 단순히 정지해 포트만 확보하지 않는다.
3. 저장소 루트에서 `python3 -B scripts/prepare-bluegreen.py --upgrade-metrics`를 실행한다.
   기본 런타임 경로가 아니면 기존 `--runtime`도 지정한다. 공통 잠금 안에서 기존 활성 색상·추가 설정을
   보존하고 메트릭 블록만 추가한다. 이전 설정은 런타임의 `before-metrics-*.conf.bak`에 남긴다.
   재실행은 같은 블록을 중복 추가하지 않는다. 이미 다른 `8081` listener가 있거나 API·메트릭 색상이
   다르면 자동 수정하지 않고 중단하므로 기존 설정을 검토한다.
4. 준비 명령은 Docker를 실행하거나 Nginx를 reload하지 않는다. 호스트 포트 추가는 **Edge 컨테이너 재생성**이
   필요하며 reload만으로는 반영되지 않는다. 이 최초 작업에는 짧은 진입점 중단이 생길 수 있다.
   배포가 실행되지 않는 작업창에서 공통 `deploy.lock`을 잡고, 기존 프로젝트와 실제 Compose 파일 조합으로
   `edge-nginx`만 `up -d --no-deps --no-build --pull never --force-recreate`한다.
   전체 스택을 내리거나 DB·볼륨·Caddy·애플리케이션을 일괄 재생성하지 않는다.
5. 실제 모니터링 서버에서 기존 대상의 `/actuator/prometheus`와 여러 수집 주기의 `up=1`, 기존 Grafana 지표를
   확인한다. 외부 API 응답도 확인하고, 이후 배포와 호환성이 확인된 버전의 롤백에서 같은 검증을 수행한다.
6. 최초 적용 검증에 실패하면 새 앱 배포를 진행하지 않는다. 런타임 백업과 이전 Edge Compose 구성을 복원해
   Edge만 다시 기동하고 API를 확인한다. 자동 배포는 정상 경로를 확인한 후 재개한다.

활성 백엔드만 수집하므로 후보의 전환 전 지표와 이전 백엔드가 마무리하는 요청의 최종 지표는 모두 보존되지
않을 수 있다. 전환 시 JVM 값과 누적 카운터의 출처도 바뀌므로 전환 구간의 요청률·지연은 배포 기록과 함께
해석한다. `up=1`은 메트릭 수집 성공이며 사용자 요청의 정상 처리 여부는 API 확인과 함께 판단한다.

로컬 회귀 검사는 `python3 -B tests/deployment/test_bluegreen.py`와 `test_rollback_bluegreen.py`로 실행한다.
실제 Nginx·Prometheus 검사는 `python3 -B tests/deployment/test_bluegreen_drain.py`에 포함되어 있다.
실행 조건과 격리·정리 범위는 [검증 안내](../../tests/README.md#긴-요청정적-파일-버전원복-통합-검증)를 따른다.
로컬 검증은 실제 DEV·운영의 수집, 접근 제한과 DB 호환성 검증을 대신하지 않는다.

## API·부하테스트 대시보드

`Toadzip API · 부하테스트`는 기존 Backend·Logs 대시보드와 별개다.
정의는 [`api-load-test.json`](../../infra/grafana/dashboards/api-load-test.json)에 있고,
모니터링 Compose를 적용하면 provisioning으로 등록된다. UID는 `toadzip-api-load-test`다.
현재 서버에 먼저 등록하려면 Grafana의 Dashboards → New → Import dashboard에서 같은 JSON을 가져온다.
파일 업로드를 사용할 수 없다면 JSON 내용을 `Import via dashboard JSON model`에 입력한다.

- 환경은 dev·prod 중 하나를 선택한다. 테스트 대상 API 지표와 로그가 같은 환경을 사용한다.
- 맨 위 `운영 보호 · prod 상시 관측`은 환경·API·메서드·DB 풀 선택과 무관하다.
  prod 전체 API(`/api/health` 제외)의 RPS·p95·5xx, DB 풀 대기·timeout과 API별 p95를 본다.
  prod 수집 실패 시 지표를 숨기며, 무트래픽의 p95·오류율은 `—`다. `—`를 정상으로 판정하지 않는다.
- 기본 범위는 사용자 API다. 관리자 API는 별도로 선택하며 `/api/health`는 집계에서 제외한다.
- 상단 RPS·지연·오류율은 최근 집계 구간이다. API 비교 표는 선택한 전체 기간의 평균 RPS·지연·오류를 보여준다.
- 테스트 종료 후에는 시작·종료 시간을 선택해 API 비교 표를 확인한다.
- 요청 없는 구간의 p95·p99·오류율은 `—`로 표시한다. 수집 정상은 서비스 정상과 다르다.
- API 비교 표의 경로를 누르면 메서드·API를 좁힌다. 요청 표의 traceId는 같은 환경·기간의 Explore로 연결한다.
- 느린 요청 기준은 검색 조건이다. 성능 목표나 알림 기준을 뜻하지 않는다.
- DB 연결 획득시간은 SQL 실행시간이 아니다. 외부 호출 시간도 전체 API 시간과 별도로 해석한다.

`로그 데이터 소스`는 현재 연결된 Loki를 선택한다. provisioning은 안정된 UID의
`Toadzip Loki`를 추가하며 기존에 수동 등록한 Loki는 삭제하지 않는다.
기존 데이터 소스와 새 데이터 소스는 같은 Loki 서버를 조회한다.
provisioning으로 관리되는 대시보드 수정은 JSON 원본에 반영한다.

라우트 템플릿 기록 전 로그는 실제 URI로 필터링된다. `API=All`이면 과거 로그도 조회할 수 있지만,
`/complexes/{complexId}`로 좁히면 route 필드가 없는 과거 상세 요청은 포함되지 않는다.
Tomcat·외부 HTTP·Nginx 패널은 해당 계측과 수집 설정을 애플리케이션 서버에 배포한 뒤 표시된다.

`공유 DB 호스트`와 DB 서버 CPU·메모리 패널은 API 환경 선택과 별개다.
세 PostgreSQL이 같은 서버를 사용하므로 호스트 전체를 한 번만 수집한다.
수집되지 않거나 계산에 필요한 데이터가 부족하면 자원 값은 `—`다. 사용률 0%와 구분한다.
Node Exporter 배포와 실제 수집 대상 등록은 [DB 서버 설정](database/server.md)을 따른다.
디스크 패널과 PostgreSQL 락·트랜잭션 패널도 API 선택과 무관하다. PostgreSQL은 `db_role`로
dev·prod·shared를 구분한다. DB 연결 성공과 collector 성공까지 확인한 데이터만 표시한다.

## 대시보드 조회식 검증

실제 JSON의 PromQL을 사용해 개발·운영 분리, API별 p95, 오류율과 무트래픽 처리를 검사한다.
실제 서비스·DB에 요청을 보내지 않는 합성 데이터 검증이다.

```shell
mkdir -p .local/verification/monitoring
python3 tests/monitoring/promql_cases.py > .local/verification/monitoring/promql-tests.yml
node tests/monitoring/request_fields.mjs
docker run --rm --entrypoint promtool \
  --mount type=bind,src="$PWD/.local/verification/monitoring",dst=/tests,readonly \
  prom/prometheus:v3.14.0 test rules /tests/promql-tests.yml
```

DB 호스트 조회식은 코어 평균·호스트 분리·캐시를 고려한 메모리·미등록·수집 실패를 검사한다.

```shell
python3 tests/monitoring/db_host_cases.py > .local/verification/monitoring/db-host-tests.yml
docker run --rm --entrypoint promtool \
  --mount type=bind,src="$PWD/.local/verification/monitoring",dst=/tests,readonly \
  prom/prometheus:v3.14.0 test rules /tests/db-host-tests.yml
```

prod 고정 집계, 디스크 계산, DB 락과 수집 실패를 검증한다.

```shell
python3 tests/monitoring/guardrail_cases.py > .local/verification/monitoring/guardrails.yml
docker run --rm --network none --entrypoint promtool \
  --mount type=bind,src="$PWD/.local/verification/monitoring",dst=/tests,readonly \
  prom/prometheus:v3.14.0 test rules /tests/guardrails.yml
python3 tests/monitoring/postgres_collection.py
```

## 부하테스트 시작 전 확인

1. [DB 서버 적용 절차](database/server.md)에 따라 수집기와 모니터링 설정을 반영한다.
2. dev와 특정 API를 선택해도 prod 행의 범위가 바뀌지 않는지 확인한다.
   DB 호스트 디스크, PostgreSQL 3개 수집, DB 연결·collector 성공을 확인한다.
3. 부하 전 동일 시간 범위로 prod의 요청량·지연·5xx·DB 풀 대기 기준선을 확보한다.
   실제 prod 요청이 없으면 지연을 검증할 수 없으므로 저빈도 읽기 점검 여부를 별도로 정한다.
4. dev의 낮은 부하부터 시작한다. prod에 새로운 5xx·DB timeout·지속적인 대기가 발생하거나
   기준선 대비 지연이 악화되면 증가를 멈추고 부하를 중단한다. 관측 수집이 끊겨도 중단한다.
   수치화한 지연 중단선과 정상·급증 목표 RPS는 실행 전에 별도로 확정한다.

Tomcat 스레드 지표는 부하테스트의 필수 시작 조건이 아니다. 처리 스레드 포화를 구분하는 보조 지표다.
현재 소스의 `server.tomcat.mbeanregistry.enabled=true`는 빌드된 JAR에 포함된다.
이 변경 이전 이미지라면 **다음 백엔드 배포 때 새로 빌드**해야 하며 기존 이미지 재시작으로 반영되지 않는다.
서버에서 배포 후 아래 읽기 전용 명령으로 확인한다(관리 포트가 기본값일 때).

```shell
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8081/actuator/prometheus \
  | rg '^tomcat_(threads|sessions)_'
```

직접 응답에는 스레드 지표가 있는데 Prometheus에 없으면 수집 대상·라벨·relabel 설정을 확인한다.
직접 응답에도 없으면 배포 이미지의 설정과 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` 재정의,
실제 서버 구현을 확인한다. 세션 지표는 MBean 없이도 등록되므로 세션 지표만 있는 것으로
MBean 활성화를 확인할 수 없다. 전체 환경변수·비밀번호를 로그나 채팅에 출력하지 않는다.

## 애플리케이션 로그 수집 적용

백엔드 이미지를 배포하면 요청 완료 로그에 `route` 템플릿이 기록되고 Tomcat·네이버 HTTP 메트릭이 추가된다.
프론트엔드 이미지를 다시 빌드·배포하면 Nginx access 로그가 JSON으로 기록된다.
`infra/alloy/config.alloy`를 각 애플리케이션 서버에 반영한 뒤 Alloy 컨테이너를 재생성한다.
`DEPLOY_ENVIRONMENT`는 서버별 dev·prod 값을 유지한다. 실제 서비스 배포와 Alloy 재시작은 함께 확인한다.

- 백엔드 예외와 스택은 최대 256줄·3초 범위에서 한 이벤트로 묶는다. 과거 로그는 다시 묶이지 않는다.
- Nginx는 method·URI·status·전체 시간·upstream 시간을 전송한다. URI의 쿼리 값은 제외한다.
- 쿼리 값이 포함될 수 있는 Nginx stderr는 Loki로 전송하지 않는다. 상세 프록시 오류는 서버의 Docker 로그에서 확인한다.

격리된 Docker → Alloy → Loki 검증은 아래 명령으로 실행한다. 작업 전용 프로젝트만 수집하며,
종료 시 테스트 컨테이너와 볼륨을 정리한다. 로컬 58082·58310 포트를 사용한다.

```shell
python3 tests/monitoring/log_collection.py
```

## API 알림

기존 `API p95 latency high`와 `Backend HTTP 5xx`는 environment·method·uri를 유지한다.
p95는 최근 5분 p95가 1초를 2분간 초과하면, 5xx는 최근 5분 발생 건수가 0을 초과하면 알린다.
이 기준은 기존 운영 설정이며 부하테스트의 합격 기준은 별도로 정한다.
알림의 Runbook URL은 같은 환경·메서드·API로 좁힌 새 대시보드로 연결한다.
링크는 최근 30분을 보여주므로 오래된 알림을 조사할 때에는 발생 시간으로 범위를 바꾼다.

[`api.json`](../../infra/grafana/alerting/api.json)은 현재 두 규칙의 재현 가능한 파일 형식이다.
기존 UID와 `team-discord` 수신 경로를 유지한다. webhook·인증정보는 포함하지 않는다.
신규 Grafana의 부팅이 기존 contact point 존재 여부에 의존하지 않도록 자동 provisioning 디렉터리 밖에 둔다.
현재 서버에서는 두 규칙의 쿼리·메시지·Runbook URL을 UI에 반영했다.
파일 provisioning을 사용할 때에는 같은 이름의 contact point가 먼저 존재하는지 확인하고,
이 파일을 `provisioning/alerting`에 배치해 적용한다. UI에서 수정한 뒤에는 이 원본도 갱신한다.

대시보드 조회식 검증 명령은 이 두 알림의 API 라벨, 환경 분리, 무트래픽의 0과 오류 조건도 검사한다.
[Grafana 파일 provisioning](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/)
형식을 사용한다.
