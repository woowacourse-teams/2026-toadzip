# 공공주택 복덕방 프론트엔드

공공주택 지도·목록·공고 탐색과 로그인·관리자 화면을 제공하는 React 애플리케이션이다.

## 문서 안내

| 문서 | 다루는 내용 |
|---|---|
| [환경 설정](../docs/setup/README.md) | 컨테이너로 로컬·개발·운영 환경을 준비하는 방법 |
| [프론트 개발 기준](../docs/development/frontend/README.md) | 구조·상태·외부 경계·디자인·검증 기준 |
| [기능 명세](../docs/features/README.md) | 지도·공고·알림·관리자와 문서 뷰어의 현재 동작 |
| [분석 운영](../docs/operations/analytics/README.md) | GA4·PostHog 환경 연결·측정·수신 확인 |
| [HTTPS 운영](../docs/operations/https.md) | EC2 인증서 발급·갱신과 Nginx 반영 |
| [AI 작업 지침](AGENTS.md) | 프론트 변경 시 필수 문서·제약과 완료 판단 |

기능별 코드 위치와 상태 소유자는 [프론트 구조](../docs/development/frontend/architecture.md)에서 확인한다. 아래 명령은 프론트엔드 단독 실행·검증용이다.

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

새 UI에는 [디자인 시스템](../docs/development/frontend/design-system.md)의 토큰과 공통 컴포넌트를 우선 사용한다.

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

## PostHog 환경 설정

PostHog 변수 4개는 루트 [`.env.example`](../.env.example)을 참고해 저장소 루트
`.env`에서 관리한다. Vite는 이 네 변수만 루트에서 읽으며 다른 프론트 변수는
기존 `frontend/.env.local`을 사용한다. 자세한 연결·누락 동작은
[PostHog 제품 분석](../docs/operations/analytics/posthog.md#환경-연결과-사용자-식별)을 따른다.
환경값 변경 후에는 개발 서버를 다시 시작하거나 프론트엔드를 다시 빌드한다.

## 로컬 공공주택 mock

로컬에서만 사용하는 `.codex/local-context/public-housing-mock.json`을 준비하고
개발 서버에서 `VITE_PUBLIC_HOUSING_LOCAL_MOCK=true`를 설정하면 해당 snapshot으로
지도·목록·상세·검색을 확인할 수 있다. 지도도 현재 `HousingMapRepository` 계약을
사용하며, 확대 수준과 관계없이 항상 4단계 개별 단지 결과를 반환한다. 좌표 범위와
검색 조건은 snapshot 데이터에 적용한다. 서버의 지역 집계·단계 전환 정책은
재현하지 않으므로 해당 동작은 실제 지도 API를 연결해 확인한다. 통합 검색은
snapshot의 지역명·단지명·공고명에서 일치하는 결과와 유형별 페이지를 제공하며,
백엔드 검색 순위나 전체 데이터 검색을 재현하지 않는다. 외부 읍·면·동과 지하철역 검색은
snapshot에 포함되지 않는다.

## 지역·지도 기능

검색·위치 권한·환경별 지도 설정은 [공개 지도와 검색](../docs/features/search/map.md)을 따른다.

## GA4 측정

설정·이벤트 계약·지표·팀원 제외와 실제 수집 검증은 [GA4 운영 안내](../docs/operations/analytics/ga4.md)를 따른다.

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
| 로컬 | HTTP, [로컬 환경 설정](../docs/setup/local.md) |
| 개발 EC2 | `https://dev.bokduckbang.com`, [개발 서버 설정](../docs/setup/servers.md) |
| 운영 EC2 | `https://bokduckbang.com`, [운영 서버 설정](../docs/setup/servers.md) |

개발과 운영은 서로 다른 EC2에서 실행하며 각각 인증서를 발급받아 HTTPS를 적용한다.
로컬 기본 접속 주소는 `http://localhost`이고 상태 확인 주소는
`http://localhost/healthz`다. 로컬 호스트 포트를 바꾸려면 루트 `.env`에
`FRONTEND_PORT=8088`처럼 지정한다. Docker 빌드는 API 주소를 별도로 넣지 않고
같은 주소의 `/api`를 사용한다.

Nginx는 `/api`와 `/api/*`를 같은 Compose 네트워크의 `backend:8080`으로 전달한다.
`/`, `/admin/login`과 그 밖의 화면 주소는 React 애플리케이션으로 연결한다.
HTTP·HTTPS 설정은 [`nginx/`](nginx/)에 모으고 화면·API 처리 규칙은 공유한다.

EC2 HTTPS와 인증서 갱신은 [Certbot 적용 안내](../docs/operations/https.md)를 따른다.
HTTPS용 Compose 설정을 선택한 서버에서는 해당 도메인의 HTTPS 주소로 접속한다.
일반 로컬 개발에는 인증서나 HTTPS용 서버 환경변수가 필요하지 않다.

`VITE_NAVER_MAPS_CLIENT_ID`는 브라우저 공개값이며 정적 빌드 결과에 포함된다.
실행 중인 컨테이너의 환경값만 바꿔서는 화면이 바뀌지 않으므로 환경별 Client ID로
각각 다시 빌드한다. Client Secret은 `.env.local`, 빌드 인자와 이미지 어디에도
넣지 않는다.

## 알림함과 웹 푸시

동작·공개 Firebase 설정·worker 빌드와 브라우저 검증은 [알림함·웹 푸시 안내](../docs/features/notifications/ui.md)를 따른다.
