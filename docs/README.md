# 서비스·운영 문서

개발 환경을 준비하거나 서비스를 운영할 때 참고할 안내와 예시 데이터를 관리합니다.

## 디렉터리 구조

| 디렉터리 | 역할 |
| --- | --- |
| [fixtures/](fixtures/) | 관리자 공고 JSON 가져오기의 정상·미확정·다중 공급행 예시를 담습니다. |
| [queries/](queries/) | 알림 신청의 유효 대상, 집계, 보존 상태와 스키마를 확인하는 SQL을 담습니다. |

## 주요 문서

| 파일 | 역할 |
| --- | --- |
| [SETUP.md](SETUP.md) | 환경별 설정 안내의 시작점이며 사전 준비와 공고 첨부파일 설정을 설명합니다. |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 로컬 환경 변수, Docker Compose 실행과 DB 초기화 절차를 설명합니다. |
| [DEV_SERVER_SETUP.md](DEV_SERVER_SETUP.md) | 개발 서버의 환경 변수, 실행과 HTTPS 설정을 설명합니다. |
| [PROD_SERVER_SETUP.md](PROD_SERVER_SETUP.md) | 운영 서버의 환경 변수, 실행과 HTTPS 설정을 설명합니다. |
| [MONITORING_SERVER_SETUP.md](MONITORING_SERVER_SETUP.md) | 모니터링 서버의 환경 변수와 실행 방법을 설명합니다. |
| [INTEGRATED_SEARCH.md](INTEGRATED_SEARCH.md) | 통합 검색 API와 지역 단지 조회의 요청·응답 기준을 설명합니다. |
| [ADMIN_ANNOUNCEMENT_IMPORT.md](ADMIN_ANNOUNCEMENT_IMPORT.md) | 관리자 공고 JSON의 작성, 검증과 등록 정책을 설명합니다. |
| [NOTIFICATION_DATA.md](NOTIFICATION_DATA.md) | 알림 신청 데이터 구조, 운영 조회와 스키마 점검 방법을 설명합니다. |
| [notification-retention-and-cancellation.md](notification-retention-and-cancellation.md) | 알림 신청의 보존·삭제, 비로그인 취소 확인과 수동 발송 절차를 설명합니다. |

백엔드 개발·운영 상세는 [backend/docs/](../backend/docs/README.md), 프론트엔드 개발 안내는
[frontend/README.md](../frontend/README.md), DB 운영은 [infra/db/SETUP.md](../infra/db/SETUP.md)를 참고합니다.
