# 로그인과 사용자 의견

## 로그인

카카오·구글 인증 후 애플리케이션 세션으로 로그인한다. 인가 코드·공급자 토큰을 프론트엔드에 전달하지 않는다.

### API

| 요청 | 결과 |
|---|---|
| GET `/api/auth/oauth2/authorization/{provider}` | `google`·`kakao` 인증 시작, 콜백 후 설정된 성공·실패 URL로 이동 |
| GET `/api/auth/me` | `{id, email}`. 이메일 미제공은 null, 비로그인 401·관리자만 로그인하면 403 |
| GET `/api/auth/csrf` | CSRF 쿠키와 `{token, headerName}` |
| POST `/api/auth/logout` | 세션 쿠키·CSRF 필요, 성공 204 |

브라우저 요청에는 세션 쿠키를 포함한다. 관리자와 사용자 권한은 분리된다.

### 정책 안내와 인증 요청

로그인 화면에 개인정보처리방침 링크를 제공한다. 표시한 정책의 `policyVersion`을 OAuth 시작 URL에 선택적으로 전달한다.
정책 조회 실패·버전 누락·변경이나 분석 미동의·거부·철회는 로그인을 막지 않는다.
서버가 보관 원문으로 확인할 수 있는 버전만 기록하며 최신 버전을 대신 채우지 않는다.

OAuth 요청은 세션별 state로 구분하고 콜백에서 한 번만 사용한다. 여러 탭의 요청은 서로 덮어쓰지 않는다.
발급 후 10분 미만에만 유효하며 만료·잘못된 state·다른 세션·재사용은 거절한다.
실패 화면에서는 인증 취소·만료 가능성을 안내하고 소셜 버튼으로 다시 시작한다.

신규 가입에 확인 가능한 버전이 있으면 고지 메타를 회원 생성과 한 트랜잭션으로 저장한다.
버전은 전달된 정책 링크를 나타낼 뿐 열람·동의·노출 증거가 아니다. 기존 회원과 버전 없는 가입에는 소급 기록하지 않는다.
고지 저장 실패는 신규 가입도 롤백하며 로그인으로 분석 선택·revision·보유기간을 변경하지 않는다.

### 공급자와 환경 설정

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

[로컬 실행](../../../docs/SETUP.md#로컬)으로 다시 빌드하고 두 공급자의 로그인·실패·로그아웃을 확인한다.
운영은 HTTPS 서비스 오리진으로 콜백·이동 URL을 맞춘다. 성공 경로는 `/`, 실패는 `/?login=failed`다.
앱 직접 실행 기본값은 `http://localhost:5173`, Compose는 `http://localhost`를 사용한다. 이전 `/login`도 메인 화면으로 연결된다.

### 계정 보존

식별자는 `google:{sub}`·`kakao:{id}`다. 기존 사용자 ID와 연관 데이터를 유지한다.
이메일은 공급자가 제공할 때 저장하며 같은 이메일의 서로 다른 공급자 계정을 자동 연결하지 않는다.
회원 이메일은 관리자의 회원 조회·검색에 사용한다. 알림 신청에서 수집·수정하지 않으며 알림 해제로 삭제하지 않는다.
스키마·기존 로그인 제약은 [Flyway 적용](../operations/DATABASE.md#flyway)을 따른다. 중복 식별자는 계정 소유권을 확인한 뒤 처리한다.

가입 고지는 [개인정보 전용 테이블](PRIVACY_STORAGE.md)에 저장한다. 기존 `users`에 개인정보용 컬럼·FK를 추가하지 않는다.
회원이 사라진 고지만 15분 주기의 다음 성공 배치에서 정리한다. 청크는 최대 500행, 실행은 최대 1분이다.
회원 삭제 API는 제공하지 않으며 문의·권리행사는 [운영 정책](../../../docs/privacy-policy.md#권리행사와-탈퇴-절차)을 따른다.
이미 없는 회원의 세션은 무효화하고 401을 반환한다.

## 사용자 의견

로그인 없이 의견을 제출하고 관리자가 검색·조회한다.

### 의견 제출

- `POST /api/v1/feedback`: 로그인 없이 `{ "content": "의견 내용" }` 제출
- `GET /api/auth/csrf`에서 받은 토큰을 요청 헤더에 전달한다. 기존 CSRF 보호를 유지한다.
- 내용은 필수이며 UTF-16 기준 최대 2,000자다. 양끝 공백을 제거하고 내부 줄바꿈은 보존한다.
- NBSP·좁은 NBSP·BOM을 포함한 양끝 공백도 제거하며 공백만 담긴 의견은 거절한다.
- 성공은 `201`과 `{ "data": { "id": 1 } }`, 검증 실패는 기존 `VALIDATION_FAILED` 오류 계약이다.
- 본문과 UTC 접수 시각만 저장한다. 회원 ID, 이메일, IP, 화면 URL을 별도로 수집하지 않는다.
- 공개 조회 API는 제공하지 않는다. 화면에서는 개인정보 입력을 피하도록 안내한다.

### 배포와 복구

Flyway·Hibernate validate로 스키마를 확인한다. 이전 앱으로 되돌려도 접수 데이터와 테이블은 보존한다.

### 관리자 조회

- `GET /api/admin/feedback?keyword=검색&page=0&size=20`은 ADMIN 세션만 접근할 수 있다.
- 검색어는 최대 200자, page는 0 이상, size는 1~100이며 기본값은 20이다.
- 본문을 대소문자 구분 없이 검색한다. `%`, `_`, `!`는 와일드카드가 아닌 입력 문자로 취급한다.
- `createdAt DESC, id DESC`로 정렬한다. `createdAt`은 UTC ISO 8601 시각이며 관리자 화면은 한국 시각으로 표시한다.
- 응답은 `data` 안에 `items`, `page`, `hasNext`, `totalElements`, `totalPages`를 포함한다.
- 각 항목은 `id`, 전체 `content`, `createdAt`을 포함한다. 범위 밖 페이지도 전체 검색 건수를 보존한다.
- 검색 건수를 먼저 확인하므로 매우 큰 범위 밖 페이지도 JPA offset 제한에 걸리지 않고 빈 목록을 반환한다.
