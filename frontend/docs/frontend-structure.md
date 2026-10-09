# 프론트엔드 구조

## 탐색 화면

`src/public-housing/PublicHousingExplorer.tsx`는 지도, 목록, URL과 상세 화면을 연결한다.
기능별 상태와 변환은 아래 모듈이 소유한다. 화면이 사용하는 같은 상태를 다른 계층에 복제하지 않는다.

| 위치 | 책임 |
| --- | --- |
| `complexes/useComplexResults.ts` | 단지 첫 페이지·더보기·재시도, 요청 취소와 늦은 응답 차단, 적용된 지도 범위 |
| `announcements/` | 공고 목록 조회와 공고 탭 상태 |
| `filters/AnnouncementFilterPanel.tsx` | 공고 필터 입력·적용·초기화 |
| `filters/ComplexFilterToolbar.tsx` | 단지 필터 팝오버·모바일 시트와 적용 동작 |
| `filters/ComplexFilterFields.tsx` | 두 단지 필터 UI가 사용하는 입력 필드 |
| `filters/searchFilterForm.ts`, `complexFilterTopics.ts`, `complexFilterPresentation.ts` | 두 화면의 폼 해석, 주제별 조건 교체, 선택값 표시 |
| `filters/RegionFilterFields.tsx`, `searchFilterOptions.ts` | 두 필터가 공유하는 지역 선택 UI·조회·취소·fallback과 옵션(화면별 CSS·로딩 문구는 호출부 소유) |
| `navigation/detailLocation.ts`, `detailHistory.ts` | 상세 URL와 history state의 검증·변환 |
| `components/HousingDetailStatePanel.tsx` | 단지·공고 상세의 로딩·미발견·오류 표시와 포커스·닫기 |
| `presentation/` | API 모델에서 카드·상세 표시 모델로 변환, 라벨·HTTP(S) 링크 정책 |
| `src/street-view/` | PC 단지 거리뷰의 실행 정보·지도 영역·출입구 핀·시도 결과와 별도 iframe SDK 수명주기 |

목록 요청 성공 후의 스크롤·강조 초기화와 지도 정책은 Explorer에 둔다.
URL은 공유 가능한 필터·상세 선택을 소유하고, history state는 상세 간 이동의 복귀 대상과 포커스 정보를 가진다.
컴포넌트 분리는 줄 수보다 별도 상태·부수효과·검증 가능한 계약을 기준으로 한다.

`AnnouncementFilterPanel`에는 실제 사용하는 공고 조건만 있다. 단지 조건을 추가할 때는
`ComplexFilterToolbar`를 수정한다. 두 필터가 제공하는 모집 상태와 적용 시점 차이를 유지한다.
입력 폼을 적용 조건의 key로 교체할 때는 새 폼 안에 포커스를 복원한다.

## 지도 SDK 경계

`src/maps/naver/NaverMap.tsx`가 SDK 로딩, 지도 인스턴스, 이벤트와 overlay 수명주기를 소유한다.

- `naverMapTypes.ts`: 이 연동에서 필요한 SDK·마커·카메라 계약.
- `mapCamera.ts`: SDK 좌표의 유한성·위경도 범위 검증, bounds 변환과 카메라 이동 계산.
- `markerData.ts`: 마커 종류·표시 데이터와 내용 비교 키.
- `markerOverlays.ts`: overlay 생성·갱신·선택·포커스·이벤트 해제.
- `regionBoundaryOverlay.ts`: 지역 경계 polygon 생성과 해제.

지도 객체를 새 전역 상태로 옮기지 않는다. 추가한 SDK 이벤트와 DOM 핸들러는 해당 소유자에서 해제한다.
마커 표시 필드를 추가하면 내용 비교 키와 갱신 행동 테스트도 함께 확인한다.

거리뷰는 기존 GL 로더와 분리한다. Explorer의 기능 전용 `useStreetView`가 실행 정보와 세션을,
`StreetViewEntry`가 버튼·설명을, `StreetViewPanel`이 상세 옆 지도 영역을 표시한다. 지도와 상세는
마운트를 유지하고 목록은 DOM·스크롤을 보존한 채 숨긴다. 검색·목록·상세 전환은 Explorer에서 연결한다.
`StreetViewFrame`은 자식 문서 메시지와 초기화 기한을 소유한다. 별도 `street-view.html`의
`runtime.ts`는 파노라마 SDK와 출입구 마커를 관리한다. API·이벤트 수집과 SDK 실행 경계,
제공 정책은 [단지 주변 거리뷰](street-view.md)를 따른다.

## 관리자와 API

- `src/api/apiBaseUrl.ts`는 기본 주소 정책만 공유한다. CSRF, 인증, 응답 검증과 오류는 각 API 모듈이 소유한다.
- 주소 결정 시점도 계약이다. 관리자 API·알림 취소 API는 모듈 초기화, repository는 생성, 사용자 인증·통합 검색·첨부파일은 호출 시점의 환경 설정을 사용한다. 명시적 빈 repository 주소는 환경 기본값보다 우선한다.
- `public-housing/api/httpErrorBody.ts`는 두 공개 repository의 오류 body 해석·AbortError 재전파만 공유한다. 오류 class와 기본 메시지는 각 repository에 둔다.
- `admin/management/api.ts`는 HTTP 요청, `managementContract.ts`는 관리 모델과 응답 검증을 담당한다.
- `ManagementSummaryTable`과 `ManagementStatus`는 목록·상세·선택 화면에서 실제 공유하는 표시다.
- `ManagementWorkspace`는 단지·공고 표와 등록·편집 영역을 한 페이지에 배치한다. 선택 항목은 경로, 검색·페이지는 쿼리가 소유하며 저장·삭제·복구 후 목록을 다시 조회한다. 기존 상세 URL과 `returnTo` 검색 조건도 지원한다.
- 공고 직접 입력 중 새 단지를 등록해 연결할 수 있다. 선택 단지의 기관·유형·PNU·단지명을 가져오고 공고명 등 작성 중인 입력은 보존한다. 단지 등록의 19자리 PNU는 법정동·시도·시군구 코드를 채우며 주소·좌표는 추정하지 않는다.
- `admin/ingest/PipelineResult.tsx`는 실행 제어와 실행 이력이 공유한다. 폴링·실행·중지는 `DataPipelineControl`에 남는다.
- `admin/registration/registrationOptions.ts`는 두 등록 폼의 옵션을 공유한다. 관리 검색의 더 넓은 허용 값과 합치지 않는다.
- 등록 페이지 테스트는 검증 대상과 함께 `admin/registration/RegistrationPages.test.tsx`에 둔다.
- 일정 편집은 편집 시작마다 최신 서버 값에서 draft를 만든다. 제출은 FormData 한 번과 기존 `formValues`를 사용해 native 입력의 빈값·숫자·날짜 정규화를 보존한다.
- 공급 편집은 최초 연결 단지와 새 선택 단지의 조회를 같은 소유자에서 취소한다. 이전 응답이 최신 주택형 선택을 덮지 않게 한다.

## 알림과 세션 변경

`NotificationInterestProvider`가 상태 조회·신청/취소·세션 초기화의 요청 세대를 함께 관리한다.
홈의 `NotificationInterestSessionControl`은 성공한 로그아웃을 이 소유자에게 전달한다.
검색 화면을 다시 mount하거나 별도 전역 인증 상태를 추가하지 않는다.
모달의 키보드 순환에는 이메일 입력, 활성 버튼과 도움 링크를 포함한다.

## 문서 미리보기

`components/useDocumentSearchShortcuts.ts`는 PDF·HWP 모달 내부의 검색 열기·닫기 단축키와 이벤트 정리만 공유한다.
문서별 검색 상태, IME·debounce 처리, Blob·worker·객체 URL 수명주기는 기존 뷰어가 계속 소유한다.

## 검증 위치

각 모듈 옆의 `*.test.ts(x)`가 가장 가까운 계약을 검증한다. 통합 연결은 `PublicHousingExplorer.test.tsx`,
지도 SDK 연결은 `NaverMap.test.tsx`, 지역 요청 경합은 `RegionFilterBehavior.test.tsx`에서 확인한다.
리팩터링 전에 기존 동작을 검증하는 테스트를 추가하고, 구조 변경 후에도 같은 사용자 행동을 확인한다.
최종 명령과 실제 브라우저 확인 범위는 [품질 게이트](quality-gates.md)를 따른다.
