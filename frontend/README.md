# 두꺼비집 프론트엔드

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
VITE_API_BASE_URL=http://localhost:8080
VITE_NAVER_MAPS_CLIENT_ID=발급받은_Client_ID
```

`VITE_*` 환경 변수는 빌드 결과와 브라우저에 공개된다. Client Secret이나 서버
비밀값을 입력하지 않는다. 로컬에서 API 주소를 생략하면
`http://localhost:8080`을 사용하고, 운영 빌드에서는 API 주소가 없으면 빌드가
실패한다. Client ID가 없으면 빌드는 정상적으로 완료되지만 실행 화면에는 지도
사용 불가 안내가 표시된다.

```shell
npm run dev
```

명령어가 출력하는 로컬 주소를 브라우저에서 열어 애플리케이션을 확인한다.

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

운영 프런트엔드와 API를 서로 다른 주소에 배포하므로, 운영 빌드 환경에는 공개 API 주소를 반드시 설정한다.

```text
VITE_API_BASE_URL=https://api.toadzip.com
```

값이 없으면 빌드는 실패한다. 운영 백엔드에는 `ADMIN_CORS_ALLOWED_ORIGIN=https://toadzip.com`와
`SESSION_COOKIE_SECURE=true`를 설정한다.

## 빌드 결과 확인

```shell
npm run preview
```

이 명령은 빌드 결과를 로컬에서 확인하기 위한 용도이며 운영 서버로 사용하지 않는다.
