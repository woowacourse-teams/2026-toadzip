# 인프라

DB 운영, HTTPS 인증서 갱신, 로그·메트릭 수집과 모니터링에 필요한 설정을 관리합니다.

## 구성 안내

| 구성 | 프로젝트에서의 역할 |
| --- | --- |
| [`alloy/config.alloy`](alloy/config.alloy) | 백엔드 예외 스택과 쿼리 값을 제외한 Nginx access 로그를 수집해 Loki로 전송합니다. |
| [`certbot/`](certbot/) | 인증서 갱신 후 Nginx가 설정을 다시 읽게 하는 `reload-nginx.sh`를 담습니다. 적용 절차는 [HTTPS 운영](../docs/operations/https.md)을 따릅니다. |
| [`db/`](db/) | PostgreSQL 운영 설정과 호스트 CPU·메모리·디스크, DB별 락·트랜잭션 수집용 Compose를 담습니다. |
| [`grafana/provisioning/datasources/prometheus.yml`](grafana/provisioning/datasources/prometheus.yml) | Grafana의 기본 Prometheus 데이터 소스를 등록합니다. |
| [`grafana/dashboards/`](grafana/dashboards/) | API·부하테스트 대시보드의 조회식, 환경 선택과 요청 로그 연결을 관리합니다. |
| [`grafana/provisioning/`](grafana/provisioning/) | 대시보드와 Prometheus·Loki 데이터 소스를 자동 등록합니다. |
| [`loki/loki-config.yml`](loki/loki-config.yml) | 로그 저장소와 보관 기간 등 Loki 서버 설정을 정의합니다. |
| [`monitoring/`](monitoring/) | Grafana 환경변수 예시와 모니터링 도메인을 Grafana로 연결하는 `Caddyfile`을 담습니다. |
| [`runtime/`](runtime/) | 배포 중 변경되는 연결 색상·정적 파일·잠금·기록의 저장 위치와 준비 방법입니다. |
| [`prometheus/prometheus.yml`](prometheus/prometheus.yml) | 개발·운영 백엔드와 공유 DB 호스트의 메트릭 수집 대상과 주기를 정의합니다. |

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [DB 서버](../docs/operations/database/server.md) | 환경별 DB·볼륨·포트와 메트릭 구성의 준비·점검 |
| [HTTPS](../docs/operations/https.md) | 인증서 최초 발급·권한·갱신과 Nginx 반영 |
| [모니터링](../docs/operations/monitoring.md) | Grafana·Loki·Prometheus 기동·대시보드·알림 검증 |

DB 백업 자동화는 저장소에서 제공하지 않습니다. 백업·복원 가능 여부는 실제 서버의 운영 설정에서 별도로 확인합니다.
