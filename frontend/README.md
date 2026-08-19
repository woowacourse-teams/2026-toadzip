# 두꺼비집 프론트엔드

## 사전 준비

- Node.js `24.19.0`
- npm `11.17.0`

명령어는 `frontend/` 디렉터리에서 실행한다.

## 의존성 설치

```shell
npm ci
```

`package-lock.json`에 기록된 버전으로 의존성을 설치한다.

## 개발 서버 실행

```shell
npm run dev
```

명령어가 출력하는 로컬 주소를 브라우저에서 열어 애플리케이션을 확인한다.

## 코드 검사

```shell
npm run lint
```

## 프로덕션 빌드

```shell
npm run build
```

TypeScript 타입 검사 후 프로덕션 파일을 `dist/`에 생성한다.

## 빌드 결과 확인

```shell
npm run preview
```

이 명령은 빌드 결과를 로컬에서 확인하기 위한 용도이며 운영 서버로 사용하지 않는다.
