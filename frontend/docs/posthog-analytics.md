# PostHog 제품 분석

이 문서는 현재 구현된 사용자 행동의 수집 계약과 세 가지 제품 지표의 계산 기준을 정의한다. 회원 알림 설정·보관함 흐름을 기준으로 수집하며 기존 GA4 수집은 유지한다. 다른 행동은 후속 가설 검증에 사용할 수 있도록 수집하되 개별 보고서는 만들지 않는다.

## 세 가지 지표

모든 날짜와 기간은 `Asia/Seoul`을 기준으로 한다. 사용자 단위는 로그인 계정이 아니라 PostHog의 브라우저 `distinct_id`다. 다음 지표를 만들 때 기본 제공 `활성 사용자`나 `모든 이벤트` 조건을 대신 사용하지 않는다.

| 지표 | 대상 행동과 계산 |
| --- | --- |
| 활성 사용자 수 | 성공적으로 불러온 단지 또는 공고 상세가 활성 탭의 전경에 실제 표시되어 `view_complex` 또는 `view_announcement`가 발생한 브라우저의 합집합 |
| DAU | 오늘 00:00부터 현재까지 활성 브라우저 수 |
| WAU | 오늘과 이전 6일의 활성 브라우저 합집합 |
| MAU | 오늘과 이전 29일의 활성 브라우저 합집합 |
| 7일 리텐션 | 기준일 D의 활성 브라우저 중 D+1부터 D+7 중 하루 이상 같은 활성 행동을 다시 한 비율 |
| 서비스 핵심가치지표 | 선택한 기간에 `notification_preregistration_completed`와 `target_type=COMPLEX`를 모두 만족한 고유 브라우저 수 |

단지와 공고의 사용자 수를 더하면 두 종류를 모두 본 사람이 중복된다. DAU를 더해서 WAU나 MAU를 계산해도 중복된다. 반드시 선택한 기간의 두 이벤트 합집합에서 고유 브라우저를 센다. MAU는 달력 월 전체가 아닌 최근 30일이다.

리텐션의 분모는 기준일의 **전체 활성 브라우저**다. 신규 사용자만의 코호트가 아니다. 분자는 기준일에 포함된 브라우저 중 `[D+1 00:00, D+8 00:00)`에 돌아온 브라우저 수다. D+1부터 D+7 사이 어느 날이든 한 번 이상 돌아오면 분자에 포함하며, D+7에만 돌아온 브라우저도 포함한다. 같은 날 재방문과 D+8 이후 재방문은 해당 계산에 포함하지 않는다. 7일의 관찰 기간이 끝나지 않았거나 분모가 0인 기준일은 `관찰 중` 또는 산출 불가로 표시한다. 기본 Retention 설정과 이 정의가 일치한다고 가정하지 않고 저장한 SQL/HogQL의 날짜 경계와 분모를 확인한다.

핵심가치지표는 특정 단지의 향후 공고에 대한 알림 사전신청 의향을 나타낸다. 현재 실제 알림은 발송하지 않는다. 서버가 신청을 새로 활성화하거나 만료·취소 상태를 재활성화한 `ACTIVATED` 결과를 반환했을 때만 완료 이벤트를 수집한다. 이미 활성 상태인 신청의 재요청은 제외한다. 한 브라우저가 여러 단지를 신청해도 기간 내 고유 사용자 수는 1명이다. 이후 취소해도 과거 완료 기록은 유지되므로 현재 구독자 재고나 발송 성공 수를 의미하지 않는다. 화면에서 준비 중 안내를 읽었다는 증거로도 해석하지 않는다.

첫 방문 안내, 지도 조작, 검색 결과와 목록 탐색, 거리뷰만으로는 활성 사용자가 되지 않는다. 상세가 로딩 중이거나 실패했거나, 다른 모달 뒤에 가려져 있거나, 탭이 백그라운드일 때는 상세 조회를 미룬다. 정상 상세가 이후 실제 보이면 그때 수집한다. GA4의 기존 상세 이벤트는 데이터 로드 기준을 유지하므로 같은 기간의 숫자가 다를 수 있다.

### 대시보드

| 환경 | PostHog 프로젝트 | 서비스 핵심 지표 대시보드 | 검증 상태 |
| --- | --- | --- | --- |
| 개발 | [bokduckbang-dev](https://us.posthog.com/project/650769/home) | [세 지표 대시보드](https://us.posthog.com/project/650769/dashboard/2185626) | 생성 완료. 로컬 브라우저 QA 이벤트·마스킹 리플레이 수신 확인 |
| 운영 | [bokduckbang-prod](https://us.posthog.com/project/652606/home) | [세 지표 대시보드](https://us.posthog.com/project/652606/dashboard/2185616) | 생성 완료. 운영 배포 후 실제 수집 확인 필요 |

개발과 운영 대시보드에는 각각 위 세 종류의 지표를 구성했다. 개발 프로젝트에는 로컬 및 자동화 QA의 합성 데이터가 포함되어 있으므로 현재 수치를 실제 고객 지표로 해석하지 않는다. 설명에 브라우저 식별, KST, 수집 시작일과 리텐션 관찰 기간을 남긴다. 수집 이전이나 연결이 확인되지 않은 기간은 사용자 0명으로 단정하지 않는다.

### 대시보드 SQL 유지보수

실제 구성한 개발 프로젝트의 HogQL을 다음 파일에 보관한다.

| 지표 | 저장된 SQL | 기간 적용 방식 |
| --- | --- | --- |
| 활성 사용자 수 | [active.sql](posthog/active.sql) | KST 오늘을 기준으로 DAU·최근 7일 WAU·최근 30일 MAU 계산 |
| 7일 리텐션 | [retention.sql](posthog/retention.sql) | KST 오늘 기준 최근 30일의 활성일별 계산. D+8이 되기 전 기준일은 `NULL` |
| 서비스 핵심가치지표 | [core.sql](posthog/core.sql) | `{filters}`에 주입된 선택 기간 적용 |

파일의 `properties.environment = 'dev'`는 개발 프로젝트 조건이다. 운영 프로젝트에 적용할 때는 이를 `'prod'`로 바꾸고 저장할 프로젝트도 확인한다. 핵심가치지표의 `{filters}`는 일반 SQL 문자열이 아니라 PostHog 매크로이며, 쿼리 source의 `dateRange` 설정이나 연결된 대시보드의 기간 필터에서 조건을 받는다. 이 조건 없이 SQL만 복사해 전체 기간으로 집계하지 않는다.

활성 사용자와 리텐션 SQL은 `now()`를 사용해 KST 오늘에 고정되어 있으므로 대시보드 기간 선택을 바꿔도 기준일이 바뀌지 않는다. 과거 특정 기준일을 분석하려면 별도 쿼리에서 날짜를 명시한다. 계산 정의를 변경할 때는 코드 이벤트 계약, 이 SQL 파일과 개발·운영의 저장된 쿼리를 함께 맞춘다.

## 환경 연결과 사용자 식별

PostHog 변수 4개는 로컬·서버 모두 저장소 루트 `.env`에서 관리한다. 로컬 `npm run dev`와 `npm run build`에서는 Vite 설정이 이 네 이름만 루트에서 선택해 읽는다. 나머지 프론트 변수와 `envDir`은 기존 `frontend/` 기준을 유지한다. `frontend/.env*`에 남은 PostHog 값은 사용하지 않으며 루트에도 값이 없으면 빈 값으로 처리한다.

루트의 모드별 `.env.development`, `.env.production`과 `.local` 파일은 Vite의 우선순위를 따르고, 실행 프로세스에 주입한 값이 파일보다 우선한다. 값을 바꾸면 개발 서버를 다시 시작하거나 빌드한다. Docker에서는 `compose.yaml`의 build args → 프론트엔드 Dockerfile → Vite 빌드 결과로 전달하므로 루트 `.env` 자체를 이미지에 복사하지 않는다. 개발과 운영은 서로 다른 이미지로 빌드한다. 컨테이너의 실행 환경 변수만 바꿔도 기존 정적 파일에 반영된다고 가정하지 않는다.

| 환경 변수 | 값과 역할 |
| --- | --- |
| `VITE_POSTHOG_KEY` | 해당 환경 프로젝트의 브라우저 공개 project token. 실제 값은 저장소에 쓰지 않는다. 개인 API 토큰이나 비밀 키를 넣지 않는다. |
| `VITE_POSTHOG_HOST` | 미국 리전 수집 주소 `https://us.i.posthog.com` |
| `VITE_ANALYTICS_ENV` | `dev` 또는 `prod` |
| `VITE_POSTHOG_LOCAL_ENABLED` | 로컬 실행에서만 명시적으로 `true`로 켜는 개발 수집 허용 옵션. 기본값 `false`. |

이름과 누락 동작의 원본은 [루트 환경 변수 예시](../../.env.example), SDK 경계는 [productAnalytics.ts](../src/analytics/productAnalytics.ts)다. `MODE=test`에서는 다른 설정에 관계없이 SDK 초기화와 제품 이벤트 수집을 차단한다. 설정이 없거나 잘못되어도 SDK 수집을 시작하지 않는다. 로컬은 `dev` 프로젝트와 명시적인 opt-in이 모두 필요하다. 운영 도메인의 `dev` 설정, 개발 도메인의 `prod` 설정도 거부한다. 프로젝트 token과 environment는 실제 목적지를 함께 검증한다.

PostHog가 생성한 브라우저 식별자를 `localStorage+cookie`로 유지한다. 로그인·로그아웃에서 `identify`, `alias`, `reset`을 호출하지 않으며 서버 사용자 ID, 이메일이나 알림용 client ID를 PostHog 식별자로 보내지 않는다. `auth_state`는 행동 요청 시점에 확인된 `member`, `guest`, `unknown`이다. 비동기 알림 완료·실패에는 응답 도착 후의 로그인 상태나 현재 경로가 아닌 원래 요청의 상태와 페이지를 유지한다. 지도 및 보관함 모달은 `page_name=explorer`, 별도 알림 관리 경로(`/mypage/notifications`)는 `page_name=notification_settings`다. 창으로 돌아왔을 때 알림 상태를 다시 확인하면 분석의 회원 상태도 갱신한다. 조회 중 또는 오류를 비회원으로 추정하지 않는다. 확정된 회원↔비회원 변화에서만 `auth_state_changed`를 발생시키고 최초 상태 확인은 변화 이벤트로 세지 않는다.

### 팀원 제외

공용 제외 키는 GA4와 같은 `toadzip.analytics.disabled`다. 각 팀원이 자신이 쓰는 **각 브라우저와 각 서비스 origin**에서 설정해야 한다. 개발 도메인에 설정해도 운영 도메인으로 전파되지 않는다.

```javascript
localStorage.setItem('toadzip.analytics.disabled', 'true')
location.reload()
```

이 상태에서는 SDK 초기화 전부터 수집을 차단한다. 이미 열려 있는 같은 origin의 다른 탭은 `storage` 변경을 받아 대기 이벤트와 리플레이를 중단한다. 저장소를 읽을 수 없는 브라우저도 수집하지 않는다. 이 조치는 설정 이후의 데이터에 적용되며 이미 수집된 기록을 소급 삭제하지 않는다.

테스트 후 다시 수집하려면 해당 브라우저의 제외 키를 삭제하고 새로고침한다. 사용자가 별도로 설정한 SDK 자체 opt-out은 앱의 팀원 제외 해제와 별개이며 자동으로 취소하지 않는다.

```javascript
localStorage.removeItem('toadzip.analytics.disabled')
location.reload()
```

## 개인정보와 세션 리플레이

자동 클릭 수집, 자동 페이지뷰·페이지 이탈, 히트맵·성능·예외·콘솔·네트워크 본문 및 헤더 수집을 끈다. 앱에서 허용한 이벤트명과 속성만 `captureProductEvent`를 통해 보낸다. 속성의 키와 값 형식도 [productEvents.ts](../src/analytics/productEvents.ts)에서 제한한다.

- 이메일, 전화번호, 계정 ID, 검색어 원문, 자유 입력, 피드백 내용, 인증 코드, 파일명, 문서 내용, 원시 오류 문자열과 원시 URL을 이벤트에 넣지 않는다.
- 검색어는 길이 구간만 보낸다. 지도는 공개 대상 ID·단계·조작 종류만 보내며 좌표는 보내지 않는다.
- URL의 쿼리·해시와 자동 person 속성을 제거한다. IP를 분석 속성으로 저장하지 않고 GeoIP를 끈다. 수집 서버로 전송하기 위한 네트워크 접속 자체의 IP 노출까지 제거한다는 의미는 아니다.
- 리플레이는 공개 탐색 화면에서만 허용한다. 피드백·비로그인 취소·별도 알림 관리·관리자·잘못된 경로는 녹화하지 않는다.
- 로그인·알림 안내·회원 보관함 및 계정 모달을 표시할 때 녹화를 중단하고 닫힌 뒤 허용 조건을 다시 확인한다.
- 입력값과 텍스트를 마스킹하고 `.ph-no-capture` 영역을 차단한다. 문서 모달, iframe, object, embed, canvas, 이미지, 파일 입력 등을 녹화에서 제외한다. 동적 속성·링크·리소스 URL도 제거한다.
- 리플레이용 CSS 속성(`_cssText`)은 리소스 URL·import·문자열 content 등을 제거한 뒤 레이아웃에 필요한 규칙만 보존한다. 차단 영역의 크기와 배치 정보도 제한된 형식만 허용한다. 일반 텍스트는 전부 마스킹되며 글꼴·일부 장식이 생략될 수 있어 원본 화면과 동일한 재현을 보장하지 않는다. `style` 태그의 내용은 SDK가 정적 CSS로 별도 보존하므로 사용자 생성 stylesheet는 이 보호 정책의 지원 범위에 포함하지 않는다. 현재 앱에는 사용자 생성 stylesheet가 없다.
- 거리뷰 iframe에 별도 PostHog를 넣지 않는다. 지도·문서 이미지의 실제 내용은 리플레이에서 재현되지 않을 수 있다. 이벤트가 해당 행동을 보완한다.

리플레이 옵션은 코드 리뷰만으로 검증을 끝내지 않는다. 개발 프로젝트에 실제 수신된 이벤트 속성과 리플레이를 확인해 테스트용 이메일·검색어·인증 코드·피드백 내용이 남지 않는지 점검한다. 콘솔의 원격 설정으로 코드의 보호 수준을 낮추지 않는다.

## 이벤트 공통 계약

모든 제품 이벤트에는 `event_id`, `schema_version`, `environment`, `page_name`, `auth_state`를 붙인다. `distinct_id`는 SDK가 관리한다. 아래 표는 각 행동에 추가되는 속성만 적는다. 모든 속성을 모든 이벤트에 채우는 것은 아니다. 알 수 없는 값은 추정해서 넣지 않는다.

공개 단지·공고·첨부·주택형·지역 ID는 분석 연결을 위해 허용한다. 방문·검색·편집·문서 열기·요청 식별자는 개인정보와 무관한 UUID다. 이벤트나 속성을 추가하려면 먼저 이 계약, allowlist, 발생 지점과 가까운 행동 검증을 함께 갱신한다. raw DOM autocapture로 대체하지 않는다.

### 진입·검색·목록

| 이벤트명 | 발생 조건 | 추가 속성 |
| --- | --- | --- |
| `page_view` | 탐색·피드백·비로그인 취소·알림 관리의 허용 경로에 실제 진입. 쿼리·해시 변경이나 로그인 리다이렉트 중간 경로를 별도 페이지로 세지 않음 | 공통 속성 |
| `welcome_shown` | 안내 모달이 실제 열림. 만료 후 다시 노출될 수 있어 신규 사용자 판정으로 쓰지 않음 | `exposure_id` |
| `welcome_search_started` | 안내에서 검색 화면으로 이동하는 버튼 클릭 | `exposure_id` |
| `welcome_completed` | 지역 검색 결과 선택, 시작 장소 선택, 바로 지도 보기 또는 Escape로 안내 완료 | `exposure_id`, `method` |
| `starter_place_selected` | 잠실·강남·판교 중 시작 장소 버튼 클릭. 행정구역 선택과 구별 | `starter_place_id`, `surface` |
| `search_executed` | 공백 제외 2자 이상 검색어가 200ms 안정되어 논리 검색 시작 | `search_id`, `surface`, `query_length_bucket`, `group_count` |
| `search_results_loaded` | 같은 검색의 모든 결과 그룹이 성공. 결과 0개도 성공 | `search_id`, `surface`, `query_length_bucket`, `result_count`, `failed_group_count` |
| `search_failed` | 같은 검색의 결과 그룹이 모두 정리되었고 하나 이상 실패 | 위 검색 결과 속성 |
| `select_search_result` | 실제 검색 결과를 마우스 또는 키보드로 선택 | `search_id`, `surface`, `result_type`, `result_id`, `rank`, `method`, `query_length_bucket` |
| `search_more_requested` | 결과 그룹의 추가 페이지 요청 시작 | `search_id`, `surface`, `result_type`, `request_id`, `page`, `query_length_bucket` |
| `search_more_succeeded` | 추가 페이지 응답 성공 | 위 요청 속성, `result_count` |
| `search_more_failed` | 추가 페이지 응답 실패 | 위 요청 속성 |
| `search_closed` | 통합 검색을 닫거나 선택·탐색 이동으로 종료 | `search_id`, `surface`, `query_length_bucket`, `reason` |
| `region_selected` | 검색 또는 행정구역 집계 마커에서 실제 지역 선택 | `entry_point`, `region_code`, 가능한 `region_level`, `surface` |
| `list_opened` | 지역 단지·공고·최근 본 목록이 실제 탐색 가능한 상태로 열림 | `list_view_id`, `list_type`, `entry_point` |
| `list_closed` | 현재 목록 탐색 구간이 종료되거나 목록 종류·조건 변경 | `list_view_id`, `list_type` |
| `list_scrolled` | 목록 탐색 구간에서 첫 사용자 스크롤. 숨김·inert·프로그램 스크롤 제외 | `list_view_id`, `list_type` |
| `list_more_requested` | 목록의 더보기 요청 시작 | `list_view_id`, `list_type`, `request_id` |
| `list_more_succeeded` | 더보기 성공 후 중복 제거해 실제 추가된 결과 확인 | 위 요청 속성, `appended_count` |
| `list_more_failed` | 더보기 실패 | 위 요청 속성 |
| `results_panel_snap_changed` | 모바일 목록의 peek·half·full 상태가 실제 변경 | `list_view_id`, `from_snap`, `to_snap`, `method` |

통합 검색의 지역·단지·공고 세 그룹은 하나의 `search_id`로 묶는다. 안내 화면 검색은 지역 그룹만 사용한다. 입력 변경으로 취소된 요청과 늦게 도착한 응답은 성공·실패로 수집하지 않는다. 결과 선택의 Enter는 별도 검색 실행이 아니다. 현재 지역 단지 목록은 선택한 행정구역 경계 기준이며 지도 이동 범위와 동일하지 않다.

### 지도·필터

| 이벤트명 | 발생 조건 | 추가 속성 |
| --- | --- | --- |
| `map_marker_previewed` | 사용자의 직접 포인터·키보드 관심으로 단지 마커가 500ms 이상 노출 | `complex_id`, `method` |
| `map_marker_selected` | 전환 중 차단되지 않은 단지 또는 집계 마커 선택 | `marker_type`, 단지의 `complex_id` 또는 집계의 `stage`, `result_count` |
| `map_panned` | 사용자 드래그 종료 후 실제 지도 중심 변경 | `source=drag` |
| `map_zoom_button_clicked` | 앱의 +/− 버튼 클릭. 최대·최소 배율에서는 변화가 없을 수 있음 | `direction`, `zoom_before`, `zoom_target` |
| `map_zoomed` | 실제 배율 변경이 완료됨 | `zoom_before`, `zoom_after`, `source` |
| `region_boundary_action` | 선택 지역으로 재정렬 또는 경계 해제 | `operation` |
| `filter_opened` | 필터 편집 UI가 열림 | `filter_target`, `filter_edit_id`, `topic`, `layout` |
| `filter_closed` | 필터 편집 종료 | 위 편집 속성, `had_changes`, `unapplied_changes`, `reason` |
| `filter_value_changed` | 실제 필터 선택 변경. range는 제스처 종료, 프리셋은 선택 시점 | 위 편집 속성, `field`, `method`, 가능한 `preset_id`, `region_level` |
| `filter_reset_clicked` | 주제 또는 전체 초기화 클릭 | 위 편집 속성, `reset_scope`, `state_target` |
| `apply_filter` | 실제 적용 조건의 canonical 값이 바뀜. 초기 URL 복원과 같은 값 적용은 제외 | 위 편집 속성, `apply_mode`, `changed_fields`, `methods_used`, `operation` |
| `exploration_retry_clicked` | 지도 SDK·결과·상세·검색·지역 경계 오류에서 재시도 | `surface`, 가능한 `result_type` |

`map_zoomed.source`는 `button`, `other_user`, `programmatic`, `unknown`으로 구별한다. SDK idle을 무조건 사용자 조작으로 해석하지 않는다. 마커 강조의 프로그램 포커스 복원과 목록 hover는 직접 마커 관심으로 세지 않는다.

필터 `method`는 `select`, `choice`, `slider`, `preset`이다. 현재 숫자 직접 입력 기능은 없다. 모바일 주제 초기화는 draft를 바꾸며 적용 전까지 실제 필터 변경이 아니다. 전체 초기화는 즉시 적용한다. 단지 필터 전체 해제는 공고 필터나 행정구역 경계를 함께 지우는 동작이 아니다.

### 상세·문서

| 이벤트명 | 발생 조건 | 추가 속성 |
| --- | --- | --- |
| `detail_open_requested` | 단지·공고 상세를 열려는 명시적 탐색 | `target_type`, `complex_id` 또는 `announcement_id`, `detail_visit_id`, `entry_point` |
| `view_complex` | 대상 데이터와 일치하는 단지 상세가 실제 전경에 표시 | `complex_id`, `detail_visit_id`, `entry_point` |
| `view_announcement` | 대상 데이터와 일치하는 공고 상세가 실제 전경에 표시 | `announcement_id`, `detail_visit_id`, `entry_point` |
| `detail_scrolled` | 같은 상세 방문 중 첫 사용자 스크롤 | `target_type`, `target_id`, `detail_visit_id` |
| `housing_type_selected` | 최초 선택 이후 실제 다른 주택형 선택 | `complex_id`, `housing_type_id`, `detail_visit_id` |
| `detail_back_used` | 상세 간 이전 화면으로 복귀 | `target_type`, `complex_id` 또는 `announcement_id`, `detail_visit_id` |
| `detail_closed` | 현재 상세 닫기 | 위 상세 복귀 속성 |
| `announcement_source_clicked` | 외부 공고 원문 링크 클릭. 외부 도착·읽기 완료를 뜻하지 않음 | `announcement_id`, `detail_visit_id` |
| `document_attachment_selected` | 하나뿐인 첨부를 자동 선택하거나 사용자가 파일 선택 | `announcement_id`, `attachment_id`, `selection_method`, `document_dialog_id` |
| `document_preview_requested` | 선택한 첨부의 미리보기 시도 시작 | `announcement_id`, `attachment_id`, `document_open_id`, `preview_attempt` |
| `document_preview_succeeded` | PDF 오류 없는 첫 렌더 쪽 또는 HWP/HWPX의 로드된 이미지가 실제 전경 viewport에 노출 | 위 미리보기 속성 |
| `document_preview_failed` | 요청·뷰어·렌더 실패, 미지원 형식 또는 사용할 수 없는 첨부 | 위 미리보기 속성, `failure_reason` |
| `document_viewer_action` | 검색 열기·닫기·검색 실행·이전/다음 결과·목차·배율·재시도 | 위 미리보기 속성, `action` |
| `document_download_requested` | 다운로드 버튼에서 요청 시작 | `announcement_id`, `attachment_id`, `document_download_id` |
| `document_download_handed_off` | 원본 Blob을 브라우저 다운로드에 넘김. 디스크 저장 완료를 뜻하지 않음 | 위 다운로드 속성 |
| `document_download_failed` | 다운로드 요청 또는 브라우저 인계 실패 | 위 다운로드 속성, `failure_reason` |

상세 재렌더와 반응형 dialog 재열기는 같은 방문이다. 닫고 다시 열거나 다른 상세로 이동하면 새 방문이 된다. 문서에서 Blob을 받았거나 PDF `pagesinit`이 실행된 것만으로 성공 처리하지 않는다. 미리보기 재시도는 같은 문서 열기 내에서 `preview_attempt`로 구분한다. 문서명·검색어·본문과 Blob URL은 분석 속성에 넣지 않는다.

### 알림·로그인·의견·취소

| 이벤트명 | 발생 조건 | 추가 속성 |
| --- | --- | --- |
| `notification_cta_viewed` | 가리지 않은 알림 버튼이 활성 탭에 노출. 대상·위치·세션 단위로 중복 제거 | `target_type`, `target_id`, `source` |
| `notification_cta_clicked` | 알림 받기 또는 취소 버튼 클릭 | 위 대상 속성, `action` |
| `notification_preregistration_completed` | 서버 처리 결과가 `ACTIVATED` | 위 대상 속성, `notification_action_id`, `server_event_id`, `completion_source`, `occurred_at` |
| `notification_preregistration_failed` | 신청 관련 요청 또는 결과 검증 실패 | 위 대상 속성, `notification_action_id`, `failure_reason` |
| `notification_cancel_requested` | 현재 신청의 취소 요청 | 위 대상 속성 |
| `notification_cancel_completed` | 서버 처리 결과가 `CANCELLED` | 위 대상 속성, `notification_action_id`, `server_event_id` |
| `notification_cancel_failed` | 취소 요청 또는 결과 검증 실패 | 위 대상 속성, `notification_action_id`, `failure_reason` |
| `login_modal_opened` | 로그인 모달 실제 열림 | `login_modal_id`, `entry_point` |
| `login_modal_closed` | 로그인 모달을 닫기·Escape·바깥 클릭으로 닫음 | `login_modal_id`, `reason` |
| `login_provider_clicked` | 카카오 또는 Google 로그인 시작 링크 클릭. 로그인 성공이 아님 | `login_modal_id`, `provider` |
| `account_menu_opened` | 데스크톱 계정 메뉴 또는 모바일 마이 모달을 실제 펼침 | 공통 속성 |
| `auth_state_changed` | 최초 확인을 제외한 확정 회원↔비회원 상태 변경 | `previous_auth_state`, `next_auth_state` |
| `logout_requested` | 로그아웃 버튼 클릭 | 공통 속성 |
| `logout_succeeded` | 로그아웃 API 성공 | 공통 속성 |
| `logout_failed` | 로그아웃 API 실패 | `failure_reason` |
| `feedback_started` | 의견 작성의 첫 입력 | `feedback_id` |
| `feedback_submitted` | 유효한 의견 제출 | `feedback_id`, `submission_id` |
| `feedback_succeeded` | 응답의 양수 정수 `data.id`까지 확인한 실제 접수 | 위 의견 요청 속성 |
| `feedback_failed` | 의견 요청 또는 응답 검증 실패 | 위 의견 요청 속성, `failure_reason` |
| `guest_cancellation_requested` | 비로그인 일괄 취소를 위한 코드 요청 | `cancellation_id`, `submission_id` |
| `guest_cancellation_request_accepted` | 요청 API가 `202` 반환. 이메일 존재나 발송 성공이 아님 | 위 취소 요청 속성 |
| `guest_cancellation_request_failed` | 코드 요청 실패 | 위 취소 요청 속성, `failure_reason` |
| `guest_cancellation_verification_submitted` | 받은 코드를 검증해 일괄 취소하려는 제출 | 위 취소 요청 속성 |
| `guest_bulk_cancellation_completed` | 검증·일괄 취소 트랜잭션 API가 `204` 반환 | 위 취소 요청 속성 |
| `guest_cancellation_verification_failed` | 코드 검증·취소 실패 | 위 취소 요청 속성, `failure_reason` |

알림의 `target_type`은 `COMPLEX`, `ANNOUNCEMENT`, `REGION`이며 지금 대시보드의 핵심가치지표는 `COMPLEX`만 선택한다. 현재 신청은 회원만 가능하며 버튼을 누르면 `CONFIRMED`를 보내고 서버가 `ACTIVATED`를 반환한 경우에만 완료를 수집한다. 비회원은 로그인 안내를 표시하며 신청 요청을 보내지 않는다. 이메일 입력 폼이 없어 `notification_form_viewed`, `notification_form_submitted`, `notification_form_dismissed`는 현재 발행하지 않는다. 알림 관리의 개별 해제·전체 해제는 대상별로 `source=SETTING`인 취소 요청과 서버 결과를 수집한다. 전체 해제에서 실제 요청하지 않은 대상과 `UNCHANGED` 응답은 취소 완료로 세지 않는다. 비로그인 취소 코드 발송은 별도 운영 절차이며 수신 대기 화면이 발송 성공의 증거는 아니다.

### 거리뷰

| 이벤트명 | 발생 조건 | 추가 속성 |
| --- | --- | --- |
| `street_view_open_requested` | PC 단지 상세에서 명시적으로 거리뷰 열기 | `complex_id`, `street_view_open_id`, `entry_point` |
| `street_view_blocked` | 재확인한 설정에서 거리뷰 사용 불가 | `complex_id`, `street_view_open_id`, `reason` |
| `street_view_viewed` | SDK·파노라마 준비 성공 후 실제 전경 표시 | `complex_id`, `street_view_open_id`, `attempt_id`, `aligned` |
| `street_view_failed` | 설정 조회 또는 실제 거리뷰 실패 | `complex_id`, `street_view_open_id`, 가능한 `attempt_id`, `reason` |
| `street_view_retry_clicked` | 실패·차단 화면의 재시도 | `complex_id`, `street_view_open_id` |
| `street_view_closed` | 실제 열린 거리뷰가 닫힘 | `complex_id`, `street_view_open_id`, 가능한 `attempt_id`, `was_viewed`, `view_state`, `reason` |
| `street_view_marker_status` | 출입구 핀의 표시 상태 전달 | `complex_id`, `street_view_open_id`, `attempt_id`, `marker_status` |

상세 진입 때 자동으로 가져오는 가용성 설정은 열기 요청이 아니다. 출입구 핀·정렬의 실패만으로 정상 파노라마를 실패 처리하지 않는다. 서버의 운영용 거리뷰 `STARTED/READY/FAILED/CANCELLED`와 제품 행동 이벤트는 역할이 다르며, 이미 READY였던 거리뷰를 닫는 것도 제품의 `street_view_closed`로 남긴다. 현재 모바일 거리뷰와 파노라마 내부 회전·이동·배율 조작 수집은 제공하지 않는다.

## 서버 결과와 중복 방지

`POST /api/v1/notification-interest-events`는 다음 top-level `200` 결과를 반환한다.

```typescript
{
  eventId: string
  targetType: 'COMPLEX' | 'ANNOUNCEMENT' | 'REGION'
  targetId: string
  outcome: 'ACTIVATED' | 'ALREADY_ACTIVE' | 'NOT_ACTIVATED'
    | 'CANCELLED' | 'UNCHANGED' | 'OBSERVED' | 'UNKNOWN'
  occurredAt: string
}
```

| 결과 | 의미 | PostHog 완료 이벤트 |
| --- | --- | --- |
| `ACTIVATED` | 새 활성 신청 또는 취소·만료 신청 재활성화 | 사전신청 완료 |
| `ALREADY_ACTIVE` | 이미 활성 상태. 기존 갱신 정책은 유지 | 없음 |
| `NOT_ACTIVATED` | 이메일 등의 활성화 조건 미충족 | 없음 |
| `CANCELLED` | 실제 활성 신청 취소 | 취소 완료 |
| `UNCHANGED` | 취소할 활성 신청이 없어 상태 변경 없음 | 없음 |
| `OBSERVED` | 노출·거절 행동 기록, 신청 상태 변경 없음 | 없음 |
| `UNKNOWN` | 과거 기록에 처리 결과가 없어 확정 불가 | 없음 |

처리 결과, 원래 발생 시각과 신청 변경은 같은 트랜잭션에 저장한다. 동일한 eventId로 같은 주체·대상·명령을 재전송하면 최초 결과와 시각을 반환하며 business 동작을 반복하지 않는다. 같은 ID를 다른 요청에 재사용하면 `409`다. 동시 신규 요청에서도 첫 활성화만 `ACTIVATED`가 된다. 기존 알림 이벤트 원장의 90일 보관 정책 범위 안에서 멱등성이 유지되며, 삭제된 오래된 eventId를 장기 재사용하는 계약은 아니다.

프론트엔드는 응답의 eventId·대상·결과·시각을 검증하고 서버 eventId로 완료 중복을 제거한다. 응답을 기다리는 동안 기존 UI가 닫히거나 세션이 초기화되어도 허용 경로와 수집 조건이 유지되면 확정된 완료는 기록하며, 이전 응답으로 새 화면을 덮지는 않는다. `NOT_ACTIVATED`·`UNKNOWN` 등 확인되지 않은 결과는 완료로 표시하지 않고 현재 서버 상태를 재조회한다. 동일 요청의 재시도에는 원래 eventId를 유지하고, 취소 후 새 신청 같은 별도 행동에는 새 ID를 사용한다. 주체가 바뀌어 `409`가 발생해도 새 ID로 자동 재시도하지 않는다. 이전 백엔드의 `204`는 처리 결과를 알 수 없는 호환 응답이므로 완료를 추정하지 않는다. **백엔드를 먼저 배포한 뒤 프론트엔드를 배포**한다. 과거 로그를 `ACTIVATED`로 추정해 소급 전송하지 않는다.

상세·페이지·모달·문서는 각 방문·요청 ID로 StrictMode 재실행과 재렌더를 중복 제거한다. 버튼 시도와 서버 성공은 별개다. 탐색·뷰어에서는 현재 요청과 무관해진 오래된 응답과 취소된 요청을 수집하지 않는다. 알림 신청·취소의 서버 확정 결과는 위 계약에 따라 원래 요청의 회원 상태와 페이지를 유지해 기록한다. 브라우저 중복 방지는 제한된 세션 저장소를 사용하며 서버 원장을 대신하지 않는다.

## 검증과 해석의 한계

아래는 회원 알림 설정·보관함 통합 전 도입 버전의 검증 기록이다. 기존 비회원 이메일 신청 QA는 현재 회원 전용 흐름의 검증 결과를 대신하지 않는다. 자동 검사, 실제 브라우저 수신과 운영 배포는 서로 다른 검증 단계다.

| 구분 | 확인 결과와 남은 범위 |
| --- | --- |
| 프론트엔드 자동 검사 | `npm run check` 통과. 테스트 133개 파일, 1,930개 테스트 통과 |
| 백엔드 자동 검사 | 2,317개 테스트 통과 |
| 개발 프로젝트 실제 수신 | 로컬 브라우저 QA에서 비회원 알림 사전신청 완료 이벤트, 검색·필터 이벤트와 마스킹된 리플레이 수신 확인. 최종 `127.0.0.1:5190` 감사 표본은 32개 이벤트·고유 브라우저 1개 |
| 지도 런타임 | NAVER SDK의 `isArray` 오류가 비교한 기존 `develop`에서도 재현됨. 최종 로컬 QA는 지도 키를 제외한 상태로 진행했으므로 실제 지도 조작을 검증한 결과로 해석하지 않음 |
| 미완료 런타임 검증 | 실제 OAuth 로그인, 거리뷰, PDF·HWP/HWPX 문서의 실제 렌더링과 관련 계측 확인 |
| 운영 프로젝트 | 대시보드는 생성했으며, 실제 운영 수신은 배포 후 확인 필요. 백엔드를 먼저 배포한 뒤 프론트엔드를 배포 |

최종 로컬 브라우저 표본에서 단지 `910002`의 `view_complex`는 필터 패널 재렌더 전후에도 1회만 수집되었다. 검사한 수집 데이터에서 검증용 이메일·검색어·URL 부분 문자열은 0건, 금지 속성도 0건이었다. 저장된 리플레이에서 일반 텍스트 마스킹과 레이아웃을 확인했다. 이 결과는 해당 QA 표본의 확인 범위다.

HogQL 인라인 fixture는 계산 쿼리와 경계 쿼리로 나누어 실행해 다음 결과를 확인했다.

| 분리 실행한 검증 | 확인 결과 |
| --- | --- |
| 2026-10-01 활성 코호트 | 분모 4, 재방문 2, 리텐션 50% |
| 2026-10-02 활성 코호트 | 분모 2, 재방문 1, 리텐션 50% |
| KST 자정 | UTC 2026-09-30 14:59:59는 9월 30일, 15:00:00은 10월 1일로 구분 |
| 관찰 기간 | 10월 1일 코호트는 D+8 직전 `NULL`, D+8 시작 후 50% |
| 분모 0 | `NULL` |

수집 시작 전 기간을 0명으로 단정하지 않는다. 실제 고객의 7일 리텐션은 운영 수집 후 관찰 기간이 지나야 해석할 수 있다. 개발 프로젝트의 합성 QA 데이터로 실제 고객의 활성도나 전환율을 판단하지 않는다.

### 배포와 후속 확인

1. 백엔드를 먼저 배포하고 알림 요청의 `200` 응답과 실제 처리 결과를 확인한다.
2. 환경별 공개 프로젝트 token과 `VITE_ANALYTICS_ENV`를 주입해 프론트엔드 이미지를 빌드·배포한다. 기존 이미지에 런타임 환경 변수만 바꾸지 않는다.
3. 해당 PostHog 프로젝트의 실제 수신, 알림 완료 중복 방지, 팀원 제외와 대시보드 수집 시작일을 확인한다.
4. 지도 오류를 별도로 해결하고 실제 지도 조작·OAuth·거리뷰·문서 렌더링의 남은 런타임 검증을 완료한다.

로컬 검사 통과가 CI 통과·병합·배포 완료를 의미하지 않는다.

새로고침은 통상 같은 브라우저 식별자를 유지하지만 사이트 데이터 삭제, 시크릿 창, 브라우저·기기 변경은 새 사용자로 보일 수 있다. 여러 사람이 같은 브라우저를 공유하면 한 사용자로 묶일 수도 있다. 광고 차단, 네트워크 실패, 페이지 종료는 누락을 만들 수 있으며 익명 브라우저 분석으로 악의적인 트래픽을 완전히 제거하지는 못한다. 이 버전은 서버 outbox 기반의 PostHog 전달 보장을 제공하지 않는다. 실제 신청 유무가 필요한 판단은 서버 DB 원장을 사용한다.

로그인 계정별 기기 통합, 실제 알림 발송·클릭·알림을 통한 재방문, 아직 없는 북마크 등의 이벤트는 이번 구현 범위가 아니다. 해당 기능이 생기면 성공 계약과 지표 정의부터 갱신한다.
