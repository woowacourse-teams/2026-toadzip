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

`frontend/.env.example`을 참고해 `frontend/.env`를 생성한다.

```shell
Copy-Item .env.example .env
```

로컬 기본값은 `http://localhost:8080`이다.

```shell
npm run dev
```

명령어가 출력하는 로컬 주소를 브라우저에서 열어 애플리케이션을 확인한다.

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
