# 기능 명세

현재 서비스가 제공하는 기능의 사용 흐름·API 계약·데이터 정책과 실패 조건을 설명한다. 기능을 바꿀 때 서버와 화면이 지켜야 할 계약을 확인한다.

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [수집·정제](ingest/README.md) | 공고·단지 원천 수집, 정제·연결과 실행 상태 |
| [관리자](admin/README.md) | 데이터 조회·수정·비교·검토와 공고 JSON 등록 |
| [공고](announcements/README.md) | 공고 조회수 집계와 중복·동시성·실패 처리 |
| [검색·지도](search/README.md) | 통합·위치 검색, 지역 데이터·지도·거리뷰 |
| [인증](authentication/README.md) | 소셜 로그인과 계정 생명주기 |
| [알림](notifications/README.md) | 알림 설정·생성·전달과 알림함·웹 푸시 |
| [개인정보](privacy/README.md) | 동의, 브라우저 수집 제어와 영속 데이터 제거 경계 |
| [공고 첨부파일](documents/README.md) | 원본 제공·다운로드와 PDF·HWP·HWPX 뷰어 |

## 관련 문서

구현 방식은 [개발 기준](../development/README.md), 실제 배포·점검·복구는 [운영](../operations/README.md)을 따른다.
