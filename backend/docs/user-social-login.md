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

## 로컬에서 실제 로그인 연결하기

두 공급자의 설정을 모두 마친 뒤 OAuth를 활성화한다. 현재 백엔드는 활성화 시
카카오와 구글 설정을 함께 검증하므로 하나라도 비어 있으면 의도적으로 시작에 실패한다.

### 1. 카카오 애플리케이션 준비

1. [카카오디벨로퍼스](https://developers.kakao.com/)에서 애플리케이션을 만든다.
2. **앱 > 플랫폼 키 > REST API 키**의 키를 복사한다. 이 값이 **KAKAO_CLIENT_ID**다.
3. 같은 REST API 키 설정에서 클라이언트 시크릿을 발급하고 활성화한다. 이 값이
   **KAKAO_CLIENT_SECRET**이다.
4. **카카오 로그인 > 사용 설정**을 켠다.
5. REST API 키의 Redirect URI에 아래 주소를 정확히 등록한다.

~~~text
http://localhost/api/auth/oauth2/callback/kakao
~~~

카카오 공식 설정 절차는
[카카오 로그인 설정하기](https://developers.kakao.com/docs/ko/kakaologin/prerequisite)를 참고한다.

### 2. 구글 OAuth 클라이언트 준비

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만들거나 선택한다.
2. Google Auth Platform에서 앱 이름과 사용자 지원 이메일 등 동의 화면 정보를 설정한다.
3. 테스트 상태라면 로그인에 사용할 구글 계정을 테스트 사용자로 추가한다.
4. **Clients > Create Client**에서 애플리케이션 유형을 **Web application**으로 선택한다.
5. Authorized redirect URIs에 아래 주소를 정확히 등록한다.

~~~text
http://localhost/api/auth/oauth2/callback/google
~~~

6. 생성된 Client ID와 Client secret을 복사한다. 각각 **GOOGLE_CLIENT_ID**,
   **GOOGLE_CLIENT_SECRET**이다.

구글은 스킴, 호스트, 포트와 경로가 모두 일치하는 Redirect URI만 허용한다. 로컬호스트는
HTTP 등록이 허용된다. 자세한 내용은
[Google 웹 서버 OAuth 안내](https://developers.google.com/identity/protocols/oauth2/web-server)를 참고한다.

### 3. 로컬 환경 변수 입력

루트 **.env**에 발급받은 값을 넣는다. 이 파일은 Git에 커밋하지 않는다.

~~~dotenv
USER_OAUTH_ENABLED=true
GOOGLE_CLIENT_ID=구글_Client_ID
GOOGLE_CLIENT_SECRET=구글_Client_secret
KAKAO_CLIENT_ID=카카오_REST_API_키
KAKAO_CLIENT_SECRET=카카오_Client_Secret
USER_OAUTH_REDIRECT_BASE_URL=http://localhost
USER_OAUTH_SUCCESS_URL=http://localhost/login
USER_OAUTH_FAILURE_URL=http://localhost/login?login=failed
~~~

전체 프로젝트를 다시 빌드해 실행한다.

~~~shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml \
  up --detach --build --wait
~~~

브라우저에서 **http://localhost/login**을 열어 카카오 또는 구글 버튼을 누른다.
로그인 후 같은 화면에 **로그인되었습니다**가 표시되면 연결이 완료된 것이다.

설정이 덜 끝난 동안에는 **USER_OAUTH_ENABLED=false**로 두면 나머지 서비스는 정상 실행된다.

## 환경별 설정과 배포

`USER_OAUTH_ENABLED=true`로 켜고 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `USER_OAUTH_REDIRECT_BASE_URL`을 설정한다.
마지막 값은 후행 `/`가 없는 브라우저 공개 서비스 오리진이다. 등록할 콜백 주소는 이 값에
`/api/auth/oauth2/callback/google`과 `/api/auth/oauth2/callback/kakao`를 붙인 것이다.
`USER_OAUTH_SUCCESS_URL`, `USER_OAUTH_FAILURE_URL`은 서버가 정한 프론트엔드 이동 주소이며
애플리케이션 직접 실행 기본값은 각각 `http://localhost:5173/login`,
`http://localhost:5173/login?login=failed`이며 Compose는 `http://localhost/login` 경로를 사용한다.
운영에서는 서비스 오리진으로 명시한다. 카카오 개발자 콘솔에서 로그인과 Redirect URI를 등록하고,
구글 OAuth 클라이언트에도 해당 Redirect URI를 등록한다. 비밀 값은 저장소에 넣지 않는다.

기존 스키마는 [Flyway 도입 절차](flyway-adoption.md)에 따라 `20260922.00`으로 baseline 한 뒤
통합 스키마 `V20260922_01`과 사용자 식별자 제약 `V20260922_02`를 순서대로 적용한다.
새 빈 DB에서는 baseline 스키마 `B20260922_01`을 사용한다. 이미 중복된 `login_identifier`가
있다면 `V20260922_02`가 실패하므로 계정 소유권을 확인하고 처리한 뒤 재시도한다.
새 로그인은 `google:{sub}` 또는 `kakao:{id}`로 저장하며 기존 사용자 ID와 연관 데이터는 유지한다.
서로 다른 공급자 계정은 동일 이메일이어도 자동 연결하지 않는다.
