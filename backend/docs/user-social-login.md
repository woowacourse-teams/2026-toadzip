# 사용자 소셜 로그인

## API

로그인 화면은 개인정보처리방침 링크를 제공한다. 공개 안내 API 조회 중·실패·버전 누락에도
`GET /api/auth/oauth2/authorization/kakao` 또는 `GET /api/auth/oauth2/authorization/google`을
사용할 수 있다. 버전이 있는 정책 링크를 표시했다면 `?policyVersion={version}`을 선택적으로 전달한다. 공급자 인증 후
`/api/auth/oauth2/callback/{provider}`로 돌아오며 성공 시 설정한 프론트엔드 URL,
실패 시 실패 URL로 이동한다. 쿼리의 인가 코드나 공급자 토큰을 프론트엔드에 전달하지 않는다.

- `GET /api/auth/me`: 로그인한 사용자에게 `{ "id": 123, "email": "user@example.com" }`.
  공급자가 이메일을 제공하지 않으면 `email`은 `null`이다. 비로그인 401, 관리자만 로그인한 경우 403.
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
5. **카카오 로그인 > 동의항목**에서 이메일(`account_email`) 동의를 설정한다.
6. REST API 키의 Redirect URI에 아래 주소를 정확히 등록한다.

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
USER_OAUTH_SUCCESS_URL=http://localhost/
USER_OAUTH_FAILURE_URL=http://localhost/?login=failed
~~~

전체 프로젝트를 다시 빌드해 실행한다.

~~~shell
docker compose -f compose.yaml -f compose.local.yaml -f compose.monitoring.yaml \
  up --detach --build --wait
~~~

브라우저에서 **http://localhost/**를 열고 헤더의 **로그인** 버튼으로 모달을 연다.
카카오 또는 구글 버튼을 누르고 인증을 완료하면 메인 화면으로 돌아오며 헤더에 **로그인됨**이 표시된다.
실패하면 메인 화면 위 로그인 모달의 중앙에 재시도 안내를 표시한다.

설정이 덜 끝난 동안에는 **USER_OAUTH_ENABLED=false**로 두면 나머지 서비스는 정상 실행된다.

## 환경별 설정과 배포

`USER_OAUTH_ENABLED=true`로 켜고 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `USER_OAUTH_REDIRECT_BASE_URL`을 설정한다.
마지막 값은 후행 `/`가 없는 브라우저 공개 서비스 오리진이다. 등록할 콜백 주소는 이 값에
`/api/auth/oauth2/callback/google`과 `/api/auth/oauth2/callback/kakao`를 붙인 것이다.
`USER_OAUTH_SUCCESS_URL`, `USER_OAUTH_FAILURE_URL`은 서버가 정한 프론트엔드 이동 주소이며
애플리케이션 직접 실행 기본값은 각각 `http://localhost:5173/`,
`http://localhost:5173/?login=failed`이며 Compose는 `http://localhost/` 경로를 사용한다.
운영에서는 서비스 오리진으로 명시한다. 성공 URL은 `/`, 실패 URL은 `/?login=failed`를 사용한다.
이전 `/login` 주소도 메인 화면으로 연결되므로 기존 설정으로 복귀해도 새 로그인 흐름을 사용할 수 있다. 카카오 개발자 콘솔에서 로그인과 Redirect URI를 등록하고,
구글 OAuth 클라이언트에도 해당 Redirect URI를 등록한다. 비밀 값은 저장소에 넣지 않는다.

이력이 없는 기존 스키마의 [Flyway 도입](flyway-adoption.md)은 백업·격리 검증과 별도 승인 후
`20260922.00` 기준선과 후속 마이그레이션을 적용하는 사전 절차다. 개인정보 앱은 이 도입을
기동 중 자동 수행하지 않으며 이력이 없거나 폐기된 개인정보 설계 흔적이 있으면 중단한다.
새 빈 DB에서는 baseline 스키마 `B20260922_01`을 사용한다. 이미 중복된 `login_identifier`가
있다면 `V20260922_02`가 실패하므로 계정 소유권을 확인하고 처리한 뒤 재시도한다.
새 로그인은 `google:{sub}` 또는 `kakao:{id}`로 저장하며 기존 사용자 ID와 연관 데이터는 유지한다.
이메일은 로그인 시 공급자가 제공한 경우에만 저장하고 관리자의 회원 조회·검색에 사용한다.
알림 신청에서는 이메일을 수집·수정하지 않으며 실제 이메일 알림을 발송하지 않는다.
서로 다른 공급자 계정은 동일 이메일이어도 자동 연결하지 않는다.

## 정책 버전과 계정 생명주기

개인정보처리방침 조회·버전과 로그인 가능 여부는 독립적이다. 정책 버전이 없거나 최신이 아니어도
로그인을 허용하며, 정책 scope 변경만으로 신규·기존 회원의 로그인을 일괄 거절하지 않는다.
분석 미동의·거부·철회도 로그인과 기본 기능의 제한 조건이 아니다.

`SocialAuthorizationRequestResolver`는 전달된 `policyVersion`이 서버의 보관된 `PRIVACY_POLICY`
버전인 경우에만 그 값을 보존한다. 누락·빈 값·알 수 없는 버전·다른 종류의 문서는 null로 둔다.
최신 버전을 대신 채우거나 OAuth 제공자의 동의 화면에서 내부 고지를 추정하지 않는다.
이 값은 로그인 진입 화면이 전달한 정책 링크의 버전이다. 실제 열람·읽음·동의 또는 링크 노출을
서버가 직접 관측했다는 증거가 아니다. 버전 없는 경로는 고지를 확인했다고 기록하지 않는다.

버전 메타와 별개로 서버 발급 시각을 반드시 기록한다. `SocialAuthorizationRequestRepository`는
세션별 state에 인가 요청을 연결하고 콜백에서 한 번만 소비한다. 여러 탭은 서로 덮어쓰지 않는다.
요청은 10분 미만에만 유효하며 정확히 10분, 미래 발급 시각, 요청 없음·다른 세션·잘못된 state·
재사용은 거절한다. 정책 버전이 null인 유효한 요청과 요청 자체가 없는 경우를 구분한다.
일반 실패 화면은 인증 취소·요청 만료 가능성을 안내하고 소셜 버튼으로 다시 시작하도록 한다.

`SocialUserService`는 검증된 요청으로 기존 회원을 찾으면 정책 조회 없이 기존 ID와 이메일 처리
규칙을 유지한다. 신규 회원이고 보관 원문으로 확인할 수 있는 버전이 있는 경우에만
`privacy_registration_notices`에 전달된 버전과 저장 시각을 기록한다. 이전 버전도 그대로 보존한다.
확인 가능한 버전이 없어 기록 없이 완료한 신규 가입이나 기존 회원에게 다음 로그인 때 고지를
소급 생성하지 않는다. 기록을 시도한 경우 SQL 실패는 신규 회원 생성과 함께 롤백한다.
회원의 분석 선택·revision·보유기간은 로그인으로 변경하지 않는다.

`user_id`는 FK 없는 논리 참조다. 기존 `users`에는 개인정보용 컬럼·제약·인덱스를 추가하지 않는다.
신규 회원 생성과 선택적 고지 기록은 한 트랜잭션이며, 동일 소셜 식별자의 동시 생성은
`SocialUserLockRepository`의 advisory transaction lock으로 직렬화한다.
회원 엔티티는 가입 고지와 분석 동의 모델을 참조하지 않는다.

세션의 공급자 principal 저장 차단, 성공 후 ROLE_USER 세션 ID 변경, CSRF, 관리자/회원 권한 분리,
없는 회원 세션 무효화와 동의 소유권 검사는 유지한다. 개인정보정책 버전은 이 보안 검사들의
대체 수단이 아니다. 회원 이메일 수집·저장과 관리자 조회·검색 용도도 유지한다.

처리 목적 확대 등 별도 동의가 필요한 기능을 추가할 때는 해당 기능의 동의 근거·항목·거부 효과를
별도로 설계한다. 현재 분석 범위 확대는 분석 동의의 scope와 수집 게이트가 처리하며, 로그인이나
전체 개인정보처리방침에 대한 포괄 동의를 요구하는 방식으로 대신하지 않는다.

가입 고지는 회원이 존재하는 동안 보관한다. `UserRegistrationRetentionService`는 15분마다 기존
`users`의 존재 여부만 조회하고 고아 고지에 파기 시각을 부여해 같은 청크에서 삭제한다.
다음 성공 배치에서 처리하며, 한 트랜잭션당 최대 500행, 한 실행당 최대 1분으로 제한한다.
잠금·장애로 지연될 수 있으므로 [공통 파기 감시·경보](../../docs/privacy-policy.md)를 적용한다.
배치는 신규 `privacy_registration_notices`만 수정·삭제하며 회원·이메일·관심 자료를 삭제하지 않는다.
`privacy.retention.*{job="registration-notice"}`로 마지막 성공, 연속 실패와 잔여 건수를 확인한다.

개인정보 기능은 회원 삭제 API나 자동 계정 삭제를 제공하지 않는다. 문의·권리행사와 기존 자료
처리는 [운영 정책](../../docs/privacy-policy.md)의 본인 확인·별도 승인 절차를 따른다. `DELETE /api/admin/users/{userId}`는 제공하지 않는다.
`DeletedUserSessionFilter`는 회원이 이미 없는 세션을 읽기 전용으로 검사하여 무효화하고 401을 반환한다.

가입 고지·동의 기능의 제거 범위와 기존 업무 보존 기준은 [모듈 분리 계약](privacy-isolation.md)을 따른다.
