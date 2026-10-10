# 사용자 소셜 로그인

카카오·구글 인증 후 애플리케이션 세션으로 로그인한다. 인가 코드·공급자 토큰을 프론트엔드에 전달하지 않는다.

## API

| 요청 | 결과 |
|---|---|
| GET `/api/auth/oauth2/authorization/{provider}` | `google`·`kakao` 인증 시작, 콜백 후 설정된 성공·실패 URL로 이동 |
| GET `/api/auth/me` | `{id, email}`. 이메일 미제공은 null, 비로그인 401·관리자만 로그인하면 403 |
| GET `/api/auth/csrf` | CSRF 쿠키와 `{token, headerName}` |
| POST `/api/auth/logout` | 세션 쿠키·CSRF 필요, 성공 204 |

브라우저 요청에는 세션 쿠키를 포함한다. 관리자와 사용자 권한은 분리된다.

## 공급자와 환경 설정

카카오 REST API 키·Client Secret과 구글 웹 애플리케이션 Client ID·Secret을 준비한다.
두 공급자를 모두 설정해야 한다. `USER_OAUTH_ENABLED=true`에서 하나라도 비면 백엔드 기동에 실패한다.
설정 전에는 false로 두고 나머지 서비스를 실행한다.

공급자 콘솔에 콜백을 등록한다. 오리진은 스킴·호스트·포트를 포함하며 후행 `/` 없이 설정한다.

| 공급자 | 등록할 콜백 |
|---|---|
| Google | `<공개_오리진>/api/auth/oauth2/callback/google` |
| Kakao | `<공개_오리진>/api/auth/oauth2/callback/kakao` |

콘솔 설정은 [카카오 안내](https://developers.kakao.com/docs/ko/kakaologin/prerequisite),
[구글 안내](https://developers.google.com/identity/protocols/oauth2/web-server)를 따른다.
로컬 Compose 예시는 다음과 같다. 비밀값은 Git에서 제외된 루트 `.env`에 넣는다.

```dotenv
USER_OAUTH_ENABLED=true
GOOGLE_CLIENT_ID=구글_Client_ID
GOOGLE_CLIENT_SECRET=구글_Client_secret
KAKAO_CLIENT_ID=카카오_REST_API_키
KAKAO_CLIENT_SECRET=카카오_Client_Secret
USER_OAUTH_REDIRECT_BASE_URL=http://localhost
USER_OAUTH_SUCCESS_URL=http://localhost/
USER_OAUTH_FAILURE_URL=http://localhost/?login=failed
```

[로컬 실행](../../docs/LOCAL_SETUP.md)으로 다시 빌드하고 두 공급자의 로그인·실패·로그아웃을 확인한다.
운영은 HTTPS 서비스 오리진으로 콜백·이동 URL을 맞춘다. 성공 경로는 `/`, 실패는 `/?login=failed`다.
앱 직접 실행 기본값은 `http://localhost:5173`, Compose는 `http://localhost`를 사용한다. 이전 `/login`도 메인 화면으로 연결된다.

## 계정 보존

식별자는 `google:{sub}`·`kakao:{id}`다. 기존 사용자 ID와 연관 데이터를 유지한다.
이메일은 공급자가 제공할 때 저장하며 같은 이메일의 서로 다른 공급자 계정을 자동 연결하지 않는다.
스키마·기존 로그인 제약은 [Flyway 적용](flyway-adoption.md)을 따른다. 중복 식별자는 계정 소유권을 확인한 뒤 처리한다.
