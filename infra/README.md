# 인프라

DB 운영, HTTPS 인증서 갱신, 로그·메트릭 수집과 모니터링에 필요한 설정을 관리합니다.

| 디렉터리·주요 파일 | 역할 |
| --- | --- |
| [`alloy/config.alloy`](alloy/config.alloy) | 백엔드 컨테이너 로그를 수집해 모니터링 서버의 Loki로 전송합니다. |
| [`certbot/`](certbot/) | HTTPS 적용 가이드와 인증서 갱신 후 Nginx가 설정을 다시 읽게 하는 `reload-nginx.sh`를 담습니다. |
| [`db/`](db/) | 개발·운영·공유 PostgreSQL의 `compose.yaml`, 환경변수 예시와 백업·복원 운영 파일을 담습니다. |
| [`db/automation/`](db/automation/), [`db/systemd/`](db/systemd/) | AWS 백업 감시·배포 템플릿 생성 코드와 정기 백업 서비스·타이머를 담습니다. |
| [`grafana/provisioning/datasources/prometheus.yml`](grafana/provisioning/datasources/prometheus.yml) | Grafana의 기본 Prometheus 데이터 소스를 등록합니다. |
| [`loki/loki-config.yml`](loki/loki-config.yml) | 로그 저장소와 보관 기간 등 Loki 서버 설정을 정의합니다. |
| [`monitoring/`](monitoring/) | Grafana 환경변수 예시와 모니터링 도메인을 Grafana로 연결하는 `Caddyfile`을 담습니다. |
| [`prometheus/prometheus.yml`](prometheus/prometheus.yml) | 개발·운영 백엔드의 메트릭 수집 대상과 주기를 정의합니다. |

운영 절차는 [DB 서버 설정](db/SETUP.md), [백업·복원](db/BACKUP.md), [HTTPS 적용](certbot/README.md), [모니터링 서버 설정](../docs/MONITORING_SERVER_SETUP.md)을 따릅니다.
