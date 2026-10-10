# 분석 동의

회원의 선택은 인증된 계정에, 비회원의 선택은 동의 전용 브라우저 쿠키에 적용한다. 로그인과
로그아웃은 두 범위의 선택을 복사하지 않는다. 로그인 고지, 알림 설정 신청, 선택적 분석 허용은
서로 다른 행위다. 이 문서는 동의 API·유효 상태·파기 동시성의 원본이다.
처리 목적·보유기간·권리행사와 미확정 운영 항목은 [운영 정책](../../../docs/privacy-policy.md)을 따른다.

## 공개 안내

공개 원문은 `src/main/resources/privacy`의 Markdown과 `manifest.properties`를 사용한다.
manifest는 현재 문서의 버전과 모든 발행 원문의 키·시행일·scope version·SHA-256을 보관한다.
시작 시 원문 해시가 다르면 애플리케이션 시작을 실패시킨다. 발행 버전의 내용은 덮어쓰지 않고
새 버전을 추가하며 과거 버전도 `documents` 목록에 유지한다. 새 scope는 이전 scope를 재사용하지
않는다. 단순 오탈자 수정은 notice version만 바꿀 수 있으나 처리 범위를 확대하면 scope도 바꾼다.
`PRIVACY_POLICY`의 버전·scope는 문서 메타데이터이며 로그인 허용 조건이 아니다. 조회 실패나
버전 누락·변경으로 로그인을 막지 않는다. 가입 시 확인 가능한 선택적 버전만 별도로 기록하고
읽음·동의를 추정하지 않는다. OAuth 10분 만료와 state 검증은 [로그인 계약](USER.md)을 따른다.

현재 분석 안내는 `analytics-2026-10-09-v2`, 처리 범위는 `analytics-scope-2`다. 익명 통계·미수집을
전제로 한 v1 허용은 새 범위의 동의로 사용하지 않는다. 기존 `analytics-scope-1` 허용은
`RECONSENT_REQUIRED`이며 최신 안내에 명시적으로 다시 동의해야 한다. 새 범위의 시행일은 이전
범위보다 늦게 기록하여 과거 동의의 최초 무효화 시각과 증빙 파기 기산점을 유지한다.

`AnalyticsCollectionPolicy`는 현재 범위와 일치하고 만료되지 않은 `GRANTED`만 허용한다.
전면 수집 차단이나 이를 해제하는 별도 환경 플래그는 없다. 현재 유효한 동의가 있으면
`collectionAllowed=true`를 반환하고 자체 행동 분석 API가 수집을 허용한다. 미동의·거부·철회·만료·
이전 범위는 차단한다. 회원/브라우저 식별자, 개인별 이용 이력·프로필·replay를 익명 통계로 취급하지 않는다.

프론트의 SDK 초기화·전송·중단과 제공자별 설정은 [프론트 계약](../../../frontend/docs/privacy-consent.md)을 따른다.

## 동의 API

| API | 의미 |
|---|---|
| `GET /api/v1/privacy/notices/current` | 공개 현재 버전·URL·해시 |
| `GET /api/v1/privacy/notices/{key}/{version}` | 로그인 없는 버전 원문 조회 |
| `GET /api/v1/privacy/analytics-context` | 실제 인증/쿠키 주체의 현재 결정·유효 상태 |
| `POST /api/v1/privacy/analytics/guest-context` | 비회원의 최초 선택 직전 쿠키와 UNSET context 준비 |
| `POST /api/v1/privacy/analytics/guest` | 쿠키와 제출 context가 일치하는 비회원 선택 변경 |
| `POST /api/v1/privacy/analytics/me` | ROLE_USER 계정의 선택 변경 |

상태·변경 응답은 `Cache-Control: no-store`다. 모든 변경은 CSRF 보호를 적용하며 관리자 인증을
비회원으로 취급하지 않는다. 회원 ID의 권한은 principal에서만 얻는다. `expectedUserId`와
`contextId`는 늦은 요청이 다른 주체에게 적용되는 것을 막기 위한 비교값이다.

명령은 `commandId`, `expectedRevision`, `action`(`GRANT/DENY/WITHDRAW`),
`source`(`FIRST_VISIT/SETTINGS`)를 받는다. 회원은 `expectedUserId`, 비회원은 `contextId`를
제출한다. GRANT는 현재 `noticeVersion/scopeVersion`이 필수다. DENY/WITHDRAW는 버전 없이
가능하며 서버 처리 시점 버전을 기록한다. 이 기록은 새 안내를 읽거나 동의했다는 증거가 아니다.

응답은 `{receipt,current}`다. 동일 명령 재전송은 최초 receipt와 최신 current를 반환한다.
과거 허용 receipt를 철회 후 재전송해도 current는 철회다. 같은 명령 ID의 다른 본문은
`PRIVACY_COMMAND_CONFLICT`, 다른 명령의 오래된 revision은 `PRIVACY_REVISION_CONFLICT`다.
충돌을 새 revision으로 조용히 덮어쓰지 않는다. 변경·이력은 한 트랜잭션으로 저장한다.

## 저장

스키마·메타 기록·파기는 신규 개인정보 테이블만 대상으로 한다. 기존 테이블과 자료는 변경하지 않는다.
정상 가입·알림 신청 등 기존 업무 연산과의 구분, 테이블 소유권·제거 영향은
[모듈 분리 계약](PRIVACY_STORAGE.md)을 따른다.

### 유효 상태

`privacy_analytics_consents`에는 회원 ID 또는 비회원 토큰 해시 중 하나만 존재한다.
`privacy_analytics_consent_events`는 선택 증빙·명령 중복 방지를 담당하며 이메일·IP·User-Agent·전체 URL은
추가 수집하지 않는다. 기존 회원 테이블에는 외래키를 만들지 않고 ID를 논리 참조한다.
회원이 별도의 승인된 절차로 삭제된 경우 고아 선택·증빙은 신규 테이블만 대상으로 다음 성공 배치에서 파기한다.

저장 결정은 `UNSET/GRANTED/DENIED/WITHDRAWN`이다. `EXPIRED/RECONSENT_REQUIRED`는 조회 시
계산한다. 허용의 scope가 다르면 재동의를 요구하고, 만료 정각부터 허용하지 않는다. 회원 거부·철회는
범위 변경으로 다시 허용되지 않는다. 동일 상태를 새 명령으로 선택해도 revision은 증가하지만
동일 명령 재시도·조회·로그인으로 기간을 연장하지 않는다.

운영 쿠키는 `__Host-toadzip-privacy; Secure; HttpOnly; SameSite=Lax; Path=/`이며 Domain은 없다.
256비트 난수 원문은 쿠키에만 저장하고 서버에는 SHA-256만 저장한다. HTTP 개발은 local/test
프로파일에서만 `privacy.cookie.secure=false`와 별도 `toadzip-privacy-local` 이름을 사용한다.

비회원 준비와 선택은 별도 요청이다. 쿠키 없는 응답 유실이 허용 기록을 생성하지 않게 하며,
프론트는 Web Locks로 최초 선택을 직렬화한다. 쿠키/잠금이 불가능하면 허용을 활성화하지 않는다.
실패한 거부·철회는 브라우저 pending-stop으로 즉시 차단하고 같은 주체의 동일 명령만 재시도한다.

## 파기와 동시성

보유기간은 [운영 정책](../../../docs/privacy-policy.md)의 보유·파기 표를 따른다.
신규 DB 자료의 기간 계산은 `privacy.domain.PrivacyRetentionPolicy`를 사용한다.

현재 허용 이벤트의 `purge_after`는 만료+90일이다. 대체되면 이전 값과 대체시각+90일 중 더
이른 값으로 당긴다. scope 변경도 처음 무효화된 시행일+90일로 앞당기며 반복 실행으로 연장하지
않는다. 이력 삭제 후에도 현재 회원 revision은 유지하므로 오래된 명령이 다시 적용되지 않는다.

`PrivacyRetentionService`는 15분마다 최대 500행의 작은 트랜잭션으로 실행한다. 실행 한도는
60초이며 청크에는 20초 트랜잭션 제한이 있다. 후보 확인 후 회원→동의→이력 순서로 잠그고
파기 조건을 다시 확인한다. 다른 트랜잭션이 잠근 소유자는 건너뛰어 다음 실행에서 처리한다.
재선택이 먼저 확정된 유효한 증빙은 삭제하지 않는다. 청크 실패는 롤백되고 다음 실행에서
같은 기산점으로 재시도한다. 현재 회원 행은 배치가 삭제하지 않는다.

자체 행동 수집도 같은 서비스 트랜잭션에서 `AnalyticsConsentRepository.lockForCollection`로
동의 행을 잠그고 `AnalyticsCollectionPolicy.requireAllowed`를 확인한 뒤 저장해야 한다.
잠금 획득 뒤 현재 시각으로 만료를 판정한다. 철회가 먼저 잠그면 대기하던 수집은 거절하고,
수집이 먼저 잠그면 해당 저장까지 완료한 뒤 철회를 적용하여 이후 수집을 거절한다.
유효한 허용이 없는 자체 분석 요청은 `403 ANALYTICS_CONSENT_REQUIRED`를 반환한다.
분석 철회와 알림 신청은 별도 처리다. 조회·노출 분석 API가 알림 설정을 변경하지 않게 한다.

### 지연 확인

`job=consent`로 [공통 파기 지표와 경보](../../../docs/privacy-policy.md)를 제공한다.
첫 실행 전 마지막 성공 값은 0이며 성공한 것처럼 채우지 않는다.

지연은 저장된 `purge_after`와 scope 무효화에 따른 기한 중 더 이른 실효 기한으로 계산한다.
소유자 잠금 때문에 기한 갱신·파기를 건너뛴 행도 지연 건수에 포함하므로 배치가 성공했다는 이유로
잠긴 대상의 파기 지연이 감춰지지 않는다.

실패 오류에 개인정보를 기록하지 않는다. 외부 경보 수신과 메일·백업·제공자 자료의 삭제는
DB 배치와 별도의 운영 절차다.

## 검증

DDL 적용·기존 데이터 보호·배포 전후 점검과 복구는 [DB 배포](../operations/PRIVACY.md)를 따른다.
이 API의 회귀 검증은 다음을 포함한다.

- 만료 직전/정각/직후, scope 변경, 버전 없는 철회, 조회·재시도의 기간 유지
- 다른 계정/context 위조, 관리자/비회원/회원 권한 구분과 CSRF
- 첫 회원 행 동시 생성, CAS, 동일 명령의 다른 본문, 과거 receipt와 최신 current 분리
- 상태·이력 원자성, 실패한 파기 청크 재시도, 중복 실행, 재선택과 파기의 경합
- 실제 PostgreSQL 제약·삭제 범위와 현재 revision/유효 증빙 보존
- 유효한 현재 범위의 허용에 따른 context·자체 분석 수집과 회원/비회원 분리
- 미동의·거부·철회·만료·이전 범위 수집 차단, v1 요청 거절과 v2 재동의
- 철회와 수집의 양방향 잠금 경합, 잠금 대기 중 만료된 요청의 저장 차단

관련 계약: [회원 로그인](USER.md), [알림 설정](../operations/NOTIFICATIONS.md),
[관측과 경보](../observability.md), [프론트 개인정보 처리](../../../frontend/docs/privacy-consent.md).
