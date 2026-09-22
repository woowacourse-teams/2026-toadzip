# 사용자 소셜 로그인

## API

브라우저를 `GET /api/auth/oauth2/authorization/kakao` 또는
`GET /api/auth/oauth2/authorization/google`로 이동시킨다. 공급자 인증 후
`/api/auth/oauth2/callback/{provider}`로 돌아오며 성공 시 설정한 프론트엔드 URL,
실패 시 실패 URL로 이동한다. 쿼리의 인가 코드나 공급자 토큰을 프론트엔드에 전달하지 않는다.

- `GET /api/auth/me`: 로그인한 사용자에게 `{ "id": 123 }`, 비로그인 401, 관리자만 로그인한 경우 403.
- `GET /api/auth/csrf`: `{ "token": "...", "headerName": "X-XSRF-TOKEN" }`와 CSRF 쿠키.
- `POST /api/auth/logout`: CSRF 헤더와 세션 쿠키가 필요하며 성공 시 204.
- 브라우저 요청에 세션 쿠키를 포함한다. 관리자 권한과 사용자 권한은 분리된다.

## 설정과 배포

`USER_OAUTH_ENABLED=true`로 켜고 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `USER_OAUTH_REDIRECT_BASE_URL`을 설정한다.
마지막 값은 후행 `/`가 없는 공개 백엔드 오리진이다. 등록할 콜백 주소는 이 값에
`/api/auth/oauth2/callback/google`과 `/api/auth/oauth2/callback/kakao`를 붙인 것이다.
`USER_OAUTH_SUCCESS_URL`, `USER_OAUTH_FAILURE_URL`은 서버가 정한 프론트엔드 이동 주소이며
기본값은 각각 `http://localhost:5173/login`, `http://localhost:5173/login?login=failed`다.
운영에서는 서비스 오리진으로 명시한다. 카카오 개발자 콘솔에서 로그인과 Redirect URI를 등록하고,
구글 OAuth 클라이언트에도 해당 Redirect URI를 등록한다. 비밀 값은 저장소에 넣지 않는다.

운영 배포 전에 `src/main/resources/db/migration/V20260922_01__unique_user_login_identifier.sql`을
기존 스키마에 적용한다. 이 저장소는 해당 SQL을 자동 실행하지 않는다. 이미 중복된
`login_identifier`가 있다면 마이그레이션이 실패하므로 계정 소유권을 확인하고 처리한 뒤 재시도한다.
새 로그인은 `google:{sub}` 또는 `kakao:{id}`로 저장하며 기존 사용자 ID와 연관 데이터는 유지한다.
서로 다른 공급자 계정은 동일 이메일이어도 자동 연결하지 않는다.
