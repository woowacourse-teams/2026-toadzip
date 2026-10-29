# 운영

개발·운영 서비스를 배포하고 상태를 관측하며 데이터·실패를 점검하거나 복구하는 절차를 안내한다. 명령의 대상·실행 전제와 환경별 차이는 각 원본 문서에서 확인한다.

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [배포](deployment.md) | 자동·수동 배포, 특별 DB 전환과 배포 실패 대응 |
| [모니터링](monitoring.md) | 로그·메트릭·대시보드·알림 구성과 검증 |
| [Caddy 진입점](caddy-ingress.md) | Blue/Green 서버의 공개 HTTPS·Edge 연결과 포트 전환 |
| [HTTPS](https.md) | 개발·운영 인증서 발급·권한·갱신과 문제 대응 |
| [DB 운영](database/README.md) | DB 서버·Flyway 도입·기존 DB 전환·복구 |
| [수집 운영](ingest/README.md) | 실행 중지·잠금 복구·품질·성능·좌표 적재 |
| [제품 분석](analytics/README.md) | GA4·PostHog 환경 연결·이벤트·지표·수신 검증 |
| [알림 운영](notifications.md) | 알림 스키마·출시 설정·보관·전달 상태 점검 |
| [개인정보 운영](privacy.md) | 처리 정책·운영 책임과 보유·파기 점검 |

## 관련 문서

처음 환경을 준비할 때는 [환경 설정](../setup/README.md), 현재 동작·보존 정책은 [기능 명세](../features/README.md)를 따른다. 문서나 CI 성공은 실제 운영 적용 완료의 증거가 아니다.
