# 검색

단지·공고·지역 통합 검색, 외부 지역·지하철역 검색과 지도 필터를 안내한다.

## 통합 검색

`GET /api/v1/search`

| 파라미터 | 설명 |
| --- | --- |
| `query` | 공백 제외 2자 이상의 검색어 |
| `preview` | 유형 미지정에서 기본 `true`, 결과 8개·유형별 3개 제한. 유형 지정 시 무시 |
| `page` | 0부터 100까지의 전체 결과 페이지 |
| `size` | 유형 미지정 전체 결과는 20으로 고정. 유형 지정은 기본 5, 1~20 |
| `type` | `ANNOUNCEMENT/COMPLEX/REGION`. 생략하면 통합 검색, 지정하면 해당 유형만 페이지 조회 |
| `rentalTypes` | 임대 유형 필터 |
| `applicationStatuses` | 모집 상태 필터 |
| `hasActiveAnnouncement` | 모집 중 공고가 있는 단지 필터 |

응답은 `data.announcements`, `data.complexes`, `data.regions`에 유형별 결과를 담는다.
유형 지정 검색은 `totalCount`를 제공하며 유형 미지정·검색 실패·건수 집계 실패는 null이다.
일부 유형만 실패하면 성공 결과를 유지하고 `data.failures`에 실패 유형과 재시도 메시지를 담는다.
잘못된 검색 요청은 `400 INVALID_SEARCH_REQUEST`를 반환한다.

### 지역 단지 조회

`GET /api/v1/complexes` 또는 `GET /api/v2/complexes/map`에 `regionCode`를 보낸다.

지도 조회에는 현재 지도 확대 수준인 `zoom`도 필수로 보낸다.
지도와 목록에는 같은 행정구역과 필터를 적용한다.

## 위치 검색

기존 `/api/v1/search?type=REGION`은 내부 시·도/시·군·구 목록을 검색한다.
프론트엔드는 내부 목록의 전체 건수가 0일 때 네이버 Maps Geocoding으로 읍·면·동을
보완하며, 지하철역은 별도 지하철역 그룹에서 키워드 검색한다.

### API

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

### 서버 설정

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

## 지역 카탈로그

### 기준과 범위

공식 자료의 취득일과 원문 해시는 `region/catalog-provenance.json`에서 확인한다.

- 시·도와 자치 시·군·구: [행정안전부 지방자치단체 현황](https://www.mois.go.kr/frt/sub/a04/localGovernment/screen.do)의 게재 순서.
- 읍·면·동: [행정표준코드관리시스템 법정동 코드](https://www.code.go.kr/stdcode/regCodeL.do?menuNo=101010100010)의 현행 코드와 상위 지역별 `서열`.
- 비자치구는 상위 시 아래 코드 순으로 배치한다. 부모 지역은 자식보다 먼저 표시한다.
- 코드 숫자 오름차순만 사용하지 않는다. 강원·전북의 코드 변경 등으로 공식 게재 순서와 다르다.
- 행정동과 법정동을 혼합하지 않는다. 주택 주소의 법정동 코드에 맞춘 읍면동 5,067개다.
- 현재 공식 시도 순서는 서울 → 전남광주 → 부산 → 대구 → 인천 → 대전 → 울산 → 세종 → 경기 → 강원 → 충북 → 충남 → 전북 → 경북 → 경남 → 제주다.

### 지역 필터

`/api/regions`와 통합 검색의 지역 결과, 필터 선택지가 같은 정렬키를 사용한다.
통합 검색은 페이지를 나누기 전에 지역을 정렬한다.

| 코드 | 의미 | 단지·공고 조회 |
|---|---|---|
| 2자리 | 시도 | 기존 시군구 확장 |
| 5자리 | 시군구 | 기존 하위 시군구·과거 코드 확장 |
| 10자리, 끝 두 자리 00 | 읍면동 | 법정동 코드 앞 8자리 일치 |

읍·면 하위 리도 앞 8자리로 함께 조회한다. 전남·광주 과거 읍면동 코드는
공식 과거 자료와 기존 시군구 별칭에 근거해 현행 지역과 연결한다.
지도 집계 후보는 상위 시군구로 찾되 DB 집계에는 읍면동 필터를 유지한다.

읍면동 지도 이동 좌표는 해당 지역의 등록된 단지 좌표 범위 중심이다.
공식 행정구역 중심점이 아니며, 단지가 없으면 좌표를 제공하지 않는다.
읍면동 경계 도형은 이 카탈로그에 포함하지 않는다.

### 갱신

공식 다운로드 ZIP은 각 시도의 `<시도코드>.zip` 이름으로 준비한다.
현행 16개 시도는 `disuseAt=0`, 과거 29·46은 `disuseAt=ALL`로 내려받는다.
같은 폴더에 현황 페이지 원문 `mois-local-government.html`을 저장한다.

```bash
python3 scripts/regions/import_catalog.py /path/to/official-source
```

스크립트는 백엔드 CSV와 프론트엔드 JSON을 함께 생성한다.
`region/catalog-provenance.json`에 URL, 취득일, 원문 SHA-256을 기록한다.
공식 자료의 명칭·코드·부모 관계를 검증하고 불일치하면 생성을 중단한다.
원문 ZIP을 실행 파일로 사용하지 않는다.

## 금액과 면적

가격과 면적은 같은 주택형 하나가 모든 조건을 만족해야 한다. 기본 금액을 공고 모집 금액으로 대신 채우지 않는다.
주택형별 금액과 요약값의 기준은 [단지 데이터](../operations/HOUSING.md#금액-검색)를 따른다.
