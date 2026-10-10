# 서비스 문서

환경 설정과 현재 기능·데이터·운영 안내를 찾는 목록이다. 실험·개발 이력과 개인 공부는 `.local/`에 둔다.
백엔드 개발 규칙은 [하네스 지도](../backend/docs/README.md)에서 찾는다.

## 실행과 운영

| 작업 | 문서 |
|---|---|
| 로컬·서버 실행, 모니터링 | [환경 설정](SETUP.md) |
| 스키마 적용·원천 이관·복구 | [DB 운영](../backend/docs/operations/DATABASE.md) |
| 수집 시작·중지·실패·원천 조회 | [수집 운영](../backend/docs/operations/INGEST.md) |
| LH 수집 설정·품질·연결 복구 | [공고 수집](../backend/docs/operations/ANNOUNCEMENT_COLLECTION.md) |
| 단지 통합·주택형·좌표 적재 | [단지 데이터](../backend/docs/operations/HOUSING.md) |
| 관리자 수정·삭제·단지 검증·공고 JSON 등록 | [관리자 데이터 관리](../backend/docs/operations/ADMIN_DATA.md) |
| 개인정보 DB 검사·서비스 교체·복구 | [개인정보 배포](../backend/docs/operations/PRIVACY.md) |

## 서비스 기능

| 기능 | 문서 |
|---|---|
| 통합·지역·지하철역 검색 | [검색](../backend/docs/features/SEARCH.md) |
| 첨부파일·조회수·일정 대상 | [공고 상세](../backend/docs/features/ANNOUNCEMENTS.md) |
| 로그인·사용자 의견 | [로그인과 의견](../backend/docs/features/USER.md) |
| 알림 설정·보관·수동 취소 | [알림](../backend/docs/operations/NOTIFICATIONS.md) |
| 분석 동의·유효 상태·수집 제어 | [분석 동의](../backend/docs/features/PRIVACY.md) |
| 개인정보 테이블·기존 업무 연동 | [개인정보 저장](../backend/docs/features/PRIVACY_STORAGE.md) |
| 거리뷰 정책·초기화 결과 | [거리뷰](../backend/docs/features/STREET_VIEW.md) |

## 운영 자료

[fixtures/](fixtures/)는 공고 JSON 예시, [queries/](queries/)는 알림 대상·집계·보관·스키마 점검 SQL이다.
HTTPS 발급·갱신은 [인증서 운영](../infra/certbot/README.md)을 따른다.
처리 목적·보유기간·권리행사·운영 책임은 [개인정보 운영 정책](privacy-policy.md)을 따른다.
프론트엔드 안내는 [frontend](../frontend/README.md)에서 찾는다.
