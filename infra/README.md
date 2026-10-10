# 인프라

DB, HTTPS와 로그·메트릭 수집 설정을 관리한다.

| 경로 | 용도 |
|---|---|
| [db/](db/) | PostgreSQL 실행 설정과 환경 변수 |
| [certbot/](certbot/README.md) | HTTPS 발급·갱신과 Nginx 반영 |
| [alloy/config.alloy](alloy/config.alloy) | 백엔드 로그를 Loki로 전송 |
| [loki/loki-config.yml](loki/loki-config.yml) | 로그 저장소와 보관 기간 |
| [prometheus/prometheus.yml](prometheus/prometheus.yml) | 메트릭 수집 대상과 주기 |
| [grafana/provisioning/datasources/prometheus.yml](grafana/provisioning/datasources/prometheus.yml) | Grafana 데이터 소스 |
| [monitoring/](monitoring/) | 모니터링 환경 변수와 Caddy 설정 |

실행은 [DB 서버](db/SETUP.md)와 [모니터링 서버](../docs/MONITORING_SERVER_SETUP.md) 설정을 따른다.
