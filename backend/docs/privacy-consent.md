# 개인정보 처리와 분석 선택 관리

회원의 선택은 인증된 계정에, 비회원의 선택은 동의 전용 브라우저 쿠키에 적용한다. 로그인과
로그아웃은 두 범위의 선택을 복사하지 않는다. 로그인 고지, 알림 설정 신청, 선택적 분석 허용은
서로 다른 행위다. 회원 이메일은 관리자의 회원 조회·검색 목적으로 유지하고 탈퇴 시 삭제한다.

## 처리 기준과 공개 안내

- 운영 주체: 공공주택 복덕방
- 개인정보 보호 및 권리행사 담당부서: 공공주택 복덕방 운영팀
- 연락처: `toadzip.official@gmail.com`
- 문의·열람·정정·삭제·처리정지·탈퇴는 팀 이메일로 접수하고 본인 확인 후 운영자가 처리한다.
- 내부 책임자 실제 지정, 회원 이메일·최소 증빙의 처리 근거, 백업·메일 제공자의 실제 삭제 조건은
  운영 확인 사항이다. 소셜 로그인 제공자의 동의 화면을 서비스 전체 동의로 해석하지 않는다.

공개 원문은 `src/main/resources/privacy`의 Markdown과 `manifest.properties`를 사용한다.
manifest는 현재 문서의 버전과 모든 발행 원문의 키·시행일·scope version·SHA-256을 보관한다.
시작 시 원문 해시가 다르면 애플리케이션 시작을 실패시킨다. 발행 버전의 내용은 덮어쓰지 않고
새 버전을 추가하며 과거 버전도 `documents` 목록에 유지한다. 새 scope는 이전 scope를 재사용하지
않는다. 단순 오탈자 수정은 notice version만 바꿀 수 있으나 처리 범위를 확대하면 scope도 바꾼다.
`PRIVACY_POLICY`의 `privacy-scope-1`은 OAuth 시작과 콜백 사이 회원 처리 범위의 호환성을
확인한다. 같은 scope에서는 시작 시 안내 버전을 유지하고 범위가 달라지면 로그인 안내로 복귀한다.

현재 분석 안내는 `analytics-2026-10-09-v2`, 처리 범위는 `analytics-scope-2`다. 익명 통계·미수집을
전제로 한 v1 허용은 새 범위의 동의로 사용하지 않는다. 기존 `analytics-scope-1` 허용은
`RECONSENT_REQUIRED`이며 최신 안내에 명시적으로 다시 동의해야 한다. 새 범위의 시행일은 이전
범위보다 늦게 기록하여 과거 동의의 최초 무효화 시각과 증빙 파기 기산점을 유지한다.

`AnalyticsCollectionPolicy`는 현재 범위와 일치하고 만료되지 않은 `GRANTED`만 허용한다.
전면 수집 차단이나 이를 해제하는 별도 환경 플래그는 없다. 현재 유효한 동의가 있으면
`collectionAllowed=true`를 반환하고 자체 행동 분석 API가 수집을 허용한다. 미동의·거부·철회·만료·
이전 범위는 차단한다. 회원/브라우저 식별자, 개인별 이용 이력·프로필·replay를 익명 통계로 취급하지 않는다.

프론트의 GA4·PostHog 전송은 이 동의 판정과 각 SDK 설정을 함께 따른다. 동의 관리 코드의 완성과
외부 제공자 운영 설정 확인은 별개다. 실제 전송 항목·국외이전 안내, 관리 콘솔의 보유기간·삭제 조건은
운영 환경에서 확인해야 하며 코드가 제공자의 보유기간을 자동으로 설정하거나 검증하지 않는다.

## API와 소유자

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

## 저장과 유효 상태

`analytics_consents`에는 회원 ID 또는 비회원 토큰 해시 중 하나만 존재한다.
`analytics_consent_events`는 선택 증빙·명령 중복 방지를 담당하며 이메일·IP·User-Agent·전체 URL은
추가 수집하지 않는다. 회원 삭제 시 두 테이블의 해당 회원 기록도 삭제된다.

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

## 보유·파기와 동시성

기간의 단일 코드 기준은 `privacy.domain.PrivacyRetentionPolicy`다.

| 대상 | 기간/파기 기준 |
|---|---|
| 회원 허용·비회원 선택 | 명시적 선택부터 180일 |
| 회원 거부·철회와 현재 상태/revision | 계정 존속 기간 |
| 비회원 준비 context | 생성부터 24시간 |
| 유효 선택 증빙 | 해당 선택이 유효한 동안 |
| 대체·만료·scope 변경된 증빙 | 효력 상실부터 최대 90일 |
| 만료된 비회원 context | 만료부터 최대 90일, 증빙과 함께 삭제 |
| 알림 활성 설정 | 서울 달력 기준 신청부터 12개월 |
| 비활성 알림 설정 / 업무 이벤트 | 실제 비활성 시점 / 이벤트 발생부터 90일 |
| 탈퇴/OAuth 경합 표식 | 탈퇴부터 10분 |
| 개인정보 요청 최소 처리 결과 | 완료부터 최대 90일, 본인 확인 원자료는 확인 즉시 삭제 |
| 개인정보 포함 백업 | 생성부터 최대 7일, 외부 저장소의 수명주기 확인 필요 |

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

## 운영 지표와 경보

job=`consent`의 아래 Micrometer 지표를 노출한다. 동일 이름의 notification/user-deletion job은
각 기능의 파기를 나타낸다. 첫 실행 전 마지막 성공 값은 0이며 성공한 것처럼 채우지 않는다.

- `privacy.retention.last.success.seconds`: 마지막 성공 UTC epoch seconds
- `privacy.retention.deleted.total`: 삭제 행 수
- `privacy.retention.consecutive.failures`: 연속 실패
- `privacy.retention.overdue.count`: 파기 기한을 지난 잔여 행 수
- `privacy.retention.oldest.overdue.seconds`: 최대 지연

지연은 저장된 `purge_after`와 scope 무효화에 따른 기한 중 더 이른 실효 기한으로 계산한다.
소유자 잠금 때문에 기한 갱신·파기를 건너뛴 행도 지연 건수에 포함하므로 배치가 성공했다는 이유로
잠긴 대상의 파기 지연이 감춰지지 않는다.

실패 시 개인정보 없는 오류를 기록한다. 30분 이상 성공이 없거나 파기 지연이 30분을 넘으면
외부 모니터링에서 운영팀으로 경보를 연결해야 한다. 스케줄 자체 정지는 앱 외부의 마지막 성공
감시로 감지한다. 지표만 있고 실제 수신 경로가 없는 상태는 운영 연결 완료가 아니다.

문의 메일, 백업, 외부 제공자의 자료는 DB 배치로 지워지지 않는다. 메일은 완료일 기준으로 정리하고
불필요한 확인 자료를 즉시 삭제한다. 백업 복원 후 사용자 접근을 열기 전에 이후의 삭제 요청을
재적용한다. 실제 수명주기·삭제·경보 전송은 해당 환경에서 별도로 확인한다.

## 검증 계약

DDL 적용·기존 데이터 처리·배포 전후 점검·복구 기준은
[개인정보 기능의 DB 배포](privacy-deployment.md)를 따른다. 새 이미지 시작 시 PRIMARY DB에
Flyway V20261009.01~04를 적용하며, 정상 기동과 스키마 점검이 끝난 뒤 프론트를 시작한다.

- 만료 직전/정각/직후, scope 변경, 버전 없는 철회, 조회·재시도의 기간 유지
- 다른 계정/context 위조, 관리자/비회원/회원 권한 구분과 CSRF
- 첫 회원 행 동시 생성, CAS, 동일 명령의 다른 본문, 과거 receipt와 최신 current 분리
- 상태·이력 원자성, 실패한 파기 청크 재시도, 중복 실행, 재선택과 파기의 경합
- 실제 PostgreSQL 제약·삭제 범위와 현재 revision/유효 증빙 보존
- 유효한 현재 범위의 허용에 따른 context·자체 분석 수집과 회원/비회원 분리
- 미동의·거부·철회·만료·이전 범위 수집 차단, v1 요청 거절과 v2 재동의
- 철회와 수집의 양방향 잠금 경합, 잠금 대기 중 만료된 요청의 저장 차단

관련 계약: [회원 로그인](user-social-login.md), [알림 설정](notification-settings.md),
[관측과 경보](observability.md), [프론트 개인정보 처리](../../frontend/docs/privacy-consent.md).
