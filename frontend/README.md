# 공공주택 복덕방 프론트엔드

공공주택 지도·목록·공고 탐색과 로그인·관리자 화면을 제공하는 React 애플리케이션이다.

## 디렉터리 구성

| 경로 | 역할 |
| --- | --- |
| [`src/`](src/) | 화면, API 연동, 지도, 공통 디자인 요소와 기능별 테스트 |
| [`public/`](public/) | 로고·인증 이미지, 지역 경계 등 브라우저에 제공할 정적 자산 |
| [`docs/`](docs/) | 개발 기준, 디자인 시스템, 구조와 자산 관리 안내 |
| [`scripts/`](scripts/) | PDF 자산 준비와 지역 경계 데이터 변환·검증 도구 |
| [`nginx/`](nginx/) | HTTP·HTTPS 설정과 화면·API 요청 처리 규칙 |

기능별 코드 위치와 상태 소유자는 [프론트엔드 구조](docs/frontend-structure.md)에서 확인한다.

## 주요 파일

| 파일 | 역할 |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | 프론트엔드 작업 지침과 문서 읽기 순서 |
| [`package.json`](package.json), [`package-lock.json`](package-lock.json), [`.nvmrc`](.nvmrc) | 실행 명령, 의존성과 Node.js·npm 사용 기준 |
| [`index.html`](index.html) | 애플리케이션을 불러오는 HTML 진입점 |
| [`vite.config.ts`](vite.config.ts) | Vite·Vitest와 로컬 공공주택 mock 설정 |
| [`tsconfig.json`](tsconfig.json), [`tsconfig.app.json`](tsconfig.app.json), [`tsconfig.node.json`](tsconfig.node.json) | 애플리케이션·개발 도구의 TypeScript 검사 설정 |
| [`.oxlintrc.json`](.oxlintrc.json) | 코드 린트 규칙 |
| [`.env.example`](.env.example) | 브라우저용 환경 변수 예시 |
| [`Dockerfile`](Dockerfile), [`.dockerignore`](.dockerignore) | 정적 파일 빌드와 Nginx 이미지 구성·빌드 제외 경로 |

## 사전 준비

- Node.js 계약은 [`.nvmrc`](.nvmrc)와 [`package.json`](package.json)의 `engines.node`가 함께 정의한다.
- npm 계약은 `package.json`의 `packageManager`가 정의한다.
- 정확한 설치 의존성 그래프의 원본은 [`package-lock.json`](package-lock.json)이다.

Node.js 계약을 바꿀 때는 두 정의를 함께 갱신한다.

명령어는 `frontend/` 디렉터리에서 실행한다.

`nvm`을 사용한다면 `.nvmrc`에 기록된 Node.js 버전을 적용한다.

```shell
nvm use
```

## 작업 기준

프론트엔드 코드를 변경하기 전에 [AGENTS.md](AGENTS.md)에서 기술 경계와 완료 기준을 확인한다.

새 UI에는 [디자인 시스템](docs/design-system.md)의 토큰과 공통 컴포넌트를 우선 사용한다.

## 의존성 설치

```shell
npm ci
```

`package-lock.json`에 기록된 버전으로 의존성을 설치한다.

## 개발 서버 실행

`frontend/.env.example`을 참고해 Git이 추적하지 않는 `frontend/.env.local`을
생성한다.

```shell
cp .env.example .env.local
```

NAVER Cloud Platform에서 로컬 Web 서비스 URL로 `http://localhost`를
등록하고 브라우저용 Client ID를 입력한다.

```dotenv
VITE_API_BASE_URL=
VITE_NAVER_MAPS_CLIENT_ID=발급받은_Client_ID
```

`VITE_*` 환경 변수는 빌드 결과와 브라우저에 공개된다. Client Secret이나 서버
비밀값을 입력하지 않는다. 로컬에서 API 주소를 생략하면
`http://localhost:8080`을 사용한다. Client ID가 없으면 빌드는 정상적으로
완료되지만 실행 화면에는 지도 사용 불가 안내가 표시된다.

```shell
npm run dev
```

명령어가 출력하는 로컬 주소를 브라우저에서 열어 애플리케이션을 확인한다.

## 로컬 공공주택 mock

로컬에서만 사용하는 `.codex/local-context/public-housing-mock.json`을 준비하고
개발 서버에서 `VITE_PUBLIC_HOUSING_LOCAL_MOCK=true`를 설정하면 해당 snapshot으로
지도·목록·상세·검색을 확인할 수 있다. 지도도 현재 `HousingMapRepository` 계약을
사용하며, 확대 수준과 관계없이 항상 4단계 개별 단지 결과를 반환한다. 좌표 범위와
검색 조건은 snapshot 데이터에 적용한다. 서버의 지역 집계·단계 전환 정책은
재현하지 않으므로 해당 동작은 실제 지도 API를 연결해 확인한다. 통합 검색은
snapshot의 지역명·단지명·공고명에서 일치하는 결과와 유형별 페이지를 제공하며,
백엔드 검색 순위나 전체 데이터 검색을 재현하지 않는다.

## 환경별 지도 설정

모든 환경은 `VITE_NAVER_MAPS_CLIENT_ID`라는 같은 변수명을 사용하고 환경별 빌드
시점에 값을 주입한다.

| 환경 | Client ID와 Web 서비스 URL |
| --- | --- |
| 로컬 | 비운영 Client ID, `http://localhost` |
| 개발 | 비운영 Client ID, 실제 개발 프론트엔드 주소 |
| 운영 | 별도 운영 Client ID, 실제 운영 프론트엔드 주소 |

Vite는 환경 변수 값을 정적 빌드 결과에 포함하므로 개발과 운영은 각각 올바른
값으로 빌드한다. 서버 실행 중 환경 변수만 바꾸거나 런타임 설정 파일을 별도로
사용하지 않는다.

## GA4 측정

공개 탐색 화면(`/`)에서 실제로 제공하는 탐색 행동만 측정한다. 로그인·관리자·
잘못된 경로에서는 수집하지 않는다. 알림 사전신청은 아직 제공하지 않으므로
관련 이벤트와 임시 핵심 지표도 아직 측정하지 않는다.

### 환경 연결

개발·운영은 서로 다른 GA4 속성과 웹 스트림을 사용한다. GA4의 `관리 → 데이터
스트림 → 웹 스트림`에서 해당 환경의 측정 ID를 확인한다.

| 실행 방식 | 설정 위치와 조건 |
| --- | --- |
| 로컬 개발 서버 | `frontend/.env.local`에 개발 속성의 ID와 `VITE_GA_DEBUG_MODE=true`를 함께 설정 |
| 개발·운영 Docker 빌드 | 각 서버의 루트 `.env`에 해당 환경의 `VITE_GA_MEASUREMENT_ID` 설정 |

측정 ID는 공개 설정이며 소스에 직접 넣지 않는다. 루트 `.env`의 값은 Compose
빌드 인자와 Dockerfile을 거쳐 Vite 빌드 결과에 포함된다. ID를 바꾸면 프론트엔드
이미지를 다시 빌드하고 배포해야 한다. 컨테이너 런타임 환경값만 변경해선 반영되지
않는다. 비어 있거나 형식이 잘못된 ID는 GA4를 로딩하지 않는다.

로컬 검증 예시에서 자리표시자를 개발 스트림 ID로 바꾼다.

```dotenv
VITE_GA_MEASUREMENT_ID=개발_스트림의_G로_시작하는_측정_ID
VITE_GA_DEBUG_MODE=true
```

`VITE_GA_DEBUG_MODE`는 로컬 검증용이며 Compose 빌드 인자로 전달하지 않는다.
`npm run dev`는 위 두 값이 모두 있어야 수집한다. `npm run preview`는 이미 만든
프로덕션 빌드를 실행하므로 빌드 당시 ID가 있으면 수집할 수 있다. 로컬 확인에
운영 ID를 사용하지 않는다.

### 이벤트 계약

| 이벤트 | 발생 조건 | 매개변수 |
| --- | --- | --- |
| `page_view` | 공개 탐색 화면에 진입 | 정제한 페이지 주소·제목 |
| `view_complex` | 단지 상세 조회에 성공하고 화면에 표시 | `complex_id`, `entry_point` |
| `view_announcement` | 공고 상세 조회에 성공하고 화면에 표시 | `announcement_id`, `entry_point` |
| `select_search_result` | 검색 결과를 선택 | `result_type`, 단지·공고인 경우 해당 공개 ID |
| `apply_filter` | 사용자가 이전과 다른 필터 조건을 적용 | `filter_target`, `filter_types`, `filter_count` |

- 상세·필터·지도 상태 변경은 페이지뷰로 세지 않는다. 상세의 재렌더링도 열람을
  늘리지 않는다. 닫았다 다시 열거나 다른 상세를 거쳐 돌아오면 새 열람이다.
- `entry_point`는 `map`, `list`, `search`, `recent`, `detail`, `direct`,
  `history` 중 하나다. 뒤로·앞으로 이동은 모두 `history`다.
- `result_type`은 `complex`, `announcement`, `region`, `filter_target`은
  `complex`, `announcement` 중 하나다. 지역 검색에는 단지·공고 ID를 넣지 않는다.
- `filter_types`는 적용 중인 필터 종류를 `region,rental,status,agency,recruitment,deposit,rent,area,built_year`
  순서로 쉼표 연결한 값이며, 없으면 `none`이다. `filter_count`는 선택값의 개수가
  아니라 적용한 종류의 개수다. 초기 URL 복원·뒤로가기는 필터 적용 이벤트가 아니다.
- 검색어 원문, 필터 입력값, 정밀 좌표, 이메일·전화번호, 자격·인증 정보와 사용자
  계정 ID는 전송하지 않는다. 페이지 주소는 현재 origin의 `/`로 정제하고,
  외부 referrer는 origin까지만 유지한다. UTM 캠페인별 분석은 포함하지 않는다.
- SDK 로딩 실패나 수집 제외 상태는 서비스 탐색을 막지 않는다. 자동 페이지뷰는
  `send_page_view: false`로 끄고 수동 페이지뷰만 보낸다.

### 팀원 데이터 제외

각 환경의 `/login`에 먼저 접속한 뒤 개발자 도구 콘솔에서 다음을 실행하고,
페이지를 새로고침한 후 탐색 화면으로 이동한다.

```javascript
localStorage.setItem('toadzip.analytics.disabled', 'true');
location.reload();
```

제외값은 SDK 로딩 전에 확인하고, 이미 열려 있는 다른 탭의 변경도 반영한다.
저장소를 읽을 수 없으면 수집하지 않는다. 기존 GA 수집 거부값도 보존한다.
이 설정은 브라우저·origin별이므로 dev/prod, HTTP/HTTPS, 다른 브라우저와 시크릿
창에 각각 적용해야 한다. 이미 수집된 과거 데이터는 삭제되지 않는다.

개발 DebugView 검증을 위해 제외를 해제할 때는 개발 환경의 `/login`에서 다음을
실행한다. 다른 방식으로 설정한 기존 GA 수집 거부까지 해제하는 명령은 아니다.

```javascript
localStorage.removeItem('toadzip.analytics.disabled');
location.reload();
```

### 지표 정의

날짜는 GA4 속성의 `Asia/Seoul` 기준이다. 고유 사용자는 초기에는 GA4의 브라우저
식별 기준이며 같은 사람의 여러 기기·브라우저를 합치지 않는다.

| 지표 | 정의 |
| --- | --- |
| 방문 사용자 | `page_view`를 발생시킨 고유 사용자 |
| 제품 활성 사용자 | 기간 내 `view_complex` 또는 `view_announcement`를 1회 이상 발생시킨 고유 사용자 |
| DAU·WAU·MAU | 각각 당일·당일 포함 최근 7일·최근 30일의 제품 활성 사용자 |
| 7일 내 리텐션 | 기준일 D의 제품 활성 사용자 중 D+1일부터 D+7일까지 다시 활성 행동을 한 고유 사용자 비율 |

제품 활성 사용자에는 GA4 기본 `활성 사용자` 대신 상세 이벤트 조건을 적용한
`총 사용자`를 사용한다. 단지와 공고를 모두 본 사용자도 한 명이다. 일별
사용자 수를 더해서 WAU·MAU를 만들지 않고 각 기간 전체에서 중복을 제거한다.

리텐션은 신규·기존 사용자를 모두 포함하며 최초 방문일 기준이 아니다. 당일
재조회만 한 사용자는 재방문자로 세지 않고, D+1~D+7에 여러 번 돌아와도 한 명으로
센다. 시간차 168시간이 아닌 한국 시간의 날짜 범위다. 7일 관찰과 데이터 처리가
끝나지 않은 기준일은 0%로 해석하지 않고 집계 대기로 남긴다.

장기 핵심 지표는 실제 단지 알림 신청 사용자다. 향후 임시 핵심 지표는 기능 준비
중이라는 안내를 확인한 뒤 **특정 단지의 알림 사전신청을 완료한 외부 고유 사용자**로
정의한다. 기능이 구현되면 클릭과 완료를 분리하고 서버 저장 성공 후에만 완료를
기록한다. 상세 조회·버튼 클릭·일반 기능 출시 소식 신청으로 완료를 대신하지 않는다.

### GA4 콘솔과 보고서 구성

2026-09-30에 dev/prod 속성의 향상된 측정 끄기와 아래 맞춤 측정기준 6개·측정항목
1개 등록을 확인했다. 이 설정 완료는 코드의 dev/prod 배포나 실제 수집 확인을
의미하지 않는다. 보고서 데이터는 배포·수집과 처리 이후 별도로 확인한다.

dev/prod 각각 웹 스트림의 **향상된 측정 전체를 끈다**. 특히 브라우저 방문 기록
변경에 따른 페이지뷰를 꺼야 수동 페이지뷰와 중복되지 않는다. 자동 수집되는
`first_visit`, `session_start`, `user_engagement`는 향상된 측정과 별개이며 실제
브라우저에서 수집 여부를 확인한다. 기존 데이터 보존 기간은 유지한다.

`관리 → 맞춤 정의`에서 다음 이벤트 범위 정의를 등록한다. 매개변수 이름은 코드와
일치시킨다. 정의 등록과 보고서 처리가 끝나기 전에는 값이 바로 보이지 않을 수 있다.

| 유형 | 매개변수 |
| --- | --- |
| 맞춤 측정기준 | `complex_id`, `announcement_id`, `entry_point`, `result_type`, `filter_target`, `filter_types` |
| 맞춤 측정항목 | `filter_count` — 일반 단위 |

`탐색 → 자유 형식`에 아래 세 보고서를 만든다. 이벤트 이름·날짜·맞춤 측정기준과
`총 사용자`, `이벤트 수`를 가져와 사용한다.

개발·운영 속성에 아래 보고서 3종을 각각 생성하고 각 속성의 기존 사용자에게
읽기 전용으로 공유했다. 운영 복사본의 탐색 행동 4개 탭에 맞춤 측정기준을 연결하고,
방문 보고서 2개 탭의 필터·리텐션의 날짜 범위와 두 사용자 세그먼트를 확인했다.

| 환경 | 방문·제품 활성 | 탐색 행동 | 7일 내 리텐션 |
| --- | --- | --- | --- |
| 개발 | [보고서](https://analytics.google.com/analytics/web/#/analysis/a409144705p555511895/edit/LXIKVOlJQJKmLpVot62fEA) | [보고서](https://analytics.google.com/analytics/web/#/analysis/a409144705p555511895/edit/ijdV04pOTMaJ2Vl0u9bDoA) | [보고서](https://analytics.google.com/analytics/web/#/analysis/a409144705p555511895/edit/k-ZfdPNIS7Kt0tUGYELJLw) |
| 운영 | [보고서](https://analytics.google.com/analytics/web/#/analysis/p556177290/edit/EcOjsHueSGaGASEtA1iLzA) | [보고서](https://analytics.google.com/analytics/web/#/analysis/p556177290/edit/MtZYs7OAR3OuQj3UFO6yXg) | [보고서](https://analytics.google.com/analytics/web/#/analysis/p556177290/edit/nCtjjXL_TVCnbEjXVyqjvA) |

1. **방문·제품 활성:** `page_view`와 상세 이벤트 두 종류를 각각 필터로 구분한다.
   상세 두 종류는 OR 조건으로 묶는다. 당일·최근 7일·최근 30일 기간별로 총 사용자
   수를 확인한다. 합계는 날짜별 행의 합이 아닌 해당 기간의 고유 사용자 수다.
   GA4의 `지난 30일` 같은 사전 설정은 당일을 제외하므로, 당일 포함 지표는 맞춤
   날짜 범위의 종료일을 당일로 지정한다.
2. **탐색 행동:** 이벤트별 탭을 나눠 상세 ID·진입 경로, 검색 결과 종류,
   필터 대상·종류별 이벤트 수와 총 사용자 수를 확인한다. 이벤트 수는 반복 행동을
   포함하고 사용자 수는 중복을 제거한다.
3. **7일 내 리텐션:** 조회 기간을 D~D+7로 설정하고 아래 사용자 세그먼트 두 개를
   비교한다. 두 세그먼트의 총 사용자 수로 `재탐색 사용자 ÷ 기준일 활성 사용자`를
   계산한다. 보고서가 이 비율을 자동 계산하지는 않는다. 분모가 0이면 산출 불가다.

리텐션 세그먼트는 다음처럼 구성한다. 각 그룹의 범위는 **동일 이벤트 내**,
사용자 포함 조건은 **어느 시점에든 충족**으로 두어 같은 날·같은 세션의 동시
충족을 요구하지 않는다.

| 사용자 세그먼트 | 조건 그룹 |
| --- | --- |
| 기준일 활성 사용자 | 이벤트 이름이 `view_complex` 또는 `view_announcement` **AND** 날짜가 D |
| 재탐색 사용자 | 위 그룹 **AND** 별도 그룹: 상세 이벤트 두 종류 중 하나 **AND** 날짜가 D+1~D+7 |

날짜 조건과 이벤트 조건은 각 그룹에서 함께 평가한다. 상세 이벤트 없이 방문만
한 날을 활성 날짜로 잘못 포함하지 않도록 한다. 기준일을 바꿀 때 보고서 기간과
두 세그먼트의 날짜 조건도 함께 변경한다. 일별 재방문자를 합산하지 않는다.

저장한 리텐션 템플릿의 기준일 D는 **2026-09-22**, 재방문 관찰일은 09-23~09-29,
보고서 범위는 09-22~09-29다. 배포 전 템플릿이므로 실제 코호트로 해석하지 않는다.
데이터가 쌓이면 두 세그먼트와 보고서 날짜를 함께 바꿔 사용한다.

### 수집 확인

2026-09-30 로컬 개발 서버(`127.0.0.1:5185`)와 공개 주택 mock에서 실제 브라우저로
다음 범위를 확인했다. dev/prod에 배포한 서비스의 검증 결과는 아니다.

- SDK 전송에서 계약의 이벤트 5개를 확인했다. 단지 진입은 검색·최근 조회,
  공고 진입은 연결 상세·검색, 필터는 임대유형 1개 적용으로 확인했다.
- dev DebugView에서 계약의 이벤트 5개와 기본 `session_start` 수신을 확인했다.
  기존 브라우저의 백그라운드 검증 세션에서는 `first_visit`, `user_engagement`
  수신을 확인하지 못했다.
- 상세 주택형 탭 변경은 추가 열람을 만들지 않았다. 팀원 제외 후 새로고침하면
  GA 스크립트와 추가 전송이 없었고, 로그인 경로에서는 제외를 해제하고 새로고침해도
  스크립트를 로딩하지 않았다. 공개 화면 복귀 시 페이지뷰 수집을 확인했다.
- 지도 SDK 키가 없는 환경이어서 지도 마커 진입의 실제 브라우저 동작은 확인하지
  못했다. 단위 테스트와 별도로 실제 지도 환경에서 확인해야 한다.

후속 변경·배포 시에는 아래를 확인한다.

- 코드 검사와 테스트 후 개발 ID로 실제 브라우저를 열어 Network의 GA 요청과
  dev 속성 `관리 → DebugView`를 함께 확인한다. 테스트·빌드 통과만으로 실제
  수집이나 배포가 검증된 것으로 기록하지 않는다.
- 첫 진입 페이지뷰 한 번, 직접 상세 접속, 지도·목록·검색·최근 조회·상세 간 이동,
  뒤로·앞으로, 닫고 재열기를 확인한다. 재렌더링과 상세 로딩 실패는 열람을 늘리지
  않아야 한다. 의미가 같은 필터의 재적용은 필터 이벤트를 늘리지 않아야 한다.
- 실제 전송의 측정 ID·이벤트 이름·허용 매개변수를 확인한다. query·fragment,
  정밀 좌표·검색 원문·연락처가 전송되지 않는지 확인한다.
- ID 미설정, 팀원 제외, 로그인·관리자 경로, SDK 지연·실패 상태를 확인한다.
  검증이 끝나면 팀원 제외를 다시 적용한다.
- 배포 후 일반 수집 보고서 반영과 리텐션 관찰 기간을 별도로 확인한다. 코드 구현,
  로컬 검증, GA4 설정, dev/prod 배포·수집 확인은 각각 다른 완료 상태다.

설정 근거: [수동 페이지뷰](https://developers.google.com/analytics/devguides/collection/ga4/views),
[사용자 세그먼트](https://support.google.com/analytics/answer/9304353?hl=en),
[탐색의 기본 측정기준](https://developers.google.com/analytics/devguides/reporting/data/v1/exploration-api-schema).

## 코드 검사

```shell
npm run lint
```

## 단위 테스트

```shell
npm run test
```

Vitest와 React Testing Library로 테스트를 한 번 실행한다.

## 전체 검사

```shell
npm run check
```

코드 검사, 단위 테스트, TypeScript 검사와 프로덕션 빌드를 차례로 실행한다.

## 프로덕션 빌드

```shell
npm run build
```

TypeScript 타입 검사 후 프로덕션 파일을 `dist/`에 생성한다.

프로덕션에서 `VITE_API_BASE_URL`을 생략하면 브라우저는 프론트엔드와 같은 주소의
`/api`로 요청한다. 별도 API 주소가 필요한 환경에서만 프로토콜과 호스트를 끝
슬래시 없이 설정한다.

```text
VITE_API_BASE_URL=https://api.toadzip.com
```

API 경로가 이미 `/api`로 시작하므로 `VITE_API_BASE_URL=/api`로 설정하지 않는다.
별도 주소를 사용하면 백엔드 CORS와 세션 쿠키 정책도 함께 설정해야 한다.

## 빌드 결과 확인

```shell
npm run preview
```

이 명령은 빌드 결과를 로컬에서 확인하기 위한 용도이며 운영 서버로 사용하지 않는다.

## Docker와 Nginx로 실행

아래 명령은 프로젝트 루트에서 실행한다. 프론트엔드 이미지만 확인하려면 실제
서버 환경변수 없이 빌드할 수 있다.

```shell
docker build -t toadzip-frontend:local frontend
```

프론트엔드와 백엔드는 루트 [`compose.yaml`](../compose.yaml)에서 함께 실행한다.
루트 [`.env.example`](../.env.example)을 참고해 DB·모니터링 등 필수값을 준비하고,
지도를 표시하려면 루트 `.env`의 `VITE_NAVER_MAPS_CLIENT_ID`에 환경에 맞는 공개
Client ID를 설정한다. 실행·종료 명령과 필요한 Compose 파일은 환경별 안내를 따른다.

| 환경 | 접속 주소와 실행 안내 |
| --- | --- |
| 로컬 | HTTP, [로컬 환경 설정](../docs/LOCAL_SETUP.md) |
| 개발 EC2 | `https://dev.bokduckbang.com`, [개발 서버 설정](../docs/DEV_SERVER_SETUP.md) |
| 운영 EC2 | `https://bokduckbang.com`, [운영 서버 설정](../docs/PROD_SERVER_SETUP.md) |

개발과 운영은 서로 다른 EC2에서 실행하며 각각 인증서를 발급받아 HTTPS를 적용한다.
로컬 기본 접속 주소는 `http://localhost`이고 상태 확인 주소는
`http://localhost/healthz`다. 로컬 호스트 포트를 바꾸려면 루트 `.env`에
`FRONTEND_PORT=8088`처럼 지정한다. Docker 빌드는 API 주소를 별도로 넣지 않고
같은 주소의 `/api`를 사용한다.

Nginx는 `/api`와 `/api/*`를 같은 Compose 네트워크의 `backend:8080`으로 전달한다.
`/`, `/admin/login`과 그 밖의 화면 주소는 React 애플리케이션으로 연결한다.
HTTP·HTTPS 설정은 [`nginx/`](nginx/)에 모으고 화면·API 처리 규칙은 공유한다.

EC2 HTTPS와 인증서 갱신은 [Certbot 적용 안내](../infra/certbot/README.md)를 따른다.
HTTPS용 Compose 설정을 선택한 서버에서는 해당 도메인의 HTTPS 주소로 접속한다.
일반 로컬 개발에는 인증서나 HTTPS용 서버 환경변수가 필요하지 않다.

`VITE_NAVER_MAPS_CLIENT_ID`는 브라우저 공개값이며 정적 빌드 결과에 포함된다.
실행 중인 컨테이너의 환경값만 바꿔서는 화면이 바뀌지 않으므로 환경별 Client ID로
각각 다시 빌드한다. Client Secret은 `.env.local`, 빌드 인자와 이미지 어디에도
넣지 않는다.
