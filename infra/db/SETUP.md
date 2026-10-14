# DB 서버 설정

## 환경변수

`.env.example`을 참고해서 `.env`를 생성한다.

## 실행

```shell
docker compose up -d
```

## 종료

```shell
docker compose down
```

## 공유 DB 호스트 CPU·메모리·디스크 수집

개발·운영·공유 PostgreSQL이 같은 서버에서 실행되므로 Node Exporter 한 개로 서버 전체의
CPU·메모리·디스크를 수집한다. Grafana의 환경 선택과 관계없이 같은 서버 자원을 보여준다.

Linux DB 서버에서 CPU·meminfo·diskstats collector를 사용한다. 호스트의 `/proc`를 읽기 전용으로
연결하고 PostgreSQL 계정이나 DB 접속은 사용하지 않는다.

### 적용 순서

1. DB 서버의 `.env`에 `.env.example`의 필수 설정을 준비한다.
   TCP 9100은 모니터링 서버에서만 접근할 수 있도록 제한한다.
2. DB 서버의 저장소 루트에서 수집기를 실행한다. 별도 Compose 프로젝트이므로 기존 DB
   컨테이너를 재생성하지 않는다.

   ```shell
   docker compose --project-name toadzip-db-metrics --env-file infra/db/.env \
     --file infra/db/compose.metrics.yaml up -d
   ```

3. 모니터링 서버의 `infra/prometheus/prometheus.yml`에서 `db-host`의 수집 대상을 등록한다.
   원본의 `targets`는 빈 목록이다. `<수집 대상>`을 모니터링 서버에서 접근 가능한 호스트 이름으로 바꾼다.

   ```yaml
   - job_name: db-host
     static_configs:
       - targets: ["<수집 대상>:9100"]
         labels:
           role: database
   ```

   설정을 적용하려면 모니터링 서버의 저장소 루트에서 Prometheus 컨테이너만 재생성한다.

   ```shell
   docker compose --env-file infra/monitoring/.env --file compose.monitoring.yaml \
     up -d --no-deps --force-recreate prometheus
   ```

   수집 대상을 변경할 때도 위 명령으로 다시 적용한다. 같은 서버를 dev·prod 대상으로 중복 등록하지 않는다.
4. Prometheus에서 `up{job="db-host"}`가 1인지 확인한다. `node_cpu_seconds_total`,
   `node_memory_MemAvailable_bytes`, `node_memory_MemTotal_bytes`가 같은 instance에 있어야 한다.
   Grafana의 API·부하테스트 대시보드에서 `공유 DB 호스트`를 선택한다.

### 지표 읽기

- CPU 사용률은 코어별 비유휴 시간의 평균이며 IO 대기를 포함한다. CPU가 높을 때 IO 대기
  추이도 함께 확인한다.
- 메모리 사용률은 `1 - MemAvailable/MemTotal`로 계산한다. 재사용 가능한 캐시를 고려하므로
  단순히 남은 메모리만 보는 값과 다르다.
- 수집되지 않거나 계산에 필요한 데이터가 부족하면 자원 값은 `—`다. 사용률 0%로 해석하지 않는다.
  수집 상태가 정상인 경우에만 CPU·메모리 값을 표시한다.

디스크 패널은 장치별 읽기·쓰기 평균 지연, 처리량, I/O 수행 시간 비율과 평균 진행·대기 I/O 수다.
`lsblk`로 DB 볼륨이 사용하는 장치를 확인하고 파티션과 물리 장치를 합산하지 않는다.
I/O 없는 구간의 평균 지연은 `—`이며, NVMe의 I/O 수행 시간 100%만으로 포화를 판단하지 않는다.

이 지표는 세 DB를 포함한 서버 전체 자원이다. 개별 DB 사용량·SQL 실행시간·락은 측정하지 않는다.
수집 성공도 PostgreSQL의 정상 응답을 보장하지 않는다. API 지연·오류와 기존 DB 연결풀 지표를
함께 확인해 서버 자원 부족인지 연결 대기인지 구분한다.

수집기 옵션은 [Node Exporter 공식 문서](https://github.com/prometheus/node_exporter)를 참고한다.

## PostgreSQL별 락·트랜잭션 수집

`compose.postgres-metrics.yaml`은 고정 버전 `postgres-exporter:v0.20.1`을 DB별로 실행한다.
DB 컨테이너의 기존 호스트 포트(dev 5432, prod 5433, shared 5434)에 Docker host gateway로 접속한다.
기존 DB와 별도 Compose 프로젝트이며 DB 재시작·확장 설치·스키마 변경은 필요 없다.
`pg_stat_activity`와 `pg_stat_database`를 수집하고 SQL 원문·쿼리별 통계는 수집하지 않는다.

### DB 서버 적용

1. 각 DB에 관리자 계정으로 접속하여 `postgres` DB에서
   [`create-monitor-role.sql`](create-monitor-role.sql)을 **최초 한 번** 실행한다.
   예: 저장소 루트에서 `docker compose --env-file infra/db/.env -f infra/db/compose.yaml exec -T db-dev psql -U toadzip -d postgres -v ON_ERROR_STOP=1 < infra/db/create-monitor-role.sql`.
   `db-prod`, `db-shared`에도 실행한다. 기존 역할이 있으면 권한을 확인하며 무조건 덮어쓰지 않는다.
2. 각 DB의 대화형 psql에서 `\password toadzip_monitor`로 서로 다른 비밀번호를 설정한다.
   전용 계정은 통계 읽기(`pg_read_all_stats`)만 부여받으며 업무 테이블 조회 권한은 추가하지 않는다.
   서버의 비밀 파일 3개에 해당 값을 보관하고 소유자 UID 65534, 모드 0400으로 제한한다.
   저장소·명령행·문서에 비밀번호를 넣지 않는다. `.env.example`을 참고해 `infra/db/.env`의 세 `*_DB_METRICS_PASSWORD_FILE`에
   각 파일의 절대 경로를 설정한다. Compose secrets는 로컬 파일 권한을 그대로 사용한다.
3. TCP 9100·9187·9188·9189의 접근 대상을 모니터링 서버로 제한한다.
   `DB_METRICS_BIND_IP`는 DB 서버의 사설 IP를 사용한다. 수집기만 실행한다.

   ```shell
   docker compose --project-name toadzip-db-metrics --env-file infra/db/.env \
     --file infra/db/compose.metrics.yaml up -d node-exporter
   docker compose --project-name toadzip-postgres-metrics --env-file infra/db/.env \
     --file infra/db/compose.postgres-metrics.yaml up -d
   ```

4. 모니터링 서버에서 아래 명령으로 세 포트 모두 확인한다.
   `pg_up 1`, `pg_scrape_collector_success{collector="stat_activity"} 1`과
   `collector="stat_database"`의 성공값 1이 필요하다. 라벨 순서는 다를 수 있다.

   ```shell
   curl --fail --max-time 10 http://<DB서버사설IP>:9187/metrics
   ```

### 모니터링 서버 적용

1. `infra/prometheus/prometheus.yml`에서 `postgres`의 `static_configs`에 실제 대상을 등록한다.
   저장소 원본의 `targets: []`는 미등록 상태다. 기존 `db-host`의 실제 대상은 보존한다.

   ```yaml
   - job_name: postgres
     scrape_timeout: 10s
     static_configs:
       - targets: ["<DB서버사설IP>:9187"]
         labels:
           db_role: dev
       - targets: ["<DB서버사설IP>:9188"]
         labels:
           db_role: prod
       - targets: ["<DB서버사설IP>:9189"]
         labels:
           db_role: shared
   ```

2. 새 Prometheus 설정과 대시보드 JSON을 반영하고 Prometheus만 재생성한다.
   Grafana가 저장소의 provisioning 마운트를 사용하는 경우 대시보드는 자동 갱신된다.
   수동 Import 방식으로 운영 중이면 같은 UID의 대시보드를 Import로 갱신한다.

   ```shell
   docker compose --env-file infra/monitoring/.env --file compose.monitoring.yaml \
     up -d --no-deps --force-recreate prometheus
   ```

3. `up{job="postgres"}`가 **db_role별 3개, 모두 1**인지 확인한다.
   DB 접속과 두 collector 성공도 확인한 뒤 Grafana의 세 DB 표시를 확인한다.
   대기 없는 DB는 0, 미등록·수집 실패는 `—`다. exporter 수집 성공만으로 DB 정상이라고 판단하지 않는다.

락 패널은 `wait_event_type="Lock"`인 **대기 세션 수**다. 보유한 락 수·deadlock 발생 건수와 다르다.
최장 트랜잭션은 열린 트랜잭션의 나이이며 락을 기다린 시간은 아니다.
15초 수집 사이에 끝나는 짧은 락 대기는 놓칠 수 있으므로 API 지연과 연결풀 대기를 함께 본다.
통계·계정 요구 사항은 [고정 버전 exporter 문서](https://github.com/prometheus-community/postgres_exporter/tree/v0.20.1)를 따른다.

### 검증과 중단

`python3 tests/monitoring/postgres_collection.py`는 별도의 임시 DB에서 행 잠금·해제·DB 장애를 검증하고 정리한다.
실제 dev·prod DB에 테스트용 락을 걸지 않는다. 합성 PromQL 검증은 [모니터링 문서](../../docs/MONITORING_SERVER_SETUP.md)를 따른다.

문제가 생기면 `toadzip-postgres-metrics` 프로젝트만 `stop`하고 PostgreSQL 수집 대상을 제거한다.
기존 DB 프로젝트에는 `down`을 실행하지 않는다. 디스크 수집만 되돌릴 때는 `--collector.diskstats`를 제거하고
Node Exporter만 재생성한다. 전용 계정·비밀 파일 삭제는 별도 운영 정리로 수행한다.
