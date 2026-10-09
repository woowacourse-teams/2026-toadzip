# 지역·지하철역 위치 검색

기존 `/api/v1/search?type=REGION`은 내부 시·도/시·군·구 목록을 검색한다.
프론트엔드는 내부 목록의 전체 건수가 0일 때 네이버 Maps Geocoding으로 읍·면·동을
보완하며, 지하철역은 별도 지하철역 그룹에서 키워드 검색한다.

`GET /api/v1/locations/search`는 공개 위치 검색 API다.

| 파라미터 | 계약 |
|---|---|
| `query` | 공백 제외 2자 이상, 정규화 후 최대 50자 |
| `type` | 필수, `REGION` 또는 `SUBWAY_STATION` |
| `page` | 0부터 시작, 기본 0, 최대 100 |
| `size` | 기본 5, 1~15 |

응답 `data`는 `items`, `page`, `size`, `hasNext`, `totalCount`를 포함한다.
항목에는 `type`, `id`, `title`, `subtitle`, WGS84 `latitude`·`longitude`가 있다.
지역은 주소 구성 요소 `SIDO`·`SIGUGUN`·`DONGMYUN`·`RI`로 이름을 구성한다.
도로명·건물·번지가 포함된 응답은 제외하며 그 좌표를 읍·면·동 대표 좌표로 취급하지
않는다. 후처리된 지역의 전체 건수는 `null`이고 원본 건수로 더보기를 계산한다.
지하철역은 네이버 Search API 지역 검색을 사용한다. `display`는 최대 5, `start`는 1만
지원하므로 `hasNext=false`다. `page>0`은 외부 호출 없이 빈 마지막 페이지를 반환한다.
지하철역은 응답 분류의 마지막 항목이 `지하철,전철`·`지하철역`·`전철역`인 결과만 제공한다.
기관·랜드마크·상점·일반 철도역 등은 제외하고 건수는 필터 후 반환한 지하철역 수다.
네이버가 반환하는 최대 5개 후보 중 지하철역이 없으면 빈 결과를 반환한다.
일반 장소 검색용이었던 `PLACE` 유형은 지원하지 않는다.
이름의 HTML 강조 태그를 제거하고 엔티티를 해제하며,
WGS84 `mapx`·`mapy` 정수 좌표를 10,000,000으로 나누어 경도·위도로 변환한다.

외부 지역에는 내부 필터·알림용 `regionCode`를 부여하지 않는다. 사용자가 선택하면
경계/필터 대신 좌표로 지도를 이동한다. 지하철역도 주택 상세를 열지 않고 지도만 이동한다.

서버 환경변수는 다음과 같다. 두 서비스의 인증 정보는 서로 다른 발급처를 사용한다.

| 용도 | 환경변수 | 발급처·사용 설정 |
|---|---|---|
| 지하철역 | `NAVER_SEARCH_CLIENT_ID`, `NAVER_SEARCH_CLIENT_SECRET` | 네이버 개발자센터, 검색 API |
| 지역 | `NAVER_MAPS_API_KEY_ID`, `NAVER_MAPS_API_KEY` | 네이버 클라우드 Maps, Geocoding |

루트 Compose가 백엔드에만 전달한다. 브라우저 환경변수·빌드 인자에는 넣지 않는다.
지도 SDK용 공개 `VITE_NAVER_MAPS_CLIENT_ID`와 서버의 비공개 키를 구분한다.
요청 유형의 키가 없거나 외부 인증·쿼터·통신·응답 검증에 실패하면 HTTP 503과
`LOCATION_SEARCH_UNAVAILABLE`을 반환한다. 기존 내부 검색은 계속 사용할 수 있다.
외부 요청은 연결 2초, 응답 3초 제한이며 자동 재시도하지 않는다.

서버의 `.env`를 설정한 후 백엔드 컨테이너를 재생성한다. 새 프론트엔드도 배포해야
지하철역 그룹이 표시된다. 실제 설정값은 저장소에 커밋하지 않는다.

원본 계약: [네이버 지역 검색](https://developers.naver.com/docs/serviceapi/search/local/local.md),
[WGS84 좌표 변경](https://developers.naver.com/notice/article/12567),
[Maps Geocoding](https://api.ncloud-docs.com/docs/application-maps-geocoding).
