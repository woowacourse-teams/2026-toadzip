# 서비스 운영 문서

환경을 준비하거나 API·데이터 운영 기준을 확인할 때 읽는다.
백엔드 개발 기준과 수집 운영은 [백엔드 문서](../backend/docs/README.md)에서 찾는다.

## 환경 설정

[환경 설정](SETUP.md)에서 개발·운영 서버를 준비한다. 로컬 개발은 [로컬 설정](LOCAL_SETUP.md)을 따른다.
[DB 서버](../infra/db/SETUP.md), [HTTPS](../infra/certbot/README.md)와 [모니터링](MONITORING_SERVER_SETUP.md)은 별도 운영 안내를 따른다.

## 기능과 데이터

| 작업 | 문서 |
|---|---|
| 지역·단지·공고 검색 | [통합 검색 API](INTEGRATED_SEARCH.md) |
| 공고 JSON 검증·등록 | [관리자 공고 가져오기](ADMIN_ANNOUNCEMENT_IMPORT.md) |
| 알림 신청 데이터·DB 점검 | [알림 데이터](NOTIFICATION_DATA.md) |
| 알림 만료·비로그인 취소·수동 발송 | [알림 보관과 취소](notification-retention-and-cancellation.md) |

## 운영 자료

[fixtures/](fixtures/)는 공고 JSON 정상·미확정·다중 공급행 예시다.
[queries/](queries/)는 알림 유효 대상·집계·보관 상태·스키마를 확인하는 SQL이다.
프론트엔드 개발은 [프론트엔드 안내](../frontend/README.md)를 따른다.
