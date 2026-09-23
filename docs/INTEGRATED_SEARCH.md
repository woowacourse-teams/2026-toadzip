# 통합 검색 API

## 검색

`GET /api/v1/search`

| 파라미터 | 설명 |
| --- | --- |
| `query` | 공백 제외 2자 이상의 검색어 |
| `preview` | `true`면 입력 중 결과 8개, 유형별 3개 제한 |
| `page` | 0부터 100까지의 전체 결과 페이지 |
| `size` | 전체 결과는 20으로 고정 |
| `rentalTypes` | 임대 유형 필터 |
| `applicationStatuses` | 모집 상태 필터 |
| `hasActiveAnnouncement` | 모집 중 공고가 있는 단지 필터 |

응답은 `data.announcements`, `data.complexes`, `data.regions`에 유형별 결과를 담는다.
일부 유형만 실패하면 성공 결과를 유지하고 `data.failures`에 실패 유형과 재시도 메시지를 담는다.
잘못된 검색 요청은 `400 INVALID_SEARCH_REQUEST`를 반환한다.

## 지역 단지 조회

`GET /api/v1/complexes` 또는 `GET /api/v1/complexes/map`에 `regionCode`를 보낸다.

지도와 목록에는 같은 행정구역과 필터를 적용한다.
